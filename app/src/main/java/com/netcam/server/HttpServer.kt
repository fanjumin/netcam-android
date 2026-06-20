package com.netcam.server

import android.util.Log
import com.netcam.data.SensorData
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.Volatile

class HttpServer(
    private val port: Int = 8080,
    private val service: CameraService
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "HttpServer"
        private const val MJPEG_BOUNDARY = "netcamboundary"
        private const val POLL_INTERVAL = 16L // ~60fps
    }

    private val isStreaming = AtomicBoolean(false)
    private val streamListeners = mutableListOf<MjpegBody>()
    @Volatile private var latestMmjpegFrame: ByteArray? = null

    // Push JPEG to all active MJPEG streams
    fun broadcastJpeg(jpegData: ByteArray) {
        latestMmjpegFrame = jpegData
        synchronized(streamListeners) {
            val it = streamListeners.iterator()
            while (it.hasNext()) {
                if (!it.next().offerJpeg(jpegData)) {
                    it.remove()
                }
            }
        }
    }

    class MjpegBody : InputStream() {
        private val queue = java.util.concurrent.LinkedBlockingQueue<ByteArray>(3)
        private var currentBuffer: ByteArray? = null
        private var currentOffset = 0
        @Volatile var active = true
        private val boundaryBytes = "--$MJPEG_BOUNDARY\r\n".toByteArray()
        private val contentTypeBytes = "Content-Type: image/jpeg\r\n".toByteArray()
        private val contentLengthPrefix = "Content-Length: ".toByteArray()
        private val crlf = "\r\n\r\n".toByteArray()
        private val trailBoundary = "\r\n--$MJPEG_BOUNDARY--\r\n".toByteArray()

        fun offerJpeg(jpegData: ByteArray): Boolean {
            if (!active) return false
            val header = buildList {
                add(boundaryBytes)
                add(contentTypeBytes)
                add(contentLengthPrefix)
                add(jpegData.size.toString().toByteArray())
                add(crlf)
            }
            val frameSize = header.sumOf { it.size } + jpegData.size
            val frame = ByteArray(frameSize)
            var pos = 0
            for (part in header) {
                System.arraycopy(part, 0, frame, pos, part.size)
                pos += part.size
            }
            System.arraycopy(jpegData, 0, frame, pos, jpegData.size)
            return queue.offer(frame)
        }

        fun finish() {
            active = false
            queue.offer(trailBoundary)
        }

        override fun read(): Int {
            while (active) {
                if (currentBuffer != null && currentOffset < currentBuffer!!.size) {
                    return currentBuffer!![currentOffset++].toInt() and 0xFF
                }
                val next = queue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (next != null) {
                    currentBuffer = next
                    currentOffset = 0
                }
            }
            return -1
        }

        override fun available(): Int = 0
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        Log.d(TAG, "Request: $method $uri")

        try {
            return when {
                uri == "/" || uri == "/index.html" -> serveWebUI()
                uri == "/video" || uri == "/mjpeg" -> serveMjpeg()
                uri == "/shot.jpg" || uri == "/snapshot.jpg" -> serveSnapshot()
                uri == "/sensors.json" -> serveSensors()
                uri.startsWith("/api/") -> handleApi(uri, session)
                uri == "/favicon.ico" -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
                else -> {
                    val file = File(session.uri.removePrefix("/"))
                    if (file.exists() && !file.isDirectory) {
                        serveFile(file)
                    } else {
                        newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Serve error", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Server Error")
        }
    }

    private fun serveWebUI(): Response {
        val html = buildWebUI()
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun serveMjpeg(): Response {
        val body = MjpegBody()
        isStreaming.set(true)
        synchronized(streamListeners) { streamListeners.add(body) }
        return newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=$MJPEG_BOUNDARY", body)
    }

    private fun serveSnapshot(): Response {
        val snapshot = service.getLatestJpeg()
        if (snapshot != null) {
            return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(snapshot), snapshot.size.toLong())
        }
        return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "No snapshot available")
    }

    private fun serveSensors(): Response {
        val sd = service.getSensorData()
        val json = """{"light":${sd.light},"temperature":${sd.temperature},"pressure":${sd.pressure},"humidity":${sd.humidity},"accelerometer_x":${sd.accelerometerX},"accelerometer_y":${sd.accelerometerY},"battery_level":${sd.batteryLevel},"battery_temperature":${sd.batteryTemperature},"is_charging":${sd.isCharging},"streaming":${isStreaming.get()},"rtsp_port":8554,"http_port":$port}"""
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }

    private fun handleApi(uri: String, session: IHTTPSession): Response {
        val path = uri.removePrefix("/api/").trimEnd('/')

        try {
            return when {
                path == "cam_switch" -> {
                    service.handleCommand("cam_switch", emptyMap())
                    jsonResponse("ok", "camera switched")
                }
                path == "torch" || path == "flash" -> {
                    val params = session.parameters
                    val state = params["state"]?.firstOrNull() ?: params["value"]?.firstOrNull() ?: "toggle"
                    service.handleCommand("torch", mapOf("state" to state))
                    jsonResponse("ok", "torch $state")
                }
                path == "mirror" -> {
                    val params = session.parameters
                    val state = params["state"]?.firstOrNull() ?: params["value"]?.firstOrNull() ?: "toggle"
                    service.handleCommand("mirror", mapOf("state" to state))
                    jsonResponse("ok", "mirror $state")
                }
                path.startsWith("res") || path == "resolution" -> {
                    val params = if (path == "resolution") session.parameters else {
                        val resPart = path.removePrefix("res:").split("/")
                        mapOf("width" to listOf(resPart.getOrElse(0) { "1280" }),
                            "height" to listOf(resPart.getOrElse(1) { "720" }))
                    }
                    val width = params["width"]?.firstOrNull()?.toIntOrNull() ?: 1280
                    val height = params["height"]?.firstOrNull()?.toIntOrNull() ?: 720
                    service.handleCommand("resolution", mapOf("width" to width.toString(), "height" to height.toString()))
                    jsonResponse("ok", "resolution $width x $height")
                }
                path == "quality" || path.startsWith("quality:") -> {
                    val quality = if (path.startsWith("quality:")) {
                        path.removePrefix("quality:").toIntOrNull() ?: 80
                    } else {
                        session.parameters["value"]?.firstOrNull()?.toIntOrNull() ?: 80
                    }
                    service.handleCommand("quality", mapOf("quality" to quality.toString()))
                    jsonResponse("ok", "quality $quality")
                }
                path == "zoom" || path.startsWith("zoom:") -> {
                    val zoom = if (path.startsWith("zoom:")) {
                        path.removePrefix("zoom:").toFloatOrNull() ?: 1.0f
                    } else {
                        session.parameters["value"]?.firstOrNull()?.toFloatOrNull() ?: 1.0f
                    }
                    service.handleCommand("zoom", mapOf("zoom" to zoom.toString()))
                    jsonResponse("ok", "zoom $zoom")
                }
                path == "motion" || path == "motion_detect" -> {
                    val params = session.parameters
                    val state = params["state"]?.firstOrNull() ?: params["value"]?.firstOrNull() ?: "toggle"
                    service.handleCommand("motion", mapOf("state" to state))
                    jsonResponse("ok", "motion $state")
                }
                path == "motion_sensitivity" -> {
                    val params = session.parameters
                    val sens = params["value"]?.firstOrNull()?.toIntOrNull() ?: 30
                    service.handleCommand("motion_sensitivity", mapOf("sensitivity" to sens.toString()))
                    jsonResponse("ok", "motion sensitivity $sens")
                }
                path == "status" -> {
                    val sd = service.getSensorData()
                    val json = """{"running":${service.isRunning()},"streaming":${isStreaming.get()},"rtsp_clients":0,"sensor_data":{"light":${sd.light},"temperature":${sd.temperature},"pressure":${sd.pressure},"humidity":${sd.humidity},"battery":${sd.batteryLevel},"battery_temperature":${sd.batteryTemperature},"is_charging":${sd.isCharging}}}"""
                    return newFixedLengthResponse(Response.Status.OK, "application/json", json)
                }
                path == "info" -> {
                    val info = service.getCameraInfo()
                    val json = info.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"$v\"" }
                    return newFixedLengthResponse(Response.Status.OK, "application/json", json)
                }
                path == "restart" -> {
                    service.restartServer()
                    jsonResponse("ok", "server restarting")
                }
                else -> {
                    // Try to parse as command:value
                    val parts = path.split(":", limit = 2)
                    if (parts.size == 2) {
                        val cmd = parts[0]
                        val value = parts[1]
                        service.handleCommand(cmd, mapOf("value" to value))
                        jsonResponse("ok", "$cmd $value")
                    } else {
                        jsonResponse("error", "unknown command", 400)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "API error: $path", e)
            return jsonResponse("error", e.message ?: "unknown error", 500)
        }
    }

    private fun jsonResponse(status: String, message: String, code: Int = 200): Response {
        val json = """{"status":"$status","message":"${message.replace("\"","\\\"")}"}"""
        return newFixedLengthResponse(
            when (code) {
                200 -> Response.Status.OK
                400 -> Response.Status.BAD_REQUEST
                500 -> Response.Status.INTERNAL_ERROR
                else -> Response.Status.OK
            },
            "application/json",
            json
        )
    }

    private fun serveFile(file: File): Response {
        return try {
            val mime = when {
                file.name.endsWith(".js") -> "application/javascript"
                file.name.endsWith(".css") -> "text/css"
                file.name.endsWith(".png") -> "image/png"
                file.name.endsWith(".jpg") || file.name.endsWith(".jpeg") -> "image/jpeg"
                file.name.endsWith(".html") -> "text/html"
                file.name.endsWith(".svg") -> "image/svg+xml"
                file.name.endsWith(".ico") -> "image/x-icon"
                else -> "application/octet-stream"
            }
            newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(file), file.length())
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
        }
    }

    override fun start() {
        try {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            Log.i(TAG, "HTTP server started on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HTTP server", e)
        }
    }

    fun stopServer() {
        isStreaming.set(false)
        stop()
        Log.i(TAG, "HTTP server stopped")
    }

    private fun buildWebUI(): String {
        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
<title>NetCam Pro</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{background:#0a0a0a;color:#e0e0e0;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;overflow-x:hidden;min-height:100vh}
.header{background:linear-gradient(135deg,#1a73e8,#1557b0);padding:12px 16px;display:flex;align-items:center;justify-content:space-between;position:sticky;top:0;z-index:100;box-shadow:0 2px 8px rgba(0,0,0,.4)}
.header h1{font-size:18px;font-weight:600;letter-spacing:.5px}
.header .status{display:flex;align-items:center;gap:8px;font-size:13px}
.status-dot{width:8px;height:8px;border-radius:50%;display:inline-block}
.status-dot.on{background:#34a853;box-shadow:0 0 6px #34a853}
.status-dot.off{background:#ea4335;box-shadow:0 0 6px #ea4335}
.video-container{position:relative;width:100%;background:#000;overflow:hidden;max-height:70vh}
.video-container img{width:100%;display:block;object-fit:contain;max-height:70vh}
.fullscreen-btn{position:absolute;bottom:8px;right:8px;background:rgba(0,0,0,.6);border:none;color:#fff;padding:6px 12px;border-radius:4px;font-size:12px;cursor:pointer;z-index:10}
.fullscreen-btn:hover{background:rgba(0,0,0,.8)}
.controls-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(160px,1fr));gap:12px;padding:12px}
.control-group{background:#1a1a2e;border-radius:10px;padding:12px;border:1px solid #2a2a3e}
.control-group label{display:block;font-size:12px;color:#888;margin-bottom:6px;text-transform:uppercase;letter-spacing:.5px}
.control-group .value{font-size:13px;color:#34a853;float:right}
.btn{background:#2a2a3e;border:none;color:#e0e0e0;padding:8px 14px;border-radius:6px;font-size:13px;cursor:pointer;width:100%;transition:all .15s;text-align:center}
.btn:hover{background:#3a3a4e}
.btn:active{transform:scale(.97)}
.btn.primary{background:#1a73e8}
.btn.primary:hover{background:#1557b0}
.btn.active{background:#34a853;color:#fff}
.btn.danger{background:#ea4335}
.btn.danger:hover{background:#d33426}
.btn-group{display:flex;gap:6px}
.btn-group .btn{flex:1}
input[type=range]{width:100%;height:4px;-webkit-appearance:none;appearance:none;background:#2a2a3e;border-radius:2px;outline:none;margin-top:4px}
input[type=range]::-webkit-slider-thumb{-webkit-appearance:none;width:16px;height:16px;border-radius:50%;background:#1a73e8;cursor:pointer}
select{width:100%;padding:8px;background:#2a2a3e;border:1px solid #3a3a4e;border-radius:6px;color:#e0e0e0;font-size:13px;outline:none}
.sensor-dashboard{display:grid;grid-template-columns:repeat(auto-fill,minmax(100px,1fr));gap:8px;padding:12px}
.sensor-card{background:#1a1a2e;border-radius:8px;padding:10px;text-align:center;border:1px solid #2a2a3e}
.sensor-card .sensor-label{font-size:10px;color:#888;text-transform:uppercase;letter-spacing:.5px}
.sensor-card .sensor-value{font-size:18px;font-weight:600;margin-top:4px;color:#e0e0e0}
.sensor-card .sensor-unit{font-size:11px;color:#666;margin-top:2px}
.sensor-card.battery{grid-column:span 2}
.battery-bar{width:100%;height:6px;background:#2a2a3e;border-radius:3px;margin-top:6px;overflow:hidden}
.battery-bar .fill{height:100%;border-radius:3px;transition:width .5s}
.battery-bar .fill.high{background:#34a853}
.battery-bar .fill.med{background:#fbbc04}
.battery-bar .fill.low{background:#ea4335}
.info-bar{background:#1a1a2e;padding:10px 16px;display:flex;gap:16px;flex-wrap:wrap;font-size:12px;border-top:1px solid #2a2a3e}
.info-bar span{color:#888}
.info-bar .info-value{color:#e0e0e0;margin-left:4px}
.url-display{background:#111;padding:8px 12px;margin:0 12px 12px;border-radius:8px;font-size:13px;font-family:monospace;text-align:center;border:1px solid #2a2a3e;word-break:break-all}
.url-display .label{color:#888}
.url-display .url{color:#34a853}
.footer{padding:16px;text-align:center;font-size:11px;color:#555}
@media(max-width:480px){.controls-grid{grid-template-columns:1fr 1fr}.sensor-dashboard{grid-template-columns:1fr 1fr 1fr}}
</style>
</head>
<body>
<div class="header">
<h1>NetCam Pro</h1>
<div class="status">
<span class="status-dot off" id="statusDot"></span>
<span id="statusText">Connecting...</span>
</div>
</div>

<div class="video-container" id="videoContainer">
<img id="mjpegStream" src="/video" alt="Live Stream">
<button class="fullscreen-btn" id="fullscreenBtn">⛶ Fullscreen</button>
</div>

<div class="url-display">
<span class="label">Stream URL: </span>
<a class="url" href="/video" target="_blank">http://<span id="hostDisplay">...</span>:8080/video</a>
</div>

<div class="info-bar">
<span>Res: <span class="info-value" id="infoRes">-</span></span>
<span>FPS: <span class="info-value" id="infoFps">-</span></span>
<span>Clients: <span class="info-value" id="infoClients">0</span></span>
<span>Motion: <span class="info-value" id="infoMotion">off</span></span>
</div>

<div class="controls-grid">
<div class="control-group">
<label>Resolution</label>
<select id="resSelect">
<option value="1920x1080">1920x1080</option>
<option value="1280x720" selected>1280x720</option>
<option value="854x480">854x480</option>
<option value="640x480">640x480</option>
<option value="320x240">320x240</option>
</select>
</div>

<div class="control-group">
<label>Quality <span class="value" id="qualityVal">80</span></label>
<input type="range" id="qualitySlider" min="10" max="100" value="80">
</div>

<div class="control-group">
<label>Zoom <span class="value" id="zoomVal">1.0x</span></label>
<input type="range" id="zoomSlider" min="1" max="8" step="0.1" value="1">
</div>

<div class="control-group">
<label>Camera</label>
<div class="btn-group">
<button class="btn primary" id="flipBtn">⟳ Flip</button>
<button class="btn" id="torchBtn">🔦 Flash</button>
</div>
</div>

<div class="control-group">
<label>Display</label>
<div class="btn-group">
<button class="btn" id="mirrorBtn">↔ Mirror</button>
<button class="btn" id="motionBtn">📡 Motion</button>
</div>
</div>

<div class="control-group">
<label>Actions</label>
<div class="btn-group">
<button class="btn danger" id="snapshotBtn">📷 Snapshot</button>
<button class="btn" id="restartBtn">🔄 Restart</button>
</div>
</div>
</div>

<div class="sensor-dashboard" id="sensorDashboard">
<div class="sensor-card" id="sensorLight">
<div class="sensor-label">Light</div>
<div class="sensor-value">-</div>
<div class="sensor-unit">lux</div>
</div>
<div class="sensor-card" id="sensorTemp">
<div class="sensor-label">Temperature</div>
<div class="sensor-value">-</div>
<div class="sensor-unit">°C</div>
</div>
<div class="sensor-card" id="sensorPressure">
<div class="sensor-label">Pressure</div>
<div class="sensor-value">-</div>
<div class="sensor-unit">hPa</div>
</div>
<div class="sensor-card" id="sensorHumidity">
<div class="sensor-label">Humidity</div>
<div class="sensor-value">-</div>
<div class="sensor-unit">%</div>
</div>
<div class="sensor-card battery" id="sensorBattery">
<div class="sensor-label">Battery</div>
<div class="sensor-value" id="batteryValue">-</div>
<div class="sensor-unit" id="batteryUnit">-</div>
<div class="battery-bar"><div class="fill high" id="batteryFill" style="width:0%"></div></div>
</div>
</div>

<div class="footer">
NetCam Pro v1.0 &mdash; IP Webcam Clone
</div>

<script>
(function(){
const BASE = '';
const host = location.host;
document.getElementById('hostDisplay').textContent = host;
const els = {};

function q(s) { return document.querySelector(s); }
function qi(s) { return document.getElementById(s); }

els.statusDot = qi('statusDot');
els.statusText = qi('statusText');
els.resSelect = qi('resSelect');
els.qualitySlider = qi('qualitySlider');
els.qualityVal = qi('qualityVal');
els.zoomSlider = qi('zoomSlider');
els.zoomVal = qi('zoomVal');
els.flipBtn = qi('flipBtn');
els.torchBtn = qi('torchBtn');
els.mirrorBtn = qi('mirrorBtn');
els.motionBtn = qi('motionBtn');
els.snapshotBtn = qi('snapshotBtn');
els.restartBtn = qi('restartBtn');
els.fullscreenBtn = qi('fullscreenBtn');
els.videoContainer = qi('videoContainer');
els.mjpegStream = qi('mjpegStream');
els.infoRes = qi('infoRes');
els.infoFps = qi('infoFps');
els.infoClients = qi('infoClients');
els.infoMotion = qi('infoMotion');

let torchState = false;
let mirrorState = false;
let motionState = false;

function setStatus(on) {
    els.statusDot.className = 'status-dot ' + (on ? 'on' : 'off');
    els.statusText.textContent = on ? 'Live' : 'Offline';
}

function api(path) {
    fetch(BASE + '/api/' + path)
        .then(r => r.json())
        .then(d => { if (d.status !== 'ok') console.warn('API:', d); })
        .catch(e => console.error('API error:', e));
}

function toggleBtn(el, active) {
    el.classList.toggle('active', active);
}

// Resolution
els.resSelect.addEventListener('change', function() {
    const val = this.value;
    api('resolution?width=' + val.split('x')[0] + '&height=' + val.split('x')[1]);
});

// Quality
els.qualitySlider.addEventListener('input', function() {
    els.qualityVal.textContent = this.value;
});
els.qualitySlider.addEventListener('change', function() {
    api('quality:' + this.value);
});

// Zoom
els.zoomSlider.addEventListener('input', function() {
    els.zoomVal.textContent = this.value + 'x';
});
els.zoomSlider.addEventListener('change', function() {
    api('zoom:' + this.value);
});

// Flip camera
els.flipBtn.addEventListener('click', function() {
    api('cam_switch');
});

// Torch
els.torchBtn.addEventListener('click', function() {
    torchState = !torchState;
    toggleBtn(this, torchState);
    api('torch?state=' + (torchState ? 'on' : 'off'));
});

// Mirror
els.mirrorBtn.addEventListener('click', function() {
    mirrorState = !mirrorState;
    toggleBtn(this, mirrorState);
    api('mirror?state=' + (mirrorState ? 'on' : 'off'));
});

// Motion detection
els.motionBtn.addEventListener('click', function() {
    motionState = !motionState;
    toggleBtn(this, motionState);
    api('motion?state=' + (motionState ? 'on' : 'off'));
    els.infoMotion.textContent = motionState ? 'on' : 'off';
});

// Snapshot
els.snapshotBtn.addEventListener('click', function() {
    window.open('/shot.jpg', '_blank');
});

// Restart
els.restartBtn.addEventListener('click', function() {
    api('restart');
    setTimeout(function(){ location.reload(); }, 2000);
});

// Fullscreen
els.fullscreenBtn.addEventListener('click', function() {
    const el = els.videoContainer;
    if (el.requestFullscreen) el.requestFullscreen();
    else if (el.webkitRequestFullscreen) el.webkitRequestFullscreen();
    else if (el.msRequestFullscreen) el.msRequestFullscreen();
});

// Refresh stream periodically (reconnect)
setInterval(function() {
    const img = els.mjpegStream;
    img.src = '/video?_=' + Date.now();
}, 30000);

// Poll sensors and status
function poll() {
    fetch(BASE + '/sensors.json?_=' + Date.now())
        .then(r => r.json())
        .then(d => {
            setStatus(d.streaming);
            if (d.camera) {
                els.infoRes.textContent = d.camera.resolution || '-';
                els.infoFps.textContent = d.camera.fps || '-';
            }
            els.infoClients.textContent = d.rtsp_clients || '0';
            els.infoMotion.textContent = d.camera && d.camera.motion_detection ? 'on' : 'off';

            // Sensor dashboard
            qi('sensorLight').querySelector('.sensor-value').textContent = d.light > 0 ? d.light.toFixed(0) : '-';
            qi('sensorTemp').querySelector('.sensor-value').textContent = d.temperature > 0 ? d.temperature.toFixed(1) : '-';
            qi('sensorPressure').querySelector('.sensor-value').textContent = d.pressure > 0 ? d.pressure.toFixed(0) : '-';
            qi('sensorHumidity').querySelector('.sensor-value').textContent = d.humidity > 0 ? d.humidity.toFixed(0) : '-';

            const batt = d.battery_level || 0;
            const pct = Math.round(batt * 100);
            const temp = d.battery_temperature || 0;
            qi('batteryValue').textContent = pct + '%';
            qi('batteryUnit').textContent = (d.is_charging ? '⚡ ' : '') + temp.toFixed(1) + '°C';
            const fill = qi('batteryFill');
            fill.style.width = pct + '%';
            fill.className = 'fill ' + (pct > 50 ? 'high' : pct > 20 ? 'med' : 'low');
        })
        .catch(function() {
            setStatus(false);
        });
}

poll();
setInterval(poll, 3000);
})();
</script>
</body>
</html>"""
    }
}

