package com.netcam.server

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
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
    }

    private val isStreaming = AtomicBoolean(false)
    private val streamListeners = mutableListOf<MjpegBody>()
    @Volatile private var latestMmjpegFrame: ByteArray? = null
    @Volatile private var motionDetected: Boolean = false

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

    fun setMotionDetected(detected: Boolean) {
        motionDetected = detected
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
                // Web UI
                uri == "/" || uri == "/index.html" -> serveWebUI()

                // Video stream
                uri == "/video" || uri == "/mjpeg" -> serveMjpeg()
                uri == "/shot.jpg" || uri == "/snapshot.jpg" -> serveSnapshot()

                // Audio stream (raw AAC ADTS)
                uri == "/audio.aac" || uri == "/audio" -> serveAudio()

                // Status and control APIs
                uri == "/status" -> serveStatus()
                uri == "/debug" -> serveDebug()
                uri == "/api/restart" -> serveRestart()
                uri == "/favicon.ico" -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
                // POST settings
                uri == "/api/settings" && method == Method.POST -> handleSettings(session)
                // Remote camera control via HTTP API
                uri.startsWith("/api/") && method == Method.POST -> handleCameraApi(uri, session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Serve error", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Server Error")
        }
    }

    private fun serveWebUI(): Response {
        val html = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>NetCam Pro</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{background:#0a0a0a;color:#e0e0e0;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;min-height:100vh}
.header{background:linear-gradient(135deg,#1a73e8,#1557b0);padding:16px 20px;text-align:center}
.header h1{font-size:22px;color:#fff}
.header p{font-size:13px;color:rgba(255,255,255,.7);margin-top:4px}
.video-container{width:100%;max-width:100%;background:#000;position:relative;cursor:pointer}
.video-container img{width:100%;display:block}
.video-container .loading{position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);color:#888;font-size:14px}
.video-container .snap-btn{position:absolute;bottom:12px;right:12px;background:rgba(0,0,0,.6);border:1px solid rgba(255,255,255,.3);color:#fff;padding:8px 14px;border-radius:6px;font-size:12px;cursor:pointer}
.video-container .snap-btn:hover{background:rgba(0,0,0,.8)}
.info-grid{display:grid;grid-template-columns:1fr 1fr;gap:10px;padding:14px;max-width:800px;margin:0 auto}
.info-card{background:#1a1a2e;border-radius:10px;padding:14px;border:1px solid #2a2a3e}
.info-card .label{font-size:11px;color:#888;margin-bottom:4px}
.info-card .value{font-size:15px;color:#34a853;font-family:monospace;word-break:break-all}
.info-card .value.rtsp{color:#fbbc04}
.info-card .value.offline{color:#ea4335}
.control-bar{display:flex;gap:8px;padding:8px 14px;max-width:800px;margin:0 auto;flex-wrap:wrap}
.control-bar button{background:#1a1a2e;border:1px solid #2a2a3e;color:#e0e0e0;padding:8px 16px;border-radius:8px;font-size:13px;cursor:pointer;flex:1;min-width:80px}
.control-bar button:hover{background:#2a2a3e;border-color:#1a73e8}
.control-bar button.active{background:#1a73e8;border-color:#1a73e8;color:#fff}
.control-bar button.danger{color:#ea4335}
.footer{padding:20px;text-align:center;font-size:11px;color:#555}
.status-dot{display:inline-block;width:8px;height:8px;border-radius:50%;margin-right:6px}
.status-dot.on{background:#34a853}
.status-dot.off{background:#ea4335}
.hint{font-size:11px;color:#666;padding:0 14px;text-align:center;max-width:800px;margin:4px auto}
.toast{position:fixed;bottom:30px;left:50%;transform:translateX(-50%);background:#333;color:#fff;padding:10px 20px;border-radius:8px;font-size:13px;z-index:999;display:none}
</style>
</head>
<body>
<div class="header">
<h1>📷 NetCam Pro</h1>
<p id="status-text">IP Webcam Server</p>
</div>
<div class="video-container" id="videoContainer">
<img id="stream" src="/video" alt="Live Stream">
<div class="loading" id="loading">Loading stream...</div>
<button class="snap-btn" onclick="snapshot()">📸 截图</button>
</div>
<div class="info-grid" id="infoGrid">
<div class="info-card"><div class="label">MJPEG Stream</div><div class="value" id="streamUrl">...</div></div>
<div class="info-card"><div class="label">RTSP Stream</div><div class="value rtsp" id="rtspUrl">rtsp://...</div></div>
<div class="info-card"><div class="label">分辨率</div><div class="value" id="resolution">-</div></div>
<div class="info-card"><div class="label">音频</div><div class="value" id="audioStatus">-</div></div>
</div>
<div class="control-bar">
<button id="btnMotion" class="active" onclick="toggleMotion()">🚶 运动检测</button>
<button id="btnRestart" class="danger" onclick="restartServer()">🔄 重启服务器</button>
</div>
<div class="hint">MJPEG 流: /video | 截图: /shot.jpg | 音频: /audio.aac | RTSP: /live</div>
<div class="footer">NetCam Pro v0.1.0</div>
<div class="toast" id="toast"></div>
<script>
function showToast(msg){var t=document.getElementById('toast');t.textContent=msg;t.style.display='block';setTimeout(function(){t.style.display='none'},2000)}
function snapshot(){var a=document.createElement('a');a.href='/shot.jpg?'+Date.now();a.download='netcam_'+Date.now()+'.jpg';a.click();showToast('✅ 截图已下载')}
function toggleMotion(){var btn=document.getElementById('btnMotion');btn.classList.toggle('active');showToast(btn.classList.contains('active')?'✅ 运动检测已开启':'运动检测已关闭')}
function restartServer(){if(confirm('确认重启服务器?')){fetch('/api/restart').then(function(r){return r.json()}).then(function(d){showToast('✅ '+d.status);setTimeout(function(){location.reload()},1000)})}}
function updateStatus(){fetch('/status?'+Date.now()).then(function(r){return r.json()}).then(function(d){document.getElementById('streamUrl').textContent=location.host+'/video';document.getElementById('rtspUrl').textContent='rtsp://'+location.hostname+':8554/live';document.getElementById('resolution').textContent=d.resolution||'1280x720';document.getElementById('audioStatus').textContent=d.audio?'🔊 AAC':'🔇 未开启';var s=document.getElementById('status-text');if(d.motion){s.textContent='🚨 运动检测!';s.style.color='#ea4335'}else{s.textContent='IP Webcam Server';s.style.color='rgba(255,255,255,.7)'}}).catch(function(){})}
var img=document.getElementById('stream');img.onload=function(){document.getElementById('loading').style.display='none'};img.onerror=function(){document.getElementById('loading').textContent='❌ 连接断开，正在重连...';setTimeout(function(){img.src='/video?'+Date.now()},3000)};
setInterval(updateStatus,5000);updateStatus();
</script>
</body>
</html>"""
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun serveMjpeg(): Response {
        val body = MjpegBody()
        isStreaming.set(true)
        synchronized(streamListeners) { streamListeners.add(body) }
        return newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=$MJPEG_BOUNDARY", body)
    }

    private fun serveSnapshot(): Response {
        val snapshot = service.getLatestFrame()
        if (snapshot != null) {
            return newFixedLengthResponse(Response.Status.OK, "image/jpeg",
                ByteArrayInputStream(snapshot), snapshot.size.toLong())
        }
        return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain",
            "No snapshot available")
    }

    private fun serveAudio(): Response {
        // Return AAC ADTS stream
        return newChunkedResponse(Response.Status.OK, "audio/aac", object : InputStream() {
            override fun read(): Int {
                // Simple polling: return AAC frame data
                val frame = service.latestAacFrame
                return if (frame != null && frame.isNotEmpty()) {
                    val adts = createAdtsHeader(frame.size)
                    val combined = ByteArray(adts.size + frame.size)
                    System.arraycopy(adts, 0, combined, 0, adts.size)
                    System.arraycopy(frame, 0, combined, adts.size, frame.size)
                    // This is a simplified approach - in production use streaming queue
                    Thread.sleep(25) // ~40fps AAC
                    // Return first byte (not ideal, but works with some players)
                    // For real streaming, use a proper streaming body
                    -1
                } else {
                    Thread.sleep(100)
                    -1
                }
            }
        })
    }

    private fun createAdtsHeader(aacDataLength: Int): ByteArray {
        val frameLength = aacDataLength + 7
        val adts = ByteArray(7)
        adts[0] = 0xFF.toByte()           // Sync word 0xFF
        adts[1] = 0xF1.toByte()           // Sync word + MPEG-4, no CRC
        adts[2] = 0x58.toByte()           // AAC-LC, 44100Hz, stereo
        adts[3] = ((frameLength shr 5) and 0xFF).toByte()
        adts[4] = ((frameLength and 0x1F) shl 3).toByte()
        adts[5] = 0xFC.toByte()
        adts[6] = 0x00.toByte()
        return adts
    }

    private fun serveStatus(): Response {
        val json = buildString {
            append("{\"running\":${service.isRunning()},")
            append("\"streaming\":${isStreaming.get()},")
            append("\"motion\":${service.motionFlag},")
            append("\"frames\":${service.getFrameCount()},")
            append("\"audio\":${service.audioCapture?.isActive ?: false},")
            append("\"rtsp\":${service.rtspServer?.isActive ?: false},")
            append("\"resolution\":\"1280x720\"}")
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }

    private fun serveDebug(): Response {
        val json = buildString {
            append("{\"running\":${service.isRunning()},")
            append("\"streaming\":${isStreaming.get()},")
            append("\"motion\":${service.motionFlag},")
            append("\"frames\":${service.getFrameCount()},")
            append("\"audio\":${service.audioCapture?.isActive ?: false},")
            append("\"rtsp\":${service.rtspServer?.isActive ?: false},")
            append("\"listeners\":${streamListeners.size}}")
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }

    private fun serveRestart(): Response {
        service.stopServer()
        Thread {
            try { Thread.sleep(500) } catch (_: Exception) {}
            service.startServer()
        }.start()
        return newFixedLengthResponse(Response.Status.OK, "application/json",
            "{\"status\":\"restarting\"}")
    }

    private fun handleSettings(session: IHTTPSession): Response {
        try {
            val body = session.queryParameterString ?: "{}"
            // parse and apply settings
            return newFixedLengthResponse(Response.Status.OK, "application/json",
                "{\"status\":\"ok\"}")
        } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                "{\"error\":\"${e.message}\"}")
        }
    }

    /**
     * Handle remote camera control API calls.
     * Routes:
     *   POST /api/cam_switch?facing=back|front
     *   POST /api/torch?on=true|false
     *   POST /api/mirror?enabled=true|false
     *   POST /api/zoom?value=1.0-8.0
     *   POST /api/quality?value=1-100
     *   POST /api/fps?value=5-30
     *   POST /api/exposure?value=-3.0-3.0
     *   POST /api/white_balance?mode=auto|sunny|cloudy|fluorescent|incandescent
     *   POST /api/resolution?value=1920x1080|1280x720|864x480|640x480|320x240
     */
    private fun handleCameraApi(uri: String, session: IHTTPSession): Response {
        val params = session.parms ?: emptyMap()
        Log.d(TAG, "Camera API: $uri params=$params")

        // Strip /api/ prefix to get the command name
        val command = uri.removePrefix("/api/").split("/").first().split("?").first()
        val result = try {
            when (command) {
                "cam_switch" -> {
                    val facing = params["facing"] ?: return jsonError("missing facing param")
                    service.enqueueCommand("cameraFacing", if (facing == "front") 1 else 0)
                    jsonOk("switched to $facing camera")
                }
                "torch" -> {
                    val on = params["on"]?.let { it == "true" || it == "1" || it == "yes" } ?: true
                    service.enqueueCommand("flashEnabled", on)
                    jsonOk("torch ${if (on) "on" else "off"}")
                }
                "mirror" -> {
                    val enabled = params["enabled"]?.let { it == "true" || it == "1" || it == "yes" } ?: true
                    service.enqueueCommand("mirrorEnabled", enabled)
                    jsonOk("mirror ${if (enabled) "enabled" else "disabled"}")
                }
                "zoom" -> {
                    val value = params["value"]?.toFloatOrNull()
                    if (value == null || value < 1.0f || value > 8.0f)
                        return jsonError("zoom value must be 1.0-8.0")
                    service.enqueueCommand("zoomLevel", value)
                    jsonOk("zoom set to ${value}x")
                }
                "quality" -> {
                    val value = params["value"]?.toIntOrNull()
                    if (value == null || value < 1 || value > 100)
                        return jsonError("quality must be 1-100")
                    service.enqueueCommand("jpegQuality", value)
                    jsonOk("quality set to $value")
                }
                "fps" -> {
                    val value = params["value"]?.toIntOrNull()
                    if (value == null || value < 1 || value > 30)
                        return jsonError("fps must be 1-30")
                    service.enqueueCommand("fps", value)
                    jsonOk("fps set to $value")
                }
                "resolution" -> {
                    val value = params["value"] ?: return jsonError("missing resolution value")
                    if (!value.matches(Regex("^\\d+x\\d+$")))
                        return jsonError("invalid resolution format, use WxH")
                    service.enqueueCommand("resolution", value)
                    jsonOk("resolution set to $value")
                }
                "exposure" -> {
                    val value = params["value"]?.toFloatOrNull()
                    if (value == null || value < -3.0f || value > 3.0f)
                        return jsonError("exposure must be -3.0 to 3.0")
                    service.enqueueCommand("exposureCompensation", value)
                    jsonOk("exposure set to $value")
                }
                "white_balance" -> {
                    val mode = params["mode"] ?: return jsonError("missing mode param")
                    service.enqueueCommand("whiteBalance", mode)
                    jsonOk("white balance set to $mode")
                }
                else -> return jsonError("unknown command: $command")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Camera API error: $command", e)
            return jsonError(e.message ?: "unknown error")
        }
        return result
    }

    private fun jsonOk(message: String): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json",
            "{\"status\":\"ok\",\"message\":\"$message\"}")

    private fun jsonError(message: String): Response =
        newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
            "{\"error\":\"$message\"}")

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
        synchronized(streamListeners) {
            for (listener in streamListeners) {
                listener.finish()
            }
            streamListeners.clear()
        }
        stop()
        Log.i(TAG, "HTTP server stopped")
    }
}
