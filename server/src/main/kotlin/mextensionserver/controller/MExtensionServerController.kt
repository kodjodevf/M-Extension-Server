package mextensionserver.controller

import fi.iki.elonen.NanoHTTPD
import io.github.oshai.kotlinlogging.KotlinLogging
import mextensionserver.impl.MExtensionServerLoader
import java.io.IOException

class MExtensionServerController {
    private val logger = KotlinLogging.logger {}
    private var server: WebServer? = null

    fun start(port: Int) {
        try {
            server = WebServer(port)
            server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            val actualPort = server?.listeningPort ?: 0
            logger.info { "mextensionserver server started on port $actualPort" }
        } catch (e: IOException) {
            logger.error(e) { "Failed to start mextensionserver server" }
            throw e
        }
    }

    fun stop() {
        server?.stop()
        logger.info { "mextensionserver server stopped" }
        MExtensionServerLoader.cleanupTempFiles()
    }

    fun isRunning(): Boolean = server?.isAlive == true

    fun getPort(): Int = server?.listeningPort ?: 0

    private inner class WebServer(
        port: Int,
    ) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response =
            when (session.uri) {
                "/dalvik" -> DalvikHandler().serve(session)
                "/" -> newFixedLengthResponse("mextensionserver Server Running")
                "/stop" -> {
                    newFixedLengthResponse("Server stopping").also {
                        Thread {
                            Thread.sleep(100)
                            stop()
                        }.start()
                    }
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }
    }
}
