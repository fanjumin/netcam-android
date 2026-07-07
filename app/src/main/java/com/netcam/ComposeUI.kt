package com.netcam

import android.view.SurfaceHolder
import android.view.SurfaceView
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
import com.netcam.data.SettingsStore
import com.netcam.server.CameraService
import kotlinx.coroutines.delay

@Composable
fun ComposeMainScreen(
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onOpenSettings: () -> Unit,
    getService: () -> CameraService?,
    onSurfaceReady: (SurfaceHolder) -> Unit,
    onSleep: () -> Unit = {}
) {
    var isRunning by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("Server Stopped") }
    val ctx = LocalContext.current
    val settingsStore = remember { SettingsStore(ctx) }

    LaunchedEffect(Unit) {
        while (true) {
            val service = getService()
            val running = service?.isRunning() ?: false
            isRunning = running
            statusText = if (running) "Server Running" else "Server Stopped"
            if (running) { serverUrl = "http://${getLocalIpAddress()}:${settingsStore.getServerPort()}" }
            else { serverUrl = "" }
            delay(1000)
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF1A73E8), secondary = Color(0xFF34A853),
            background = Color(0xFF0a0a0a), surface = Color(0xFF1a1a2e),
            onPrimary = Color.White, onSecondary = Color.White,
            onBackground = Color(0xFFe0e0e0), onSurface = Color(0xFFe0e0e0)
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // UI overlay
            Column(modifier = Modifier.fillMaxSize()) {
            Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("NetCam Pro", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("IP Webcam Server", fontSize = 14.sp, color = Color.White.copy(alpha = 0.8f))
                    }
                    TextButton(onClick = onOpenSettings) { Text("设置", color = Color.White, fontWeight = FontWeight.Bold) }
                    TextButton(onClick = onSleep) { Text("息屏", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp) }
                }
            }

            Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f/9f).background(Color.Black), contentAlignment = Alignment.Center) {
                AndroidView(factory = { ctx2 ->
                    SurfaceView(ctx2).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) { onSurfaceReady(holder) }
                            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                            override fun surfaceDestroyed(h: SurfaceHolder) {}
                        })
                    }
                }, modifier = Modifier.fillMaxSize())
            }

            Spacer(Modifier.height(16.dp))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(12.dp).background(
                            if (isRunning) Color(0xFF34A853) else Color(0xFFEA4335), shape = RoundedCornerShape(6.dp)))
                        Spacer(Modifier.width(8.dp))
                        Text(statusText, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    }
                    if (serverUrl.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(serverUrl, fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF34A853), textAlign = TextAlign.Center)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(onClick = if (isRunning) onStopServer else onStartServer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (isRunning) Color(0xFFEA4335) else Color(0xFF34A853)),
                shape = RoundedCornerShape(24.dp)) {
                Text(if (isRunning) "Stop Server" else "Start Server", fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(16.dp))
            Text("同一网络下用浏览器打开上面地址查看画面", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f), textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp))

            Spacer(Modifier.weight(1f))
            Text("NetCam Pro v4.0", fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp))
        }  // Column
        }  // Box
    }  // MaterialTheme
}

