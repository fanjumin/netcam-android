package com.netcam.server

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageProxy
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.netcam.MainActivity
import com.netcam.NetCamApp
import com.netcam.data.SensorCollector
import com.netcam.data.SensorData
import com.netcam.data.SettingsStore
import com.netcam.media.AudioCapture
import com.netcam.media.H264Encoder
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

class CameraService : Service(), LifecycleOwner {

    companion object {
        private const val TAG = "CameraService"
        const val ACTION_START = "com.netcam.action.START"
        const val ACTION_STOP = "com.netcam.action.STOP"
        const val ACTION_RESTART = "com.netcam.action.RESTART"

        private var instance: CameraService? = null

        fun getInstance(): CameraService? = instance
    }

    private lateinit var settingsStore: SettingsStore
    private lateinit var sensorCollector: SensorCollector
    private var cameraManager: CameraManager? = null
    private var httpServer: HttpServer? = null
    private var rtspServer: RtspServer? = null
    private var h264Encoder: H264Encoder? = null
    private var audioCapture: AudioCapture? = null

    private val lifecycleRegistry = LifecycleRegistry(this)
    private var isServerRunning = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var sensorPollJob: Job? = null

    // Latest JPEG frame for snapshot/MJPEG
    private var latestJpegFrame: ByteArray? = null
    private val jpegFrameLock = Any()

    // SPS/PPS for RTSP
    private var spsData: ByteArray? = null
    private var ppsData: ByteArray? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        settingsStore = SettingsStore(this)
        sensorCollector = SensorCollector(this)
        Log.i(TAG, "CameraService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startServer()
            ACTION_STOP -> stopServer()
            ACTION_RESTART -> restartServer()
        }
        return START_STICKY
    }

    fun startServer() {
        if (isServerRunning) return

        val notification = createNotification()
        startForeground(NetCamApp.NOTIFICATION_ID, notification)

        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        // Start sensor collection
        sensorCollector.start()

        // Initialize camera manager
        val cm = CameraManager(lifecycleRegistry, this)
        cameraManager = cm

        // Configure from settings
        CoroutineScope(Dispatchers.IO).launch {
            val res = settingsStore.getVideoResolution()
            val parts = res.split("x")
            if (parts.size == 2) {
                cm.setResolution(parts[0].toIntOrNull() ?: 1280, parts[1].toIntOrNull() ?: 720)
            }
            cm.setFps(settingsStore.getVideoFps())
            cm.setJpegQuality(settingsStore.getJpegQuality())
            cm.setMirrorEnabled(settingsStore.getMirrorEnabled())
            cm.setMotionDetection(settingsStore.getMotionDetection())
            cm.setMotionSensitivity(settingsStore.getMotionSensitivity())
            cm.setZoom(settingsStore.getZoomLevel())

            if (settingsStore.getCameraFacing() == 1) {
                // Already initialized as BACK, switch if needed
            }
        }

        // Set camera callbacks
        cm.onJpegFrame = { jpeg ->
            synchronized(jpegFrameLock) {
                latestJpegFrame = jpeg
            }
            // Push to all active MJPEG stream listeners
            httpServer?.broadcastJpeg(jpeg)
        }

        cm.onH264Frame = { image ->
            h264Encoder?.let { encoder ->
                // Feed the image to the encoder's surface
                // The surface input approach means CameraX writes directly to the encoder surface
            }
        }

        cm.onMotionDetected = { motion ->
            Log.d(TAG, "Motion detected: $motion")
        }

        // Start H264 encoder
        val encoder = H264Encoder(1280, 720, 30, 2_000_000)
        h264Encoder = encoder
        encoder.onNalUnit = { nal ->
            rtspServer?.queueH264Nal(nal)
        }
        encoder.onSpsPps = { sps, pps ->
            spsData = sps
            ppsData = pps
            rtspServer?.setSpsPps(sps, pps)
        }
        encoder.start()

        // Start audio capture
        val audio = AudioCapture()
        audioCapture = audio
        audio.onAacFrame = { aacFrame ->
            rtspServer?.queueAacFrame(aacFrame)
        }
        audio.start()

        // Start RTSP server
        val rtsp = RtspServer(8554)
        rtspServer = rtsp
        rtsp.onClientConnected = { connected ->
            Log.d(TAG, "RTSP client connected: $connected")
        }
        rtsp.start()

        // Start HTTP server
        CoroutineScope(Dispatchers.IO).launch {
            val httpPort = settingsStore.getServerPort()
            val http = HttpServer(httpPort, this@CameraService)
            httpServer = http
            http.start()
        }

        // Start camera
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        cm.start()

        // Start JPEG capture for MJPEG
        cm.triggerJpegCapture()

        // Start sensor polling for battery updates
        sensorPollJob = serviceScope.launch {
            while (isActive) {
                sensorCollector.refreshBattery()
                cm.currentSensorData = sensorCollector.sensorData.value
                delay(3000)
            }
        }

        isServerRunning = true
        Log.i(TAG, "Server started successfully")
    }

    fun stopServer() {
        if (!isServerRunning) return

        isServerRunning = false

        // Stop sensor polling
        sensorPollJob?.cancel()
        sensorPollJob = null

        // Stop camera and JPEG capture
        cameraManager?.stopJpegCapture()
        cameraManager?.stop()
        cameraManager = null

        // Stop servers
        rtspServer?.stop()
        rtspServer = null

        httpServer?.stopServer()
        httpServer = null

        // Stop media
        audioCapture?.stop()
        audioCapture = null

        h264Encoder?.stop()
        h264Encoder = null

        // Stop sensor
        sensorCollector.stop()

        latestJpegFrame = null
        spsData = null
        ppsData = null

        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED

        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "Server stopped")
    }

    fun restartServer() {
        serviceScope.launch {
            stopServer()
            delay(500)
            startServer()
        }
    }

    fun handleCommand(command: String, params: Map<String, String>) {
        val cm = cameraManager ?: return
        Log.d(TAG, "Command: $command, params: $params")

        when (command) {
            "cam_switch" -> {
                cm.switchCamera()
                serviceScope.launch {
                    settingsStore.setCameraFacing(
                        if (cm.getCameraInfo()["facing"] == "back") 0 else 1
                    )
                }
            }
            "torch" -> {
                val state = params["state"] ?: params["value"] ?: "toggle"
                when (state) {
                    "on" -> { cm.setTorch(true); serviceScope.launch { settingsStore.setFlashEnabled(true) } }
                    "off" -> { cm.setTorch(false); serviceScope.launch { settingsStore.setFlashEnabled(false) } }
                    "toggle" -> {
                        val current = params["state"] ?: "off"
                        cm.setTorch(current == "on")
                    }
                }
            }
            "mirror" -> {
                val state = params["state"] ?: params["value"] ?: "toggle"
                val enabled = when (state) {
                    "on" -> true; "off" -> false
                    else -> !(cm.getCameraInfo()["mirror"] as? Boolean ?: false)
                }
                cm.setMirrorEnabled(enabled)
                serviceScope.launch { settingsStore.setMirrorEnabled(enabled) }
            }
            "resolution" -> {
                val width = params["width"]?.toIntOrNull() ?: 1280
                val height = params["height"]?.toIntOrNull() ?: 720
                cm.setResolution(width, height)
                serviceScope.launch {
                    settingsStore.setVideoResolution("${width}x${height}")
                }
                // Need to restart camera to apply
                serviceScope.launch {
                    delay(100)
                    cm.stop()
                    delay(200)
                    cm.start()
                }
            }
            "quality" -> {
                val quality = params["quality"]?.toIntOrNull()
                    ?: params["value"]?.toIntOrNull() ?: 80
                cm.setJpegQuality(quality)
                serviceScope.launch { settingsStore.setJpegQuality(quality) }
            }
            "zoom" -> {
                val zoom = params["zoom"]?.toFloatOrNull()
                    ?: params["value"]?.toFloatOrNull() ?: 1.0f
                cm.setZoom(zoom)
                serviceScope.launch { settingsStore.setZoomLevel(zoom) }
            }
            "motion" -> {
                val state = params["state"] ?: params["value"] ?: "toggle"
                val enabled = when (state) {
                    "on" -> true; "off" -> false
                    else -> !(cm.getCameraInfo()["motion_detection"] as? Boolean ?: false)
                }
                cm.setMotionDetection(enabled)
                serviceScope.launch { settingsStore.setMotionDetection(enabled) }
            }
            "motion_sensitivity" -> {
                val sens = params["sensitivity"]?.toIntOrNull() ?: 30
                cm.setMotionSensitivity(sens)
                serviceScope.launch { settingsStore.setMotionSensitivity(sens) }
            }
        }
    }

    fun getLatestJpeg(): ByteArray? {
        synchronized(jpegFrameLock) {
            return latestJpegFrame
        }
    }

    fun getSensorData(): SensorData = sensorCollector.sensorData.value

    fun getCameraInfo(): Map<String, Any> = cameraManager?.getCameraInfo() ?: emptyMap()

    fun isRunning(): Boolean = isServerRunning

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopServer()
        instance = null
        super.onDestroy()
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NetCamApp.CHANNEL_ID)
            .setContentTitle("NetCam Pro")
            .setContentText("NetCam Pro is running")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
