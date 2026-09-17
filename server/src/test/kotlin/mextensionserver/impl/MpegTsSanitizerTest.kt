package mextensionserver.impl

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertSame

class MpegTsSanitizerTest {
    @Test
    fun `removes variable interleaved junk and restores the packet grid`() {
        val packets =
            listOf(
                packet(0, 256, payloadStart = true),
                packet(1, 257, payloadStart = true),
                packet(2, 256),
                packet(3, 257),
                packet(4, 257),
                packet(5, 256),
                packet(6, 257, payloadStart = true),
                packet(7, 257),
                packet(8, 256),
                packet(9, 257),
                packet(10, 256),
                packet(11, 257),
            )
        val input =
            packets.take(4).flatten().toByteArray() +
                packets[4].take(70).toByteArray() +
                ByteArray(110) { (it + 1).toByte() } +
                packets[4].drop(70).toByteArray() +
                packets.drop(5).flatten().toByteArray()

        val expected = listOf(packets[0], packets[2], packets[5]) + packets.drop(6)
        assertContentEquals(expected.flatten().toByteArray(), MpegTsSanitizer.repair(input))
    }

    @Test
    fun `keeps an already aligned transport stream without copying`() {
        val input = List(8) { packet(it, 256) }.flatten().toByteArray()

        assertSame(input, MpegTsSanitizer.repair(input))
    }

    @Test
    fun `leaves unrecognized payloads unchanged`() {
        val input = ByteArray(2048) { it.toByte() }

        assertSame(input, MpegTsSanitizer.repair(input))
    }

    private fun packet(
        seed: Int,
        pid: Int,
        payloadStart: Boolean = false,
    ): List<Byte> =
        ByteArray(188) { index -> (seed + index).toByte() }
            .apply {
                this[0] = 0x47
                this[1] = (((pid shr 8) and 0x1F) or if (payloadStart) 0x40 else 0).toByte()
                this[2] = (pid and 0xFF).toByte()
                this[3] = 0x10
            }.toList()
}
