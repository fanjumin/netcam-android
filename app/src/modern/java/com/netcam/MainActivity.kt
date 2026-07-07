package com.netcam

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Camera
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.netcam.data.SensorCollector
import com.netcam.data.SensorData
import com.netcam.data.SettingsStore
import com.netcam.server.CameraService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private val RESOLUTIONS = listOf("3840x2160", "1920x1080", "1280x720", "864x480", "640x480", "320x240")
    }

    private var cameraService: CameraService? = null
    private var isBound = false
    private var permissionsGranted = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // Camera managed in Activity scope
    private var previewCamera: Camera? = null
    private var savedHolder: SurfaceHolder? = null
    private var pendingCameraStart = false
    private var currentCameraId = 0 // 0=back, 1=front
    private var previewW = 1280
    private var previewH = 720
    private var currentJpegQuality = 80
    private var currentFps = 30
    private var currentMirror = false
    private var currentFlash = false
    private var currentMotion = false
    private var currentMotionSensitivity = 30
    private var currentZoom = 1.0f

    // Sensor overlay — lazy init to avoid crash before onCreate()
    private val sensorCollector: SensorCollector by lazy { SensorCollector(this) }
    private var sensorData = SensorData()

    // Motion detection state
    private var prevMotionFrame: ByteArray? = null

    private val requiredPermissions = mutableListOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.BODY_SENSORS)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        permissionsGranted = allGranted
        if (allGranted) {
            Log.d(TAG, "Permissions granted, starting server + camera")
            startService(Intent(this, CameraService::class.java).apply {
                action = CameraService.ACTION_START
            })
            savedHolder?.let { startCamera(it) } ?: run { pendingCameraStart = true }
        } else {
            Toast.makeText(this, "需要摄像头和麦克风权限", Toast.LENGTH_LONG).show()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            cameraService = CameraService.getInstance()
            isBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            cameraService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate start")
        sensorCollector.start()
        Log.d(TAG, "sensorCollector started")

        val intent = Intent(this, CameraService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
        Log.d(TAG, "service bind requested")

        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            permissionsGranted = true
            Log.d(TAG, "permissions already granted, starting service")
            startService(Intent(this, CameraService::class.java).apply {
                action = CameraService.ACTION_START
            })
        } else {
            Log.d(TAG, "requesting permissions: $missing")
            permissionLauncher.launch(missing.toTypedArray())
        }

        Log.d(TAG, "setting content view")
        setContent {
            NetCamTheme {
                MainScreen(
                    permissionsGranted = permissionsGranted,
                    onStartServer = {
                        startService(Intent(this@MainActivity, CameraService::class.java).apply {
                            action = CameraService.ACTION_START
                        })
                    },
                    onStopServer = {
                        stopCamera()
                        startService(Intent(this@MainActivity, CameraService::class.java).apply {
                            action = CameraService.ACTION_STOP
                        })
                    },
                    getService = { CameraService.getInstance() },
                    settingsStore = SettingsStore(this),
                    onSurfaceReady = { holder ->
                        savedHolder = holder
                        if (permissionsGranted) startCamera(holder)
                        else if (pendingCameraStart) { pendingCameraStart = false; startCamera(holder) }
                    },
                    onCameraSettingsChanged = { settings ->
                        applyCameraSettings(settings)
                    },
                    sensorData = sensorCollector.sensorData.value
                )
            }
        }
    }

    private fun applyCameraSettings(s: Map<String, Any>) {
        var needsRestart = false

        // Resolution change
        val resStr = s["resolution"] as? String ?: "1280x720"
        if (resStr != "${previewW}x${previewH}") {
            val parts = resStr.split("x")
            if (parts.size == 2) {
                previewW = parts[0].toIntOrNull() ?: 1280
                previewH = parts[1].toIntOrNull() ?: 720
                needsRestart = true
            }
        }

        // Facing (front/back) change
        val newFacing = if ((s["cameraFacing"] as? Int ?: 0) == 0) 0 else 1
        if (newFacing != currentCameraId) {
            currentCameraId = newFacing
            needsRestart = true
        }

        currentJpegQuality = (s["jpegQuality"] as? Int ?: 80).coerceIn(1, 100)
        currentFps = (s["fps"] as? Int ?: 30).coerceIn(1, 30)
        currentMirror = s["mirrorEnabled"] as? Boolean ?: false
        currentFlash = s["flashEnabled"] as? Boolean ?: false
        currentMotion = s["motionDetection"] as? Boolean ?: false
        currentMotionSensitivity = (s["motionSensitivity"] as? Int ?: 30).coerceIn(1, 100)
        currentZoom = (s["zoomLevel"] as? Float ?: 1.0f).coerceIn(1.0f, 8.0f)

        if (needsRestart && permissionsGranted && savedHolder?.surface?.isValid() == true) {
            savedHolder?.let { startCamera(it) }
        } else if (currentFlash) {
            try {
                val params = previewCamera?.parameters
                if (params != null && params.supportedFlashModes != null) {
                    params.flashMode = Camera.Parameters.FLASH_MODE_TORCH
                    previewCamera?.parameters = params
                }
            } catch (_: Exception) {}
        } else {
            try {
                val params = previewCamera?.parameters
                if (params != null && params.supportedFlashModes != null) {
                    params.flashMode = Camera.Parameters.FLASH_MODE_OFF
                    previewCamera?.parameters = params
                }
            } catch (_: Exception) {}
        }

        // Apply zoom via parameters
        try {
            val params = previewCamera?.parameters
            if (params != null && params.isZoomSupported) {
                val maxZoom = params.maxZoom
                val zoomIdx = ((currentZoom - 1.0f) / 7.0f * maxZoom).toInt().coerceIn(0, maxZoom)
                params.zoom = zoomIdx
                previewCamera?.parameters = params
            }
        } catch (_: Exception) {}
    }

    private fun startCamera(holder: SurfaceHolder) {
        stopCamera()
        try {
            val cam = Camera.open(currentCameraId)
            previewCamera = cam
            val params = cam.parameters ?: run { cam.release(); return }

            // Select best preview size
            val sizes = params.supportedPreviewSizes
            if (sizes != null && sizes.isNotEmpty()) {
                var best = sizes[0]
                var bestScore = Int.MAX_VALUE
                for (s in sizes) {
                    val score = kotlin.math.abs(s.width - previewW) + kotlin.math.abs(s.height - previewH)
                    if (score < bestScore) { bestScore = score; best = s }
                }
                previewW = best.width; previewH = best.height
            }
            params.setPreviewSize(previewW, previewH)
            params.setPreviewFormat(ImageFormat.NV21)

            // FPS range
            val fpsRanges = params.supportedPreviewFpsRange
            if (fpsRanges != null && fpsRanges.isNotEmpty()) {
                var bestRange = fpsRanges[0]
                for (r in fpsRanges) {
                    val target = currentFps * 1000
                    if (r[1] >= target && (bestRange[1] < target || r[1] < bestRange[1])) {
                        bestRange = r
                    }
                }
                params.setPreviewFpsRange(bestRange[0], bestRange[1])
            }

            // Flash
            if (currentFlash && params.supportedFlashModes != null) {
                params.flashMode = Camera.Parameters.FLASH_MODE_TORCH
            }

            // Zoom
            if (params.isZoomSupported && currentCameraId == 0) { // back camera only
                val maxZoom = params.maxZoom
                val zoomIdx = ((currentZoom - 1.0f) / 7.0f * maxZoom).toInt().coerceIn(0, maxZoom)
                params.zoom = zoomIdx
            }

            // Facing for front camera: set display orientation
            cam.parameters = params
            cam.setDisplayOrientation(if (currentCameraId == 0) 90 else 270)

            cam.setPreviewDisplay(holder)
            cam.setPreviewCallback { data, _ ->
                try {
                    // Poll remote commands from HTTP API queue
                    val svc = CameraService.getInstance()
                    if (svc != null) {
                        var cmd = svc.commandQueue.poll()
                        while (cmd != null) {
                            mainHandler.post { applyCameraSettings(cmd) }
                            cmd = svc.commandQueue.poll()
                        }
                    }

                    val yuv = YuvImage(data, ImageFormat.NV21, previewW, previewH, null)
                    val out = java.io.ByteArrayOutputStream()
                    yuv.compressToJpeg(Rect(0, 0, previewW, previewH), currentJpegQuality, out)
                    var jpeg = out.toByteArray()

                    // Mirror
                    if (currentMirror) {
                        jpeg = mirrorJpeg(jpeg)
                    }

                    // Motion detection
                    if (currentMotion && currentCameraId == 0) {
                        detectMotion(jpeg)
                    }

                    // Sensor OSD overlay
                    if (License.IS_PRO) {
                        jpeg = overlaySensorData(jpeg)
                    }

                    // Watermark (free version)
                    if (!License.IS_PRO) jpeg = addWatermark(jpeg)

                    CameraService.getInstance()?.broadcastFrame(jpeg)
                } catch (e: Exception) {
                    Log.e(TAG, "Frame: ${e.message}")
                }
            }
            cam.startPreview()
            Log.i(TAG, "Camera1 OK: ${previewW}x${previewH} facing=${currentCameraId}")
        } catch (e: Exception) {
            Log.e(TAG, "Camera1 failed", e)
            try { previewCamera?.release() } catch (_: Exception) {}
            previewCamera = null
        }
    }

    private fun stopCamera() {
        try { previewCamera?.setPreviewCallback(null); previewCamera?.stopPreview() } catch (_: Exception) {}
        try { previewCamera?.release() } catch (_: Exception) {}
        previewCamera = null
    }

    private fun mirrorJpeg(bytes: ByteArray): ByteArray {
        try {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
            val mx = Matrix().apply { preScale(-1f, 1f) }
            val mirrored = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, mx, true)
            val out = java.io.ByteArrayOutputStream()
            mirrored.compress(Bitmap.CompressFormat.JPEG, currentJpegQuality, out)
            bmp.recycle(); mirrored.recycle()
            return out.toByteArray()
        } catch (_: Exception) { return bytes }
    }

    private fun detectMotion(jpeg: ByteArray) {
        try {
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
            val grey = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ALPHA_8)
            val canvas = Canvas(grey)
            val paint = Paint()
            canvas.drawBitmap(bmp, 0f, 0f, paint)
            bmp.recycle()

            val w = grey.width; val h = grey.height
            val pixels = IntArray(w * h)
            grey.getPixels(pixels, 0, w, 0, 0, w, h)
            grey.recycle()

            // Simple frame diff: compare downscaled grid
            val gridW = 32; val gridH = 24
            val cellW = w / gridW; val cellH = h / gridH
            var diffCount = 0
            val currentFrame = ByteArray(gridW * gridH)

            for (y in 0 until gridH) {
                for (x in 0 until gridW) {
                    var sum = 0
                    for (dy in 0 until cellH) {
                        for (dx in 0 until cellW) {
                            val idx = (y * cellH + dy) * w + (x * cellW + dx)
                            if (idx < pixels.size) {
                                sum += pixels[idx] and 0xFF
                            }
                        }
                    }
                    currentFrame[y * gridW + x] = (sum / (cellW * cellH)).toByte()
                }
            }

            if (prevMotionFrame != null) {
                for (i in currentFrame.indices) {
                    val diff = kotlin.math.abs((currentFrame[i].toInt() and 0xFF) - (prevMotionFrame!![i].toInt() and 0xFF))
                    if (diff > currentMotionSensitivity) diffCount++
                }
            }
            prevMotionFrame = currentFrame

            val threshold = (gridW * gridH * 5) / 100 // 5% cells changed
            cameraService?.setMotionDetected(diffCount > threshold)
        } catch (_: Exception) {}
    }

    private fun overlaySensorData(jpeg: ByteArray): ByteArray {
        try {
            val sd = sensorCollector.sensorData.value
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
            val canvas = Canvas(bmp)
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

            // Top-left: timestamp + battery
            val tsPaint = Paint().apply {
                color = android.graphics.Color.WHITE; textSize = bmp.width * 0.035f
                isAntiAlias = true; alpha = 200
                setShadowLayer(2f, 1f, 1f, android.graphics.Color.BLACK)
            }
            val ts = "${dateFormat.format(Date(sd.timestamp))}  BAT:${(sd.batteryLevel * 100).toInt()}%${if (sd.isCharging) "⚡" else ""}"
            canvas.drawText(ts, 8f, tsPaint.textSize + 4f, tsPaint)

            // Top-right: sensors
            if (sd.light > 0 || sd.temperature > 0) {
                val sensorPaint = Paint().apply {
                    color = android.graphics.Color.argb(200, 100, 255, 100)
                    textSize = bmp.width * 0.03f; isAntiAlias = true
                    setShadowLayer(2f, 1f, 1f, android.graphics.Color.BLACK)
                }
                val sensorInfo = buildString {
                    if (sd.light > 0) append("LX:${sd.light.toInt()} ")
                    if (sd.temperature > 0) append("${String.format("%.1f", sd.temperature)}°C ")
                    if (sd.pressure > 0) append("${sd.pressure.toInt()}hPa ")
                    if (sd.humidity > 0) append("${sd.humidity.toInt()}%")
                }
                if (sensorInfo.isNotBlank()) {
                    canvas.drawText(sensorInfo.trimEnd(),
                        bmp.width - 8f - Paint().apply { textSize = bmp.width * 0.03f }.measureText(sensorInfo.trimEnd()),
                        tsPaint.textSize + 4f, sensorPaint)
                }
            }

            // Bottom-left: resolution + fps
            val infoPaint = Paint().apply {
                color = android.graphics.Color.argb(180, 200, 200, 200)
                textSize = bmp.width * 0.03f; isAntiAlias = true
                setShadowLayer(2f, 1f, 1f, android.graphics.Color.BLACK)
            }
            val info = "${previewW}x${previewH} @${currentFps}fps"
            canvas.drawText(info, 8f, bmp.height - 8f, infoPaint)

            val out = java.io.ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, currentJpegQuality, out)
            bmp.recycle()
            return out.toByteArray()
        } catch (_: Exception) { return jpeg }
    }

    private fun addWatermark(bytes: ByteArray): ByteArray {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        val w = bmp.width; val h = bmp.height
        val mutable = bmp.copy(Bitmap.Config.ARGB_8888, true)
        bmp.recycle()
        val canvas = Canvas(mutable)
        val paint = Paint().apply {
            color = android.graphics.Color.WHITE; textSize = bmp.width * 0.04f
            isAntiAlias = true; alpha = 180
            setShadowLayer(2f, 1f, 1f, android.graphics.Color.BLACK)
        }
        canvas.drawText(License.WATERMARK_TEXT,
            w - paint.measureText(License.WATERMARK_TEXT) - 8f, h - 8f, paint)
        val out = java.io.ByteArrayOutputStream()
        mutable.compress(Bitmap.CompressFormat.JPEG, currentJpegQuality, out)
        mutable.recycle()
        return out.toByteArray()
    }

    override fun onDestroy() {
        stopCamera()
        sensorCollector.stop()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        super.onDestroy()
    }
}

// ── Theme ──
@Composable
fun NetCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF1A73E8), secondary = Color(0xFF34A853),
            background = Color(0xFF0a0a0a), surface = Color(0xFF1a1a2e),
            onPrimary = Color.White, onSecondary = Color.White,
            onBackground = Color(0xFFe0e0e0), onSurface = Color(0xFFe0e0e0)
        ), content = content
    )
}

// ── Main Screen ──
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    permissionsGranted: Boolean = false,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    getService: () -> CameraService?,
    settingsStore: SettingsStore,
    onSurfaceReady: (SurfaceHolder) -> Unit = {},
    onCameraSettingsChanged: (Map<String, Any>) -> Unit = {},
    sensorData: SensorData = SensorData()
) {
    val scope = rememberCoroutineScope()
    var isRunning by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("Server Stopped") }
    var showSettings by remember { mutableStateOf(false) }
    var resolution by remember { mutableStateOf("1280x720") }
    var jpegQuality by remember { mutableIntStateOf(80) }
    var fps by remember { mutableIntStateOf(30) }
    var cameraFacing by remember { mutableIntStateOf(0) }
    var mirrorEnabled by remember { mutableStateOf(false) }
    var motionEnabled by remember { mutableStateOf(false) }
    var motionSensitivity by remember { mutableIntStateOf(30) }
    var zoomLevel by remember { mutableFloatStateOf(1.0f) }
    var serverPort by remember { mutableIntStateOf(8080) }
    var autoStart by remember { mutableStateOf(false) }
    var platformUrl by remember { mutableStateOf("") }
    var licenseKey by remember { mutableStateOf("") }
    var licenseStatus by remember { mutableStateOf("") }
    var flashEnabled by remember { mutableStateOf(false) }
    var rtspPort by remember { mutableIntStateOf(8554) }
    var serverIp by remember { mutableStateOf("") }

    // Apply settings to camera when they change
    fun applyAllSettings() {
        onCameraSettingsChanged(mapOf(
            "resolution" to resolution,
            "jpegQuality" to jpegQuality,
            "fps" to fps,
            "cameraFacing" to cameraFacing,
            "mirrorEnabled" to mirrorEnabled,
            "flashEnabled" to flashEnabled,
            "motionDetection" to motionEnabled,
            "motionSensitivity" to motionSensitivity,
            "zoomLevel" to zoomLevel
        ))
    }

    // Load settings
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            resolution = settingsStore.getVideoResolution()
            jpegQuality = settingsStore.getJpegQuality()
            fps = settingsStore.getVideoFps()
            cameraFacing = settingsStore.getCameraFacing()
            mirrorEnabled = settingsStore.getMirrorEnabled()
            motionEnabled = settingsStore.getMotionDetection()
            motionSensitivity = settingsStore.getMotionSensitivity()
            zoomLevel = settingsStore.getZoomLevel()
            serverPort = settingsStore.getServerPort()
            autoStart = settingsStore.getAutoStart()
            platformUrl = settingsStore.getPlatformUrl()
            licenseKey = settingsStore.getLicenseKey()
            flashEnabled = settingsStore.getFlashEnabled()
        }
        // Reconcile saved settings with current camera state
        applyAllSettings()
    }

    // Server status poll
    LaunchedEffect(Unit) {
        while (true) {
            val service = getService()
            if (service != null) {
                val running = service.isRunning()
                isRunning = running
                statusText = if (running) "Server Running" else "Server Stopped"
                if (running) {
                    val ipAddr = getLocalIpAddress()
                    serverUrl = "http://$ipAddr:$serverPort"
                    serverIp = ipAddr
                    rtspPort = 8554
                } else {
                    serverUrl = ""
                }
            }
            delay(1000)
        }
    }

    if (showSettings) {
        SettingsScreen(
            resolution = resolution, onResolutionChange = { v ->
                resolution = v; scope.launch(Dispatchers.IO) { settingsStore.setVideoResolution(v) }; applyAllSettings()
            },
            resolutions = listOf("3840x2160", "1920x1080", "1280x720", "864x480", "640x480", "320x240"),
            jpegQuality = jpegQuality, onJpegQualityChange = { v ->
                jpegQuality = v; scope.launch(Dispatchers.IO) { settingsStore.setJpegQuality(v) }; applyAllSettings()
            },
            fps = fps, onFpsChange = { v ->
                fps = v; scope.launch(Dispatchers.IO) { settingsStore.setVideoFps(v) }; applyAllSettings()
            },
            mirrorEnabled = mirrorEnabled, onMirrorChange = { v ->
                mirrorEnabled = v; scope.launch(Dispatchers.IO) { settingsStore.setMirrorEnabled(v) }; applyAllSettings()
            },
            flashEnabled = flashEnabled, onFlashChange = { v ->
                flashEnabled = v; scope.launch(Dispatchers.IO) { settingsStore.setFlashEnabled(v) }; applyAllSettings()
            },
            cameraFacing = cameraFacing, onCameraFacingChange = { v ->
                cameraFacing = v; scope.launch(Dispatchers.IO) { settingsStore.setCameraFacing(v) }; applyAllSettings()
            },
            motionEnabled = motionEnabled, onMotionChange = { v ->
                motionEnabled = v; scope.launch(Dispatchers.IO) { settingsStore.setMotionDetection(v) }; applyAllSettings()
            },
            motionSensitivity = motionSensitivity, onSensitivityChange = { v ->
                motionSensitivity = v; scope.launch(Dispatchers.IO) { settingsStore.setMotionSensitivity(v) }; applyAllSettings()
            },
            zoomLevel = zoomLevel, onZoomChange = { v ->
                zoomLevel = v; scope.launch(Dispatchers.IO) { settingsStore.setZoomLevel(v) }; applyAllSettings()
            },
            serverPort = serverPort, onPortChange = { v ->
                serverPort = v; scope.launch(Dispatchers.IO) { settingsStore.setServerPort(v) }
            },
            autoStart = autoStart, onAutoStartChange = { v ->
                autoStart = v; scope.launch(Dispatchers.IO) { settingsStore.setAutoStart(v) }
            },
            platformUrl = platformUrl, onPlatformUrlChange = { platformUrl = it },
            licenseKey = licenseKey, onLicenseKeyChange = { licenseKey = it },
            licenseStatus = licenseStatus,
            onVerifyLicense = {
                scope.launch(Dispatchers.IO) {
                    settingsStore.setPlatformUrl(platformUrl)
                    settingsStore.setLicenseKey(licenseKey)
                    val result = License.checkLicense(platformUrl, licenseKey)
                    licenseStatus = License.LAST_CHECK_RESULT
                }
            },
            onBack = { showSettings = false }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NetCam Pro", fontWeight = FontWeight.Bold) },
                actions = {
                    TextButton(onClick = { showSettings = true }) { Text("设置") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CameraPreviewSection(onSurfaceReady = onSurfaceReady)
            Spacer(modifier = Modifier.height(12.dp))
            StatusCard(statusText, serverUrl, isRunning)
            Spacer(modifier = Modifier.height(8.dp))

            // Quick stats row
            if (isRunning) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1a1a2e))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${resolution.split("x")[0]}×${resolution.split("x").getOrElse(1){"720"}}", fontSize = 13.sp,
                                color = Color(0xFF34A853), fontFamily = FontFamily.Monospace)
                            Text("分辨率", fontSize = 10.sp, color = Color.Gray)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${fps}fps", fontSize = 13.sp,
                                color = Color(0xFF34A853), fontFamily = FontFamily.Monospace)
                            Text("帧率", fontSize = 10.sp, color = Color.Gray)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (cameraFacing == 0) "后置" else "前置", fontSize = 13.sp,
                                color = Color(0xFF34A853))
                            Text("摄像头", fontSize = 10.sp, color = Color.Gray)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val bat = (sensorData.batteryLevel * 100).toInt()
                            Text("${bat}%${if (sensorData.isCharging) "⚡" else ""}", fontSize = 13.sp,
                                color = if (bat < 20) Color(0xFFEA4335) else Color(0xFF34A853),
                                fontFamily = FontFamily.Monospace)
                            Text("电量", fontSize = 10.sp, color = Color.Gray)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(20.dp))
            StartStopButton(isRunning, onStartServer, onStopServer)
            Spacer(modifier = Modifier.height(12.dp))

            // Connection info
            if (isRunning) {
                Text("浏览器访问地址", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp))
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1a1a2e)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("HTTP ($serverPort):", fontSize = 11.sp, color = Color.Gray)
                        Text(serverUrl, fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF34A853))
                        Spacer(Modifier.height(4.dp))
                        Text("RTSP ($rtspPort):", fontSize = 11.sp, color = Color.Gray)
                        Text("rtsp://$serverIp:$rtspPort/live", fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFFBBC04))
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            Text("NetCam Pro v0.1.0", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp))
        }
    }
}

// ── Camera Preview (SurfaceView for Camera1 API) ──
@Composable
fun CameraPreviewSection(onSurfaceReady: (SurfaceHolder) -> Unit = {}) {
    Box(
        modifier = Modifier.fillMaxWidth().height(220.dp).background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            onSurfaceReady(holder)
                        }
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                        override fun surfaceDestroyed(h: SurfaceHolder) {}
                    })
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

// ── Status Card ──
@Composable
fun StatusCard(statusText: String, serverUrl: String, isRunning: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(12.dp).background(
                    if (isRunning) Color(0xFF34A853) else Color(0xFFEA4335),
                    shape = RoundedCornerShape(6.dp)))
                Spacer(Modifier.width(8.dp))
                Text(statusText, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

// ── Start/Stop Button ──
@Composable
fun StartStopButton(isRunning: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Button(
        onClick = if (isRunning) onStop else onStart,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp).height(48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isRunning) Color(0xFFEA4335) else Color(0xFF34A853)),
        shape = RoundedCornerShape(24.dp)
    ) { Text(if (isRunning) "Stop Server" else "Start Server", fontWeight = FontWeight.Bold) }
}

// ── Settings Screen ──
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    resolution: String, onResolutionChange: (String) -> Unit,
    resolutions: List<String> = listOf("3840x2160", "1920x1080", "1280x720", "864x480", "640x480", "320x240"),
    jpegQuality: Int, onJpegQualityChange: (Int) -> Unit,
    fps: Int, onFpsChange: (Int) -> Unit,
    mirrorEnabled: Boolean, onMirrorChange: (Boolean) -> Unit,
    flashEnabled: Boolean, onFlashChange: (Boolean) -> Unit,
    cameraFacing: Int, onCameraFacingChange: (Int) -> Unit,
    motionEnabled: Boolean, onMotionChange: (Boolean) -> Unit,
    motionSensitivity: Int, onSensitivityChange: (Int) -> Unit,
    zoomLevel: Float, onZoomChange: (Float) -> Unit,
    serverPort: Int, onPortChange: (Int) -> Unit,
    autoStart: Boolean, onAutoStartChange: (Boolean) -> Unit,
    platformUrl: String, onPlatformUrlChange: (String) -> Unit,
    licenseKey: String, onLicenseKeyChange: (String) -> Unit,
    licenseStatus: String,
    onVerifyLicense: () -> Unit,
    onBack: () -> Unit
) {
    var showResPicker by remember { mutableStateOf(false) }
    var resolutionExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = { TextButton(onClick = onBack) { Text("← 返回") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {

            // ── Video Settings ──
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp)) {
                    Text("视频设置", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(12.dp))

                    // Resolution dropdown
                    Text("分辨率", fontSize = 13.sp, color = Color.Gray)
                    ExposedDropdownMenuBox(
                        expanded = resolutionExpanded,
                        onExpandedChange = { resolutionExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = resolution,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = resolutionExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                        )
                        ExposedDropdownMenu(
                            expanded = resolutionExpanded,
                            onDismissRequest = { resolutionExpanded = false }
                        ) {
                            resolutions.forEach { res ->
                                DropdownMenuItem(
                                    text = { Text(res) },
                                    onClick = { onResolutionChange(res); resolutionExpanded = false }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    // JPEG Quality
                    Text("JPEG 画质: $jpegQuality", fontSize = 13.sp, color = Color.Gray)
                    Slider(
                        value = jpegQuality.toFloat(),
                        onValueChange = { onJpegQualityChange(it.toInt()) },
                        valueRange = 10f..100f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))

                    // FPS
                    Text("帧率: $fps fps", fontSize = 13.sp, color = Color.Gray)
                    Slider(
                        value = fps.toFloat(),
                        onValueChange = { onFpsChange(it.toInt()) },
                        valueRange = 5f..30f,
                        steps = 24,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))

                    // Camera Facing
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("摄像头", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.weight(1f))
                        FilterChip(
                            selected = cameraFacing == 0,
                            onClick = { if (cameraFacing != 0) onCameraFacingChange(0) },
                            label = { Text("后置", fontSize = 12.sp) }
                        )
                        Spacer(Modifier.width(8.dp))
                        FilterChip(
                            selected = cameraFacing == 1,
                            onClick = { if (cameraFacing != 1) onCameraFacingChange(1) },
                            label = { Text("前置", fontSize = 12.sp) }
                        )
                    }
                    Spacer(Modifier.height(8.dp))

                    // Flash toggle
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("闪光灯", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.weight(1f))
                        Switch(checked = flashEnabled, onCheckedChange = onFlashChange)
                    }

                    // Mirror toggle
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("镜像翻转", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.weight(1f))
                        Switch(checked = mirrorEnabled, onCheckedChange = onMirrorChange)
                    }

                    // Zoom
                    Text("数码变焦: ${String.format("%.1f", zoomLevel)}x", fontSize = 13.sp, color = Color.Gray)
                    Slider(
                        value = zoomLevel,
                        onValueChange = onZoomChange,
                        valueRange = 1.0f..8.0f,
                        steps = 13,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))

                    // Server port
                    Text("服务器端口", fontSize = 13.sp, color = Color.Gray)
                    OutlinedTextField(
                        value = serverPort.toString(),
                        onValueChange = { it.toIntOrNull()?.let(onPortChange) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ── Motion Detection ──
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("运动检测", fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Switch(checked = motionEnabled, onCheckedChange = onMotionChange)
                    }
                    if (motionEnabled) {
                        Spacer(Modifier.height(8.dp))
                        Text("灵敏度: $motionSensitivity", fontSize = 13.sp, color = Color.Gray)
                        Slider(
                            value = motionSensitivity.toFloat(),
                            onValueChange = { onSensitivityChange(it.toInt()) },
                            valueRange = 1f..100f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // ── Auto Start ──
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("自启动", fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Switch(checked = autoStart, onCheckedChange = onAutoStartChange)
                    }
                    if (autoStart) {
                        Spacer(Modifier.height(4.dp))
                        Text("开机自动启动摄像头服务器", fontSize = 11.sp, color = Color.Gray)
                    }
                }
            }

            // ── License Section ──
            Card(modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (License.IS_PRO) Color(0xFF34A853) else Color(0xFF1A73E8))) {
                Column(Modifier.padding(16.dp)) {
                    Text(if (License.IS_PRO) "✅ 专业版（已激活）" else "授权验证",
                        fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    if (!License.IS_PRO) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = platformUrl, onValueChange = onPlatformUrlChange,
                            label = { Text("平台地址") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = licenseKey, onValueChange = onLicenseKeyChange,
                            label = { Text("授权码") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onVerifyLicense, modifier = Modifier.fillMaxWidth()) {
                            Text("验证")
                        }
                        if (licenseStatus.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(licenseStatus, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

// ── Utility ──
fun getLocalIpAddress(): String {
    try {
        NetworkInterface.getNetworkInterfaces().toList().forEach { ni ->
            ni.inetAddresses.toList().forEach { addr ->
                if (!addr.isLoopbackAddress && addr is Inet4Address)
                    return addr.hostAddress ?: "unknown"
            }
        }
    } catch (_: Exception) {}
    return "unknown"
}
