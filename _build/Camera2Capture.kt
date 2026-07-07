package com.netcam.server

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import com.netcam.License
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Camera2-based frame capture — works on ALL Android 5+ devices.
 * Uses Camera2 API directly with ImageReader for frame-by-frame capture.
 */
class Camera2Capture(private val context: android.content.Context) {

    companion object {
        private const val TAG = "Camera2Capture"
    }

    private val isRunning = AtomicBoolean(false)
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var previewSize = Size(1280, 720)
    private var jpegQuality = 80
    private var facing = CameraCharacteristics.LENS_FACING_BACK
    private var mirrorEnabled = false

    var onJpegFrame: ((ByteArray) -> Unit)? = null

    fun setResolution(width: Int, height: Int) { previewSize = Size(width, height) }
    fun setJpegQuality(quality: Int) { jpegQuality = quality.coerceIn(1, 100) }
    fun setMirrorEnabled(enabled: Boolean) { mirrorEnabled = enabled }

    val isActive: Boolean get() = isRunning.get()

    fun start() {
        if (isRunning.getAndSet(true)) return
        startBackgroundThread()

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = findCameraId(manager)
            if (cameraId == null) {
                Log.e(TAG, "No camera found")
                isRunning.set(false)
                return
            }

            // Choose best size
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: run {
                Log.e(TAG, "No stream config map")
                isRunning.set(false)
                return
            }
            val outputSizes = map.getOutputSizes(ImageFormat.YUV_420_888)
            if (outputSizes != null && outputSizes.isNotEmpty()) {
                var best = outputSizes[0]
                var bestScore = Int.MAX_VALUE
                for (s in outputSizes) {
                    val score = abs(s.width - previewSize.width) + abs(s.height - previewSize.height)
                    if (score < bestScore) { bestScore = score; best = s }
                }
                previewSize = best
            }

            // Create ImageReader for YUV frames
            imageReader = ImageReader.newInstance(previewSize.width, previewSize.height,
                ImageFormat.YUV_420_888, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                val img = reader.acquireLatestImage()
                if (img != null) {
                    processImage(img)
                    img.close()
                }
            }, backgroundHandler)

            // Open camera
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession(camera)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected")
                    camera.close()
                    cameraDevice = null
                    isRunning.set(false)
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close()
                    cameraDevice = null
                    isRunning.set(false)
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Camera2 start failed", e)
            isRunning.set(false)
        }
    }

    private fun createCaptureSession(camera: CameraDevice) {
        try {
            val reader = imageReader ?: return
            val surface = reader.surface
            val previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder.addTarget(surface)
            previewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

            camera.createCaptureSession(listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler)
                            Log.i(TAG, "Camera2 capture started: ${previewSize.width}x${previewSize.height}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Start repeating failed", e)
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Session configure failed")
                        isRunning.set(false)
                    }
                }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Create session failed", e)
        }
    }

    private fun processImage(img: Image) {
        try {
            val planes = img.planes
            if (planes.size < 3) return

            val w = img.width
            val h = img.height
            val nv21 = yuv420ToNv21(planes, w, h) ?: return

            val yuv = YuvImage(nv21, ImageFormat.NV21, w, h, null)
            val out = ByteArrayOutputStream()
            yuv.compressToJpeg(Rect(0, 0, w, h), jpegQuality, out)
            var jpeg = out.toByteArray()
            if (!License.IS_PRO) jpeg = addWatermark(jpeg)
            onJpegFrame?.invoke(jpeg)
        } catch (e: Exception) {
            Log.e(TAG, "Process error: ${e.message}")
        }
    }

    private fun yuv420ToNv21(planes: Array<Image.Plane>, w: Int, h: Int): ByteArray? {
        try {
            val data = ByteArray(w * h * 3 / 2)
            val rowData = ByteArray(planes[0].rowStride)
            var channelOffset = 0; var outputStride = 1
            for (i in planes.indices) {
                when (i) {
                    0 -> { channelOffset = 0; outputStride = 1 }
                    1 -> { channelOffset = w * h + 1; outputStride = 2 }
                    2 -> { channelOffset = w * h; outputStride = 2 }
                }
                val buffer = planes[i].buffer
                val rowStride = planes[i].rowStride
                val pixelStride = planes[i].pixelStride
                val shift = if (i == 0) 0 else 1
                val pw = w shr shift; val ph = h shr shift
                for (row in 0 until ph) {
                    if (pixelStride == 1 && outputStride == 1) {
                        val len = pw; buffer.get(data, channelOffset, len); channelOffset += len
                    } else {
                        val len = (pw - 1) * pixelStride + 1
                        buffer.get(rowData, 0, len)
                        for (col in 0 until pw) { data[channelOffset] = rowData[col * pixelStride]; channelOffset += outputStride }
                    }
                    if (row < ph - 1) buffer.position(buffer.position() + rowStride - (if (pixelStride == 1 && outputStride == 1) pw else (pw - 1) * pixelStride + 1))
                }
            }
            return data
        } catch (e: Exception) { Log.e(TAG, "YUV→NV21 error: ${e.message}"); return null }
    }

    private fun addWatermark(bytes: ByteArray): ByteArray {
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = bmp.width * 0.04f
            isAntiAlias = true; alpha = 180
            setShadowLayer(2f, 1f, 1f, android.graphics.Color.BLACK)
        }
        canvas.drawText(License.WATERMARK_TEXT,
            bmp.width - paint.measureText(License.WATERMARK_TEXT) - 8f, bmp.height - 8f, paint)
        val out = ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, jpegQuality, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun findCameraId(manager: CameraManager): String? {
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val f = chars.get(CameraCharacteristics.LENS_FACING)
            if (f != null && f == facing) return id
        }
        return manager.cameraIdList.firstOrNull()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("Camera2Background").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try { backgroundThread?.join() } catch (_: InterruptedException) {}
        backgroundThread = null; backgroundHandler = null
    }

    fun stop() {
        isRunning.set(false)
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        stopBackgroundThread()
    }
}
