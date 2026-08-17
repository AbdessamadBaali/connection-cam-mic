package com.cammic.streamer

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tiny HTTP server that exposes:
 *   /        - info page
 *   /info    - JSON identification (used by the desktop app for discovery)
 *   /video   - MJPEG stream (multipart/x-mixed-replace)
 *   /audio   - endless WAV stream (16-bit PCM mono)
 *   /switch  - toggle front/back camera
 */
class StreamServer(
    private val port: Int,
    val sampleRate: Int,
    private val deviceName: String,
    private val onSwitchCamera: (() -> Unit)? = null
) {

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null

    private val frameLock = Object()
    private var latestFrame: ByteArray? = null
    private var frameId = 0L

    private val audioClients = CopyOnWriteArrayList<OutputStream>()

    fun start() {
        if (running) return
        running = true
        Thread({ acceptLoop() }, "http-server").start()
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: IOException) {}
        synchronized(frameLock) { frameLock.notifyAll() }
        for (client in audioClients) {
            try { client.close() } catch (_: IOException) {}
        }
        audioClients.clear()
    }

    fun publishFrame(jpeg: ByteArray) {
        synchronized(frameLock) {
            latestFrame = jpeg
            frameId++
            frameLock.notifyAll()
        }
    }

    fun publishAudio(buffer: ByteArray, length: Int) {
        for (client in audioClients) {
            try {
                client.write(buffer, 0, length)
            } catch (_: IOException) {
                audioClients.remove(client)
                try { client.close() } catch (_: IOException) {}
            }
        }
    }

    fun infoJson(): String {
        val safeName = deviceName.replace("\\", "").replace("\"", "")
        return """{"app":"cammic","name":"$safeName","port":$port,"sampleRate":$sampleRate}"""
    }

    private fun acceptLoop() {
        try {
            val socket = ServerSocket(port)
            serverSocket = socket
            while (running) {
                val client = socket.accept()
                Thread({ handleClient(client) }, "http-client").start()
            }
        } catch (_: IOException) {
            // socket closed on stop()
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.tcpNoDelay = true
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val requestLine = reader.readLine() ?: return
            // drain remaining request headers
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val path = requestLine.split(" ").getOrNull(1) ?: "/"
            val out = client.getOutputStream()
            when {
                path.startsWith("/video") -> serveVideo(out)
                path.startsWith("/audio") -> serveAudio(client, out)
                path.startsWith("/info") -> serveJson(out, infoJson())
                path.startsWith("/switch") -> {
                    onSwitchCamera?.invoke()
                    serveJson(out, """{"ok":true}""")
                }
                else -> serveIndex(out)
            }
        } catch (_: IOException) {
            // client disconnected
        } finally {
            try { client.close() } catch (_: IOException) {}
        }
    }

    // CORS header lets the desktop app (and browsers) call these endpoints.
    private fun headers(contentType: String, extra: String = ""): String =
        "HTTP/1.0 200 OK\r\n" +
        "Content-Type: $contentType\r\n" +
        "Access-Control-Allow-Origin: *\r\n" +
        "Cache-Control: no-cache\r\n" +
        extra +
        "Connection: close\r\n\r\n"

    private fun serveJson(out: OutputStream, json: String) {
        val body = json.toByteArray()
        out.write(headers("application/json", "Content-Length: ${body.size}\r\n").toByteArray())
        out.write(body)
        out.flush()
    }

    private fun serveIndex(out: OutputStream) {
        val body = """
            <html><head><title>Cam Mic Streamer</title></head>
            <body style="font-family:sans-serif">
            <h2>Cam Mic Streamer</h2>
            <p>Video stream: <a href="/video">/video</a> (MJPEG)</p>
            <p>Audio stream: <a href="/audio">/audio</a> (WAV, ${sampleRate} Hz mono)</p>
            <p><a href="/switch">Switch front/back camera</a></p>
            <img src="/video" style="max-width:100%"/>
            </body></html>
        """.trimIndent().toByteArray()
        out.write(headers("text/html", "Content-Length: ${body.size}\r\n").toByteArray())
        out.write(body)
        out.flush()
    }

    private fun serveVideo(out: OutputStream) {
        out.write(headers("multipart/x-mixed-replace; boundary=frame").toByteArray())
        var lastSent = -1L
        while (running) {
            var frame: ByteArray?
            synchronized(frameLock) {
                while (running && frameId == lastSent) {
                    frameLock.wait(1000)
                }
                frame = latestFrame
                lastSent = frameId
            }
            val jpeg = frame ?: continue
            out.write(
                ("--frame\r\n" +
                 "Content-Type: image/jpeg\r\n" +
                 "Content-Length: ${jpeg.size}\r\n\r\n").toByteArray()
            )
            out.write(jpeg)
            out.write("\r\n".toByteArray())
            out.flush()
        }
    }

    private fun serveAudio(client: Socket, out: OutputStream) {
        out.write(headers("audio/wav").toByteArray())
        out.write(wavStreamHeader(sampleRate))
        out.flush()
        audioClients.add(out)
        try {
            // Block until the client disconnects; audio is pushed by publishAudio().
            val input = client.getInputStream()
            while (running && input.read() != -1) { /* ignore */ }
        } catch (_: IOException) {
        } finally {
            audioClients.remove(out)
        }
    }

    /** WAV header with "infinite" data length, suitable for live streaming. */
    private fun wavStreamHeader(sampleRate: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val header = ByteArray(44)
        fun putString(offset: Int, s: String) {
            s.toByteArray(Charsets.US_ASCII).copyInto(header, offset)
        }
        fun putIntLE(offset: Int, value: Long) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
            header[offset + 2] = ((value shr 16) and 0xFF).toByte()
            header[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }
        fun putShortLE(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }
        putString(0, "RIFF")
        putIntLE(4, 0xFFFFFFFFL)
        putString(8, "WAVE")
        putString(12, "fmt ")
        putIntLE(16, 16)
        putShortLE(20, 1) // PCM
        putShortLE(22, channels)
        putIntLE(24, sampleRate.toLong())
        putIntLE(28, byteRate.toLong())
        putShortLE(32, blockAlign)
        putShortLE(34, bitsPerSample)
        putString(36, "data")
        putIntLE(40, 0xFFFFFFFFL)
        return header
    }
}
