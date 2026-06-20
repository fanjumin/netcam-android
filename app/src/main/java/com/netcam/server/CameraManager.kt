package com.netcam.server

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.netcam.License
import com.netcam.data.SensorData
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CameraManager(
    private val lifecycleRegistry: LifecycleRegistry,
    private val context: android.content.Context
) {
    companion object {
        private const val TAG = "CameraManager"
        private const val MOTION_BLOCK_SIZE = 32
        private const val MOTION_THRESHOLD = 30
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null
    private var preview: Preview? = null
    private var isRunning = false
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var jpegQuality = 80
    private var mirrorEnabled = false
    private var zoomLevel = 1.0f
    private var resolution = Size(1280, 720)
    private var targetFps = 30

    private val cameraExecutor = Executors.newSingleThreadExecutor()

    var onJpegFrame: ((ByteArray) -> Unit)? = null
    var onH264Frame: ((android.media.Image) -> Unit)? = null

    // Motion detection state
    private var motionDetectionEnabled = false
    private var motionSensitivity = 30
    private var previousFrame: ByteArray? = null
    private var previousFrameWidth = 0
    private var previousFrameHeight = 0
    var onMotionDetected: ((Boolean) -> Unit)? = null
    private var lastMotionState = false

    // Sensor data for overlay
    var currentSensorData: SensorData = SensorData()

    // JPEG capture queue
    private val jpegBuffer = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    private var jpegCaptureActive = false

    fun setResolution(width: Int, height: Int) {
        resolution = Size(width, height)
    }

    fun setFps(fps: Int) {
        targetFps = fps
    }

    fun setJpegQuality(quality: Int) {
        jpegQuality = quality.coerceIn(1, 100)
    }

    fun setMirrorEnabled(enabled: Boolean) {
        mirrorEnabled = enabled
    }

    fun setZoom(zoom: Float) {
        zoomLevel = zoom.coerceIn(1.0f, 8.0f)
        camera?.cameraControl?.setZoomRatio(zoomLevel)
    }

    fun setTorch(on: Boolean) {
        camera?.cameraControl?.enableTorch(on)
    }

    fun setMotionDetection(enabled: Boolean) {
        motionDetectionEnabled = enabled
        if (!enabled) {
            previousFrame = null
            lastMotionState = false
        }
    }

    fun setMotionSensitivity(sensitivity: Int) {
        motionSensitivity = sensitivity.coerceIn(1, 100)
    }

    fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        if (isRunning) {
            stop()
            start()
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCamera()
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                isRunning = false
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        preview = Preview.Builder()
            .setTargetResolution(resolution)
            .setTargetRotation(getDisplayRotation())
            .build()

        val analysisSize = if (resolution.width > 640) Size(640, 480) else resolution

        imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(analysisSize)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(getDisplayRotation())
            .build()

        imageAnalysis?.setAnalyzer(cameraExecutor) { imageProxy ->
            processFrame(imageProxy)
        }

        imageCapture = ImageCapture.Builder()
            .setTargetResolution(resolution)
            .setTargetRotation(getDisplayRotation())
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                lifecycleRegistry as LifecycleOwner,
                cameraSelector,
                preview, imageAnalysis, imageCapture
            )

            if (zoomLevel > 1.0f) {
                camera?.cameraControl?.setZoomRatio(zoomLevel)
            }

            val cameraInfo = camera?.cameraInfo
            if (cameraInfo != null) {
                val isTorchAvailable = Camera2CameraInfo.from(cameraInfo)
                    .getCameraCharacteristic(CameraCharacteristics.FLASH_INFO_AVAILABLE)
                Log.d(TAG, "Torch available: $isTorchAvailable")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Use case bind failed", e)
        }
    }

    private fun processFrame(imageProxy: ImageProxy) {
        if (!isRunning) return

        val image = imageProxy.image
        val isMotionFrame = motionDetectionEnabled && image != null

        // Route to H264 encoder
        if (image != null) {
            onH264Frame?.invoke(image)
        }

        // Capture JPEG for MJPEG stream
        if (jpegCaptureActive) {
            val jpegBytes = imageProxyToJpeg(imageProxy)
            if (jpegBytes != null) {
                onJpegFrame?.invoke(jpegBytes)
            }
        }

        // Motion detection
        if (isMotionFrame) {
            detectMotion(imageProxy)
        }

        imageProxy.close()
    }

    private fun imageProxyToJpeg(imageProxy: ImageProxy): ByteArray? {
        return try {
            val yuvBuffer = imageProxyToNv21(imageProxy) ?: return null
            val yuvImage = YuvImage(yuvBuffer, ImageFormat.NV21,
                imageProxy.width, imageProxy.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height),
                jpegQuality, out)

            var bytes = out.toByteArray()

            // Mirror if needed
            if (mirrorEnabled) {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                val matrix = Matrix().apply { preScale(-1f, 1f) }
                val mirrored = Bitmap.createBitmap(bitmap, 0, 0,
                    bitmap.width, bitmap.height, matrix, true)
                val baos = ByteArrayOutputStream()
                mirrored.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos)
                bytes = baos.toByteArray()
                bitmap.recycle()
                mirrored.recycle()
            }

            // Watermark for free version
            if (!License.IS_PRO) {
                bytes = addWatermark(bytes, jpegQuality)
            }

            bytes
        } catch (e: Exception) {
            Log.e(TAG, "JPEG conversion failed", e)
            null
        }
    }

    private fun addWatermark(bytes: ByteArray, quality: Int): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            color = Color.WHITE
            textSize = bitmap.width * 0.04f
            isAntiAlias = true
            alpha = 180
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
        }
        val text = License.WATERMARK_TEXT
        val x = bitmap.width - paint.measureText(text) - 8f
        val y = bitmap.height - 8f
        canvas.drawText(text, x, y, paint)
        canvas.save()
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        bitmap.recycle()
        return baos.toByteArray()
    }

    private fun imageProxyToNv21(imageProxy: ImageProxy): ByteArray? {
        val image = imageProxy.image ?: return null
        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)

        val uvPixelStride = uPlane.pixelStride
        val uvRowStride = uPlane.rowStride

        if (uvPixelStride == 2 && uvRowStride == imageProxy.width) {
            // Interleaved UV
            val uvBuffer = ByteBuffer.allocate(uSize + vSize)
            uBuffer.get(nv21, ySize, uSize)
            vBuffer.get(nv21, ySize + uSize, vSize)
        } else {
            // Semi-planar, convert VU to NV21
            val uvWidth = imageProxy.width / 2
            val uvHeight = imageProxy.height / 2
            var uvIdx = ySize
            for (row in 0 until uvHeight) {
                val uRowPos = row * uvRowStride
                val vRowPos = row * uvRowStride
                for (col in 0 until uvWidth) {
                    val uPos = uRowPos + col * uvPixelStride
                    val vPos = vRowPos + col * uvPixelStride
                    if (uPos < uSize && vPos < vSize) {
                        nv21[uvIdx] = vBuffer.get(vPos)
                        nv21[uvIdx + 1] = uBuffer.get(uPos)
                        uvIdx += 2
                    }
                }
            }
        }

        return nv21
    }

    private fun detectMotion(imageProxy: ImageProxy) {
        val width = imageProxy.width
        val height = imageProxy.height

        val nv21 = imageProxyToNv21(imageProxy) ?: return
        val yPlaneSize = width * height
        if (nv21.size < yPlaneSize) return

        val yBuffer = nv21.copyOfRange(0, yPlaneSize)

        if (previousFrame != null && previousFrame!!.size == yPlaneSize) {
            val diff = calculateMotionDifference(previousFrame!!, yBuffer, width, height)
            val threshold = max(1, (MOTION_THRESHOLD * (motionSensitivity / 100.0)).toInt())
            val motion = diff > threshold

            if (motion != lastMotionState) {
                lastMotionState = motion
                onMotionDetected?.invoke(motion)
            }
        }

        previousFrame = yBuffer
        previousFrameWidth = width
        previousFrameHeight = height
    }

    private fun calculateMotionDifference(frame1: ByteArray, frame2: ByteArray,
                                          width: Int, height: Int): Int {
        val cols = (width + MOTION_BLOCK_SIZE - 1) / MOTION_BLOCK_SIZE
        val rows = (height + MOTION_BLOCK_SIZE - 1) / MOTION_BLOCK_SIZE
        var changedBlocks = 0
        var totalBlocks = 0

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val startY = row * MOTION_BLOCK_SIZE
                val startX = col * MOTION_BLOCK_SIZE
                var sum1 = 0
                var sum2 = 0
                var count = 0

                for (y in startY until min(startY + MOTION_BLOCK_SIZE, height)) {
                    for (x in startX until min(startX + MOTION_BLOCK_SIZE, width)) {
                        val idx = y * width + x
                        sum1 += frame1[idx].toInt() and 0xFF
                        sum2 += frame2[idx].toInt() and 0xFF
                        count++
                    }
                }

                if (count > 0) {
                    val avg1 = sum1 / count
                    val avg2 = sum2 / count
                    if (abs(avg1 - avg2) > MOTION_THRESHOLD) {
                        changedBlocks++
                    }
                    totalBlocks++
                }
            }
        }

        return if (totalBlocks > 0) (changedBlocks * 100) / totalBlocks else 0
    }

    fun triggerJpegCapture() {
        jpegCaptureActive = true
    }

    fun stopJpegCapture() {
        jpegCaptureActive = false
    }

    fun takeSnapshot(callback: (ByteArray?) -> Unit) {
        val capture = imageCapture ?: run { callback(null); return }

        val options = ImageCapture.OutputFileOptions.Builder(
            java.io.File(context.cacheDir, "snapshot_temp.jpg")
        ).build()

        capture.takePicture(options, ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    try {
                        val file = java.io.File(context.cacheDir, "snapshot_temp.jpg")
                        var bytes = file.readBytes()
                        file.delete()
                        if (!License.IS_PRO) {
                            bytes = addWatermark(bytes, jpegQuality)
                        }
                        callback(bytes)
                    } catch (e: Exception) {
                        callback(null)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Snapshot error", exception)
                    callback(null)
                }
            })
    }

    fun getCameraInfo(): Map<String, Any> {
        val info = mutableMapOf<String, Any>()
        info["resolution"] = "${resolution.width}x${resolution.height}"
        info["fps"] = targetFps
        info["facing"] = if (lensFacing == CameraSelector.LENS_FACING_BACK) "back" else "front"
        info["mirror"] = mirrorEnabled
        info["zoom"] = zoomLevel
        info["motion_detection"] = motionDetectionEnabled
        info["motion_sensitivity"] = motionSensitivity
        info["jpeg_quality"] = jpegQuality
        return info
    }

    fun getPreviewSurface(): Preview? = preview

    private fun getDisplayRotation(): Int {
        val windowManager = context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        return when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    fun stop() {
        isRunning = false
        jpegCaptureActive = false
        previousFrame = null
        lastMotionState = false
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {}
        camera = null
        preview = null
        imageAnalysis = null
        imageCapture = null
    }

    val isActive: Boolean get() = isRunning
}
