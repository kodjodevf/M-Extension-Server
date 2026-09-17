package mextensionserver.impl

internal object MpegTsSanitizer {
    private const val PACKET_SIZE = 188
    private const val SYNC_BYTE = 0x47.toByte()
    private const val MIN_SYNC_RUN = 4
    private const val MAX_RESYNC_DISTANCE = 8 * 1024

    fun repair(data: ByteArray): ByteArray {
        if (data.size < PACKET_SIZE * MIN_SYNC_RUN || data[0] != SYNC_BYTE) return data
        if (isAligned(data)) return data

        val packets = mutableListOf<ByteArray>()
        val discardUntilPayloadStart = mutableSetOf<Int>()
        var cursor = 0
        var repaired = false
        while (cursor + PACKET_SIZE <= data.size) {
            val hasCompleteNextPacket = cursor + (PACKET_SIZE * 2) <= data.size
            if (data[cursor] == SYNC_BYTE &&
                (!hasCompleteNextPacket || data[cursor + PACKET_SIZE] == SYNC_BYTE)
            ) {
                val packet = data.copyOfRange(cursor, cursor + PACKET_SIZE)
                val pid = packet.pid()
                if (pid !in discardUntilPayloadStart || packet.isPayloadStart()) {
                    discardUntilPayloadStart.remove(pid)
                    packets += packet
                } else {
                    repaired = true
                }
                cursor += PACKET_SIZE
                continue
            }

            // The injected block can begin inside the current packet. Do not
            // preserve that packet merely because its leading sync byte is
            // intact. Remove its whole PES unit so a truncated AAC frame is
            // never forwarded to the decoder.
            if (data[cursor] == SYNC_BYTE && cursor + 3 < data.size) {
                val damagedPid = data.pidAt(cursor)
                discardCurrentPes(packets, damagedPid)
                discardUntilPayloadStart += damagedPid
            }
            val nextSync = findNextSyncRun(data, cursor + 1)
            if (nextSync < 0) return data
            repaired = true
            cursor = nextSync
        }

        val result = ByteArray(packets.size * PACKET_SIZE)
        packets.forEachIndexed { index, packet ->
            packet.copyInto(result, index * PACKET_SIZE)
        }
        return if (repaired && isAligned(result)) result else data
    }

    private fun discardCurrentPes(
        packets: MutableList<ByteArray>,
        pid: Int,
    ) {
        var reachedPayloadStart = false
        for (index in packets.lastIndex downTo 0) {
            val packet = packets[index]
            if (packet.pid() != pid) continue
            packets.removeAt(index)
            if (packet.isPayloadStart()) {
                reachedPayloadStart = true
                break
            }
        }
        if (!reachedPayloadStart) {
            packets.removeAll { it.pid() == pid }
        }
    }

    private fun ByteArray.pid(): Int = pidAt(0)

    private fun ByteArray.pidAt(offset: Int): Int = ((this[offset + 1].toInt() and 0x1F) shl 8) or (this[offset + 2].toInt() and 0xFF)

    private fun ByteArray.isPayloadStart(): Boolean = (this[1].toInt() and 0x40) != 0

    private fun isAligned(data: ByteArray): Boolean {
        if (data.isEmpty() || data.size % PACKET_SIZE != 0) return false
        return (data.indices step PACKET_SIZE).all { data[it] == SYNC_BYTE }
    }

    private fun findNextSyncRun(
        data: ByteArray,
        start: Int,
    ): Int {
        val lastCandidate =
            minOf(
                data.size - (PACKET_SIZE * MIN_SYNC_RUN),
                start + MAX_RESYNC_DISTANCE,
            )
        for (candidate in start..lastCandidate) {
            if ((0 until MIN_SYNC_RUN).all { data[candidate + (it * PACKET_SIZE)] == SYNC_BYTE }) {
                return candidate
            }
        }
        return -1
    }
}
