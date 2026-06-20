package com.netcam

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.format.Formatter
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleObserver
import com.netcam.server.CameraService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private var cameraService: CameraService? = null
    private var isBound = false
    private var permissionsGranted = false

    private val requiredPermissions = mutableListOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.BODY_SENSORS)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        permissionsGranted = allGranted
        if (allGranted) {
            Log.d(TAG, "All permissions granted, starting server")
            startService(Intent(this, CameraService::class.java).apply {
                action = CameraService.ACTION_START
            })
        } else {
            Log.w(TAG, "Some permissions denied: $result")
            Toast.makeText(this, "需要摄像头和麦克风权限才能运行", Toast.LENGTH_LONG).show()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            // CameraService doesn't use binder, we use static instance
            cameraService = CameraService.getInstance()
            isBound = true
            Log.d(TAG, "Service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cameraService = null
            isBound = false
            Log.d(TAG, "Service disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = Intent(this, CameraService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)

        // Check and request permissions
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            permissionsGranted = true
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }

        setContent {
            NetCamTheme {
                MainScreen(
                    permissionsGranted = permissionsGranted,
                    onStartServer = {
                        startService(Intent(this, CameraService::class.java).apply {
                            action = CameraService.ACTION_START
                        })
                    },
                    onStopServer = {
                        startService(Intent(this, CameraService::class.java).apply {
                            action = CameraService.ACTION_STOP
                        })
                    },
                    getService = { CameraService.getInstance() }
                )
            }
        }
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        super.onDestroy()
    }
}

@Composable
fun NetCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF1A73E8),
            secondary = Color(0xFF34A853),
            background = Color(0xFF0a0a0a),
            surface = Color(0xFF1a1a2e),
            onPrimary = Color.White,
            onSecondary = Color.White,
            onBackground = Color(0xFFe0e0e0),
            onSurface = Color(0xFFe0e0e0)
        ),
        content = content
    )
}

@Composable
fun MainScreen(
    permissionsGranted: Boolean = false,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    getService: () -> CameraService?
) {
    val scope = rememberCoroutineScope()
    var isRunning by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("Server Stopped") }
    var cameraError by remember { mutableStateOf(false) }

    // Poll service status
    LaunchedEffect(Unit) {
        while (true) {
            val service = getService()
            if (service != null) {
                val running = service.isRunning()
                isRunning = running
                statusText = if (running) "Server Running" else "Server Stopped"

                if (running) {
                    val ip = getLocalIpAddress()
                    serverUrl = "http://$ip:8080"
                } else {
                    serverUrl = ""
                }
            }
            delay(1000)
        }
    }

    // Auto-start server on first composition (only if permissions granted)
    LaunchedEffect(Unit) {
        delay(500)
        if (!permissionsGranted) return@LaunchedEffect
        val service = getService()
        if (service != null && !service.isRunning()) {
            onStartServer()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        // Header
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "NetCam Pro",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "IP Webcam Server",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
        }

        // Camera Preview
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (!cameraError) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = "Camera unavailable",
                    color = Color.Gray,
                    fontSize = 16.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Status indicator
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Status indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(
                                color = if (isRunning) Color(0xFF34A853) else Color(0xFFEA4335),
                                shape = RoundedCornerShape(6.dp)
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = statusText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (serverUrl.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = serverUrl,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF34A853),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "RTSP: rtsp://${serverUrl.removePrefix("http://")}:8554/live",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF1A73E8),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = {
                    if (isRunning) {
                        onStopServer()
                    } else {
                        onStartServer()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) Color(0xFFEA4335)
                    else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = if (isRunning) "Stop Server" else "Start Server",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Info text
        Text(
            text = "Open a web browser on the same network to view the stream.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
        )

        Spacer(modifier = Modifier.weight(1f))

        // Footer
        Text(
            text = "NetCam Pro v1.0",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        )
    }
}

private fun getLocalIpAddress(): String {
    try {
        java.net.NetworkInterface.getNetworkInterfaces()?.let { eni ->
            while (eni.hasMoreElements()) {
                val intf = eni.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                intf.inetAddresses?.let { addrs ->
                    while (addrs.hasMoreElements()) {
                        val a = addrs.nextElement()
                        if (a is java.net.Inet4Address && !a.isLoopbackAddress)
                            return a.hostAddress ?: "?"
                    }
                }
            }
        }
    } catch (_: Exception) {}
    return "192.168.1.100"
}
