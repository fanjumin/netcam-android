package com.netcam.server

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.netcam.MainActivity
import com.netcam.NetCamApp
import com.netcam.data.SettingsStore
import com.netcam.media.AudioCapture
import com.netcam.media.H264Encoder
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * CameraService — runs HTTP server + optional RTSP server + optional audio capture.
 * Camera1 (old API) frame callback runs in Activity, pushed via broadcastFrame().
 *
 * Supports remote camera control via commandQueue — HttpServer enqueues commands,
 * MainActivity polls them in the frame callback and applies to the live camera.
 *
 * Each component is started independently so one failure doesn't block the rest.
 */
class CameraService : LifecycleService() {

    companion object {
        private const val TAG = "CameraService"
        const val ACTION_START = "com.netcam.action.START"
        const val ACTION_STOP = "com.netcam.action.STOP"
        const val ACTION_RESTART = "com.netcam.action.RESTART"
        private var instance: CameraService? = null
        fun getInstance(): CameraService? = instance
    }

    private lateinit var settingsStore: SettingsStore
    var httpServer: HttpServer? = null
        private set
    var rtspServer: RtspServer? = null
        private set
    var audioCapture: AudioCapture? = null
        private set
    var h264Encoder: H264Encoder? = null
        private set

    private var isServerRunning = false
    private var frameCount = 0L

    /** Used by MainActivity to push frames from Camera1 + SurfaceView */
    @Volatile var latestJpeg: ByteArray? = null

    /** Motion detection state (set by Activity) */
    @Volatile var motionFlag: Boolean = false

    /** Shared latest AAC audio frame for HTTP audio stream */
    @Volatile var latestAacFrame: ByteArray? = null

    /** Thread-safe command queue for HTTP API remote control.
     *  Each entry is a Map<String,Any> with the same keys as applyCameraSettings expects.
     *  HttpServer enqueues, MainActivity polls + applies on frame callback. */
    val commandQueue: ConcurrentLinkedQueue<Map<String, Any>> = ConcurrentLinkedQueue()

    /** Convenience: enqueue a single-key command map */
    fun enqueueCommand(key: String, value: Any) {
        commandQueue.offer(mapOf(key to value))
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "onCreate: instance set, initializing SettingsStore")
        try {
            settingsStore = SettingsStore(this)
            Log.d(TAG, "SettingsStore created")
        } catch (e: Exception) {
            Log.e(TAG, "SettingsStore init failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: "null"
        Log.d(TAG, "onStartCommand: action=$action")
        when (action) {
            ACTION_START -> startServer()
            ACTION_STOP -> stopServer()
            ACTION_RESTART -> {
                stopServer()
                CoroutineScope(Dispatchers.Main).launch {
                    delay(300)
                    startServer()
                }
            }
        }
        return START_STICKY
    }

    fun setMotionDetected(detected: Boolean) {
        motionFlag = detected
        httpServer?.setMotionDetected(detected)
    }

    fun startServer() {
        if (isServerRunning) { Log.d(TAG, "startServer: already running"); return }
        Log.d(TAG, "startServer: beginning")

        try {
            // 1. Foreground notification (must be first, required by Android)
            Log.d(TAG, "startServer: creating notification")
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Log.d(TAG, "startServer: calling startForeground with types")
                startForeground(NetCamApp.NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NetCamApp.NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "startServer: startForeground OK")

            val port = runBlocking(Dispatchers.IO) { settingsStore.getServerPort() }
            Log.d(TAG, "startServer: port=$port")
            isServerRunning = true

            // 2. Start HTTP server (core — must succeed for MJPEG streaming)
            try {
                val http = HttpServer(port, this)
                httpServer = http
                http.start()
                Log.i(TAG, "HTTP server started on port $port")
            } catch (e: Exception) {
                Log.e(TAG, "HTTP server failed to start", e)
            }

            // 3. Start RTSP server (optional — can work without it)
            try {
                val rtsp = RtspServer(8554)
                rtspServer = rtsp
                rtsp.start()
                Log.i(TAG, "RTSP server started on port 8554")
            } catch (e: Exception) {
                Log.e(TAG, "RTSP server failed to start", e)
            }

            // 4. Start H264 encoder (optional — for RTSP video)
            try {
                val encoder = H264Encoder(1280, 720, 15, 1_000_000)
                h264Encoder = encoder
                encoder.onNalUnit = { nal ->
                    rtspServer?.queueH264Nal(nal)
                }
                encoder.onSpsPps = { sps, pps ->
                    rtspServer?.setSpsPps(sps, pps)
                }
                encoder.start()
                Log.i(TAG, "H264 encoder started")
            } catch (e: Exception) {
                Log.e(TAG, "H264 encoder failed to start", e)
            }

            // 5. Start audio capture (optional)
            try {
                val audio = AudioCapture()
                audioCapture = audio
                audio.onAacFrame = { aac ->
                    latestAacFrame = aac
                    rtspServer?.queueAacFrame(aac)
                }
                audio.start()
                Log.i(TAG, "Audio capture started")
            } catch (e: Exception) {
                Log.e(TAG, "Audio capture failed to start", e)
            }

            Log.i(TAG, "Server startup complete")
        } catch (e: Exception) {
            Log.e(TAG, "Fatal: Server startup failed", e)
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
            isServerRunning = false
        }
    }

    fun broadcastFrame(jpeg: ByteArray) {
        if (!isServerRunning) return
        latestJpeg = jpeg
        httpServer?.broadcastJpeg(jpeg)
        frameCount++
    }

    fun getLatestFrame(): ByteArray? = latestJpeg
    fun getFrameCount(): Long = frameCount

    fun stopServer() {
        isServerRunning = false
        try { httpServer?.stopServer() } catch (_: Exception) {}
        httpServer = null
        try { h264Encoder?.stop() } catch (_: Exception) {}
        h264Encoder = null
        try { rtspServer?.stop() } catch (_: Exception) {}
        rtspServer = null
        try { audioCapture?.stop() } catch (_: Exception) {}
        audioCapture = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        Log.i(TAG, "Server stopped")
    }

    fun isRunning(): Boolean = isServerRunning

    override fun onDestroy() {
        stopServer()
        instance = null
        super.onDestroy()
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, NetCamApp.CHANNEL_ID)
            .setContentTitle("NetCam Pro")
            .setContentText("Streaming: HTTP + RTSP + Audio")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
