package com.cammic.streamer

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PORT = 8080
        private const val BEACON_PORT = 8888
        private const val SAMPLE_RATE = 44100
        private const val JPEG_QUALITY = 60
        private const val PERMISSION_REQUEST = 10
        private val REQUIRED_PERMISSIONS =
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var cameraExecutor: ExecutorService

    private val server = StreamServer(PORT, SAMPLE_RATE, Build.MODEL) { switchCamera() }
    private var cameraProvider: ProcessCameraProvider? = null
    @Volatile private var useFrontCamera = false
    @Volatile private var audioRunning = false
    @Volatile private var beaconRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (hasAllPermissions()) {
            startEverything()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) {
            if (hasAllPermissions()) {
                startEverything()
            } else {
                Toast.makeText(this, R.string.permissions_needed, Toast.LENGTH_LONG).show()
                statusText.setText(R.string.permissions_needed)
            }
        }
    }

    private fun hasAllPermissions() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startEverything() {
        server.start()
        startCamera()
        startAudio()
        startBeacon()
        updateStatus()
    }

    /** Broadcasts a small UDP beacon so the desktop app can auto-detect this phone. */
    private fun startBeacon() {
        if (beaconRunning) return
        beaconRunning = true
        Thread({
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.broadcast = true
                val payload = server.infoJson().toByteArray()
                while (beaconRunning) {
                    try {
                        socket.send(
                            DatagramPacket(
                                payload, payload.size,
                                InetAddress.getByName("255.255.255.255"), BEACON_PORT
                            )
                        )
                        // Also send to each interface's broadcast address (some
                        // routers drop the global broadcast).
                        for (iface in NetworkInterface.getNetworkInterfaces()) {
                            if (!iface.isUp || iface.isLoopback) continue
                            for (ia in iface.interfaceAddresses) {
                                val bcast = ia.broadcast ?: continue
                                socket.send(DatagramPacket(payload, payload.size, bcast, BEACON_PORT))
                            }
                        }
                    } catch (_: Exception) {}
                    Thread.sleep(2000)
                }
            } catch (_: Exception) {
            } finally {
                socket?.close()
            }
        }, "beacon").start()
    }

    fun switchCamera() {
        runOnUiThread {
            useFrontCamera = !useFrontCamera
            bindCamera()
        }
    }

    private fun updateStatus() {
        val ip = localIpAddress()
        statusText.text = if (ip != null) {
            "Server running\nAddress:  http://$ip:$PORT\nVideo:    http://$ip:$PORT/video\nAudio:    http://$ip:$PORT/audio"
        } else {
            getString(R.string.waiting_for_network)
        }
        // Refresh periodically in case Wi-Fi connects later.
        statusText.postDelayed({ updateStatus() }, 5000)
    }

    private fun localIpAddress(): String? {
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(cameraExecutor) { image ->
            try {
                server.publishFrame(imageToJpeg(image, image.imageInfo.rotationDegrees))
            } catch (_: Exception) {
            } finally {
                image.close()
            }
        }

        val selector = if (useFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, preview, analysis)
        } catch (_: Exception) {
            // e.g. device has no front camera - fall back to back camera
            useFrontCamera = false
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) {}
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudio() {
        if (audioRunning) return
        audioRunning = true
        Thread({
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, SAMPLE_RATE / 2)
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                audioRunning = false
                return@Thread
            }
            val buffer = ByteArray(4096)
            record.startRecording()
            try {
                while (audioRunning) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n > 0) server.publishAudio(buffer, n)
                }
            } finally {
                record.stop()
                record.release()
            }
        }, "audio-capture").start()
    }

    private fun imageToJpeg(image: ImageProxy, rotationDegrees: Int): ByteArray {
        val nv21 = yuv420888ToNv21(image)
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, image.width, image.height), JPEG_QUALITY, out)
        var jpeg = out.toByteArray()
        if (rotationDegrees != 0) {
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            val rotatedOut = ByteArrayOutputStream()
            rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, rotatedOut)
            jpeg = rotatedOut.toByteArray()
            if (rotated != bmp) rotated.recycle()
            bmp.recycle()
        }
        return jpeg
    }

    private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val nv21 = ByteArray(ySize + ySize / 2)

        // Y plane
        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        var pos = 0
        if (yPlane.rowStride == width) {
            yBuffer.get(nv21, 0, ySize)
            pos = ySize
        } else {
            for (row in 0 until height) {
                yBuffer.position(row * yPlane.rowStride)
                yBuffer.get(nv21, pos, width)
                pos += width
            }
        }

        // Interleave V and U (NV21 = Y + VU)
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                val vIndex = row * vPlane.rowStride + col * vPlane.pixelStride
                val uIndex = row * uPlane.rowStride + col * uPlane.pixelStride
                nv21[pos++] = vBuffer.get(vIndex)
                nv21[pos++] = uBuffer.get(uIndex)
            }
        }
        return nv21
    }

    override fun onDestroy() {
        super.onDestroy()
        audioRunning = false
        beaconRunning = false
        server.stop()
        cameraExecutor.shutdown()
    }
}
