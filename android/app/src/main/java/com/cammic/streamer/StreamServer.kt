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
 *   /video   - MJPEG stream (multipart/x-mixed-replace)
 *   /audio   - endless WAV stream (16-bit PCM mono)
 */
class StreamServer(private val port: Int, val sampleRate: Int) {

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
                else -> serveIndex(out)
            }
        } catch (_: IOException) {
            // client disconnected
        } finally {
            try { client.close() } catch (_: IOException) {}
        }
    }

    private fun serveIndex(out: OutputStream) {
        val body = """
            <html><head><title>Cam Mic Streamer</title></head>
            <body style="font-family:sans-serif">
            <h2>Cam Mic Streamer</h2>
            <p>Video stream: <a href="/video">/video</a> (MJPEG)</p>
            <p>Audio stream: <a href="/audio">/audio</a> (WAV, ${sampleRate} Hz mono)</p>
            <img src="/video" style="max-width:100%"/>
            </body></html>
        """.trimIndent().toByteArray()
        out.write(
            ("HTTP/1.0 200 OK\r\n" +
             "Content-Type: text/html\r\n" +
             "Content-Length: ${body.size}\r\n" +
             "Connection: close\r\n\r\n").toByteArray()
        )
        out.write(body)
        out.flush()
    }

    private fun serveVideo(out: OutputStream) {
        out.write(
            ("HTTP/1.0 200 OK\r\n" +
             "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
             "Cache-Control: no-cache\r\n" +
             "Connection: close\r\n\r\n").toByteArray()
        )
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
        out.write(
            ("HTTP/1.0 200 OK\r\n" +
             "Content-Type: audio/wav\r\n" +
             "Cache-Control: no-cache\r\n" +
             "Connection: close\r\n\r\n").toByteArray()
        )
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
