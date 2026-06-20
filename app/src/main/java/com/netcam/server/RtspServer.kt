package com.netcam.server

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class RtspServer(private val port: Int = 8554) {

    companion object {
        private const val TAG = "RtspServer"
        private const val VERSION = "RTSP/1.0"
        private const val SERVER_NAME = "NetCam Pro/1.0"
        private const val CSEQ_HEADER = "CSeq"
        private const val SESSION_TIMEOUT = 30000L
    }

    private var serverSocket: ServerSocket? = null
    private var isRunning = AtomicBoolean(false)
    private val sessionId = AtomicInteger(0)

    // RTP state
    private var rtpInterleaved = false
    private var rtpChannelVideo = 0
    private var rtpChannelAudio = 2
    private var rtcpChannelVideo = 1
    private var rtcpChannelAudio = 3
    private var clientSocket: Socket? = null
    private var clientOutput: OutputStream? = null

    // Current session info
    private var currentSessionId = ""
    private var transportHeader = ""

    // Stream data callbacks
    var onClientConnected: ((Boolean) -> Unit)? = null
    private var hasClient = AtomicBoolean(false)

    private var serverThread: Thread? = null
    private var sendThread: Thread? = null

    // SPS/PPS for SDP
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    // Queue for outgoing RTP data
    private val outgoingQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()

    fun setSpsPps(spsData: ByteArray?, ppsData: ByteArray?) {
        sps = spsData
        pps = ppsData
    }

    fun start() {
        if (isRunning.get()) return

        isRunning.set(true)
        serverThread = Thread({
            runServer()
        }, "rtsp-server")
        serverThread?.start()
    }

    private fun runServer() {
        try {
            serverSocket = ServerSocket(port)
            Log.i(TAG, "RTSP server listening on port $port")

            while (isRunning.get()) {
                try {
                    val socket = serverSocket?.accept() ?: break
                    handleClient(socket)
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.e(TAG, "Accept error", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Server error", e)
        }
    }

    private fun handleClient(socket: Socket) {
        Log.i(TAG, "RTSP client connected: ${socket.inetAddress}")
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val output = socket.getOutputStream()

        clientSocket = socket
        clientOutput = output

        try {
            var request: String?
            var contentLength = 0

            while (isRunning.get() && !socket.isClosed) {
                // Read request line
                request = reader.readLine() ?: break
                if (request.isEmpty()) continue

                val parts = request.split(" ")
                if (parts.size < 2) continue

                val method = parts[0]
                val url = parts[1]

                // Read headers
                val headers = mutableMapOf<String, String>()
                contentLength = 0

                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        val key = line.substring(0, colon).trim()
                        val value = line.substring(colon + 1).trim()
                        headers[key] = value
                        if (key.equals("Content-Length", ignoreCase = true)) {
                            contentLength = value.toIntOrNull() ?: 0
                        }
                    }
                }

                // Read body if present
                if (contentLength > 0) {
                    val body = CharArray(contentLength)
                    reader.read(body, 0, contentLength)
                }

                val cseq = headers[CSEQ_HEADER] ?: "1"

                when (method.uppercase()) {
                    "OPTIONS" -> handleOptions(output, cseq)
                    "DESCRIBE" -> handleDescribe(output, cseq, url)
                    "SETUP" -> handleSetup(output, cseq, headers, url)
                    "PLAY" -> handlePlay(output, cseq)
                    "TEARDOWN" -> handleTeardown(output, cseq)
                    else -> sendResponse(output, "501", "Not Implemented", cseq)
                }

                // After PLAY, break to handle streaming
                if (method.uppercase() == "PLAY") {
                    break
                }
                if (method.uppercase() == "TEARDOWN") {
                    break
                }
            }

            // Start sending RTP data if we have a play session
            if (isRunning.get() && hasClient.get() && !socket.isClosed) {
                sendRtpData(output)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Client handler error", e)
        } finally {
            hasClient.set(false)
            onClientConnected?.invoke(false)
            try { socket.close() } catch (_: Exception) {}
            clientSocket = null
            clientOutput = null
            rtpInterleaved = false
        }
    }

    private fun handleOptions(output: OutputStream, cseq: String) {
        val response = buildString {
            append("$VERSION 200 OK\r\n")
            append("$CSEQ_HEADER: $cseq\r\n")
            append("Server: $SERVER_NAME\r\n")
            append("Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN\r\n")
            append("\r\n")
        }
        sendRaw(output, response.toByteArray())
    }

    private fun handleDescribe(output: OutputStream, cseq: String, url: String) {
        val spsBase64 = if (sps != null) {
            android.util.Base64.encodeToString(sps, android.util.Base64.NO_WRAP)
        } else {
            "J0KEgNo=" // Placeholder SPS
        }
        val ppsBase64 = if (pps != null) {
            android.util.Base64.encodeToString(pps, android.util.Base64.NO_WRAP)
        } else {
            "qBqWcg==" // Placeholder PPS
        }

        val sdp = buildString {
            appendLine("v=0")
            appendLine("o=- ${System.currentTimeMillis()} 1 IN IP4 0.0.0.0")
            appendLine("s=NetCam Pro Stream")
            appendLine("i=Live H.264 + AAC Stream")
            appendLine("c=IN IP4 0.0.0.0")
            appendLine("t=0 0")
            appendLine("a=range:npt=0-")
            appendLine("a=control:*")
            appendLine("m=video 0 RTP/AVP 96")
            appendLine("a=rtpmap:96 H264/90000")
            appendLine("a=fmtp:96 packetization-mode=1;profile-level-id=42E01E;sprop-parameter-sets=$spsBase64,$ppsBase64")
            appendLine("a=control:trackID=0")
            appendLine("m=audio 0 RTP/AVP 97")
            appendLine("a=rtpmap:97 MPEG4-GENERIC/44100/1")
            appendLine("a=fmtp:97 streamtype=5;profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=1208")
            appendLine("a=control:trackID=1")
        }

        val sdpBytes = sdp.toByteArray()

        val response = buildString {
            append("$VERSION 200 OK\r\n")
            append("$CSEQ_HEADER: $cseq\r\n")
            append("Server: $SERVER_NAME\r\n")
            append("Content-Type: application/sdp\r\n")
            append("Content-Base: $url\r\n")
            append("Content-Length: ${sdpBytes.size}\r\n")
            append("\r\n")
        }

        sendRaw(output, response.toByteArray())
        sendRaw(output, sdpBytes)
    }

    private fun handleSetup(output: OutputStream, cseq: String, headers: Map<String, String>, url: String) {
        transportHeader = headers["Transport"] ?: "RTP/AVP/TCP;unicast"
        currentSessionId = sessionId.incrementAndGet().toString()

        // Parse transport for interleaved
        if (transportHeader.contains("interleaved")) {
            rtpInterleaved = true
            val interleavedMatch = Regex("interleaved=(\\d+)-(\\d+)").find(transportHeader)
            if (interleavedMatch != null) {
                rtpChannelVideo = interleavedMatch.groupValues[1].toIntOrNull() ?: 0
                rtcpChannelVideo = interleavedMatch.groupValues[2].toIntOrNull() ?: 1
            }
        }

        val transport = if (rtpInterleaved) {
            "RTP/AVP/TCP;unicast;interleaved=$rtpChannelVideo-$rtcpChannelVideo;ssrc=${System.currentTimeMillis()}"
        } else {
            "RTP/AVP/UDP;unicast;client_port=0-0;server_port=0-0;ssrc=${System.currentTimeMillis()}"
        }

        val response = buildString {
            append("$VERSION 200 OK\r\n")
            append("$CSEQ_HEADER: $cseq\r\n")
            append("Server: $SERVER_NAME\r\n")
            append("Session: $currentSessionId\r\n")
            append("Transport: $transport\r\n")
            append("\r\n")
        }
        sendRaw(output, response.toByteArray())
    }

    private fun handlePlay(output: OutputStream, cseq: String) {
        val response = buildString {
            append("$VERSION 200 OK\r\n")
            append("$CSEQ_HEADER: $cseq\r\n")
            append("Server: $SERVER_NAME\r\n")
            append("Session: $currentSessionId\r\n")
            append("Range: npt=0.000-\r\n")
            append("RTP-Info: url=trackID=0;seq=0;rtptime=0\r\n")
            append("\r\n")
        }
        sendRaw(output, response.toByteArray())
        hasClient.set(true)
        onClientConnected?.invoke(true)
    }

    private fun handleTeardown(output: OutputStream, cseq: String) {
        val response = buildString {
            append("$VERSION 200 OK\r\n")
            append("$CSEQ_HEADER: $cseq\r\n")
            append("Server: $SERVER_NAME\r\n")
            append("Session: $currentSessionId\r\n")
            append("\r\n")
        }
        sendRaw(output, response.toByteArray())
        hasClient.set(false)
        onClientConnected?.invoke(false)
    }

    private fun sendResponse(output: OutputStream, code: String, reason: String, cseq: String) {
        val response = "$VERSION $code $reason\r\n$CSEQ_HEADER: $cseq\r\n\r\n"
        sendRaw(output, response.toByteArray())
    }

    private fun sendRaw(output: OutputStream, data: ByteArray) {
        try {
            output.write(data)
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Send error", e)
        }
    }

    // RTP packetization and sending
    private fun sendRtpData(output: OutputStream) {
        Log.i(TAG, "Starting RTP data send loop")
        sendThread = Thread({
            try {
                while (isRunning.get() && hasClient.get() && !clientSocket?.isClosed!!) {
                    val data = outgoingQueue.poll(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (data != null) {
                        try {
                            output.write(data)
                            output.flush()
                        } catch (e: Exception) {
                            Log.e(TAG, "RTP send error", e)
                            break
                        }
                    }
                }
            } catch (_: java.lang.InterruptedException) {
            } catch (e: Exception) {
                Log.e(TAG, "RTP send loop error", e)
            }
            Log.i(TAG, "RTP send loop ended")
            hasClient.set(false)
            onClientConnected?.invoke(false)
        }, "rtp-send")
        sendThread?.start()
    }

    fun queueH264Nal(nalUnit: ByteArray) {
        if (!hasClient.get() || !rtpInterleaved) return

        val nalType = nalUnit[0].toInt() and 0x1F

        // Don't send SPS/PPS as separate video data (they're in SDP)
        if (nalType == 7 || nalType == 8) return

        val packets = packetizeH264(nalUnit, rtpChannelVideo)
        for (packet in packets) {
            outgoingQueue.offer(packet)
        }
    }

    fun queueAacFrame(aacData: ByteArray) {
        if (!hasClient.get() || !rtpInterleaved) return

        val packets = packetizeAac(aacData, rtpChannelAudio)
        for (packet in packets) {
            outgoingQueue.offer(packet)
        }
    }

    private var videoSequenceNumber = 0
    private var audioSequenceNumber = 0
    private var videoTimestamp = 0L
    private var audioTimestamp = 0L
    private val MTU = 1400

    // RTP header: 12 bytes
    // For TCP interleaved: $[channel][2-byte length][RTP header][payload]
    private fun createRtpTcpHeader(channel: Int, rtpPacket: ByteArray): ByteArray {
        val header = ByteArray(4 + rtpPacket.size)
        header[0] = 0x24.toByte() // $
        header[1] = channel.toByte()
        header[2] = ((rtpPacket.size shr 8) and 0xFF).toByte()
        header[3] = (rtpPacket.size and 0xFF).toByte()
        System.arraycopy(rtpPacket, 0, header, 4, rtpPacket.size)
        return header
    }

    private fun createRtpHeader(sequenceNumber: Int, timestamp: Long, isMarker: Boolean, payloadType: Int): ByteArray {
        val header = ByteArray(12)
        header[0] = 0x80.toByte() // V=2, P=0, X=0, CC=0
        header[1] = (payloadType or (if (isMarker) 0x80 else 0x00)).toByte()
        header[2] = ((sequenceNumber shr 8) and 0xFF).toByte()
        header[3] = (sequenceNumber and 0xFF).toByte()
        header[4] = ((timestamp shr 24) and 0xFF).toByte()
        header[5] = ((timestamp shr 16) and 0xFF).toByte()
        header[6] = ((timestamp shr 8) and 0xFF).toByte()
        header[7] = (timestamp and 0xFF).toByte()
        val ssrc = 0x12345678
        header[8] = ((ssrc shr 24) and 0xFF).toByte()
        header[9] = ((ssrc shr 16) and 0xFF).toByte()
        header[10] = ((ssrc shr 8) and 0xFF).toByte()
        header[11] = (ssrc and 0xFF).toByte()
        return header
    }

    private fun packetizeH264(nalUnit: ByteArray, channel: Int): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        val ts = videoTimestamp
        videoTimestamp += 3000 // 1/30s in 90kHz units

        if (nalUnit.size <= MTU - 12) {
            // Single NAL unit
            val seq = videoSequenceNumber++
            val rtpHeader = createRtpHeader(seq, ts, true, 96)
            val rtpPacket = rtpHeader + nalUnit
            packets.add(createRtpTcpHeader(channel, rtpPacket))
        } else {
            // FU-A fragmentation
            val nalHeader = nalUnit[0]
            val nalType = nalHeader.toInt() and 0x1F
            val data = nalUnit.copyOfRange(1, nalUnit.size)

            val fuIndicator = (0x1C).toByte() // FU-A type
            val startBit = 0x80
            val endBit = 0x40

            var offset = 0
            var seq = videoSequenceNumber

            while (offset < data.size) {
                val isStart = offset == 0
                val remaining = data.size - offset
                val fragmentSize = minOf(remaining, MTU - 12 - 2) // 2 bytes for FU indicator + FU header
                val isEnd = offset + fragmentSize >= data.size

                val fuHeader = when {
                    isStart && isEnd -> (nalType or startBit or endBit).toByte()
                    isStart -> (nalType or startBit).toByte()
                    isEnd -> (nalType or endBit).toByte()
                    else -> nalType.toByte()
                }

                val rtpHeader = createRtpHeader(seq, ts, isEnd, 96)
                val payloadSize = 2 + fragmentSize
                val rtpPacket = ByteArray(12 + payloadSize)
                System.arraycopy(rtpHeader, 0, rtpPacket, 0, 12)
                rtpPacket[12] = fuIndicator
                rtpPacket[13] = fuHeader
                System.arraycopy(data, offset, rtpPacket, 14, fragmentSize)

                packets.add(createRtpTcpHeader(channel, rtpPacket))

                seq = videoSequenceNumber++
                offset += fragmentSize
            }
        }

        return packets
    }

    private fun packetizeAac(aacData: ByteArray, channel: Int): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        val ts = audioTimestamp
        audioTimestamp += 1024 // 1024 samples per AAC frame at 44100Hz

        // AAC hbr RTP payload format
        val seq = audioSequenceNumber++

        // AU-header: 2 bytes
        val auHeaderLength = 2
        val accessUnit = aacData.size
        val auHeader = ByteArray(auHeaderLength)

        // AU-header-length (13 bits) + AU-header (variable)
        // Actually for MPEG4-GENERIC with mode=AAC-hbr:
        // AU-headers-length (16 bits) = size of AU-headers in bits
        // Then AU-header per frame: 2 bytes (13 bits size, 3 bits index)
        val aacFrameLength = aacData.size
        auHeader[0] = ((aacFrameLength shr 5) and 0xFF).toByte()
        auHeader[1] = ((aacFrameLength and 0x1F) shl 3).toByte()

        val rtpHeader = createRtpHeader(seq, ts, true, 97)
        val payloadSize = 2 + 2 + aacData.size  // AU-headers-length (2) + AU-header (2) + data
        val rtpPacket = ByteArray(12 + payloadSize)
        System.arraycopy(rtpHeader, 0, rtpPacket, 0, 12)

        // AU-headers-length in bits (16 bits)
        val auHeadersLengthBits = auHeaderLength * 8 // 16 bits
        rtpPacket[12] = ((auHeadersLengthBits shr 8) and 0xFF).toByte()
        rtpPacket[13] = (auHeadersLengthBits and 0xFF).toByte()
        // AU-header
        rtpPacket[14] = auHeader[0]
        rtpPacket[15] = auHeader[1]
        // AAC data
        System.arraycopy(aacData, 0, rtpPacket, 16, aacData.size)

        packets.add(createRtpTcpHeader(channel, rtpPacket))
        return packets
    }

    fun stop() {
        isRunning.set(false)
        hasClient.set(false)

        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null

        try { clientSocket?.close() } catch (_: Exception) {}
        clientSocket = null

        outgoingQueue.clear()
        sps = null
        pps = null
        videoSequenceNumber = 0
        audioSequenceNumber = 0
        videoTimestamp = 0L
        audioTimestamp = 0L
        Log.i(TAG, "RTSP server stopped")
    }

    val hasRtspClient: Boolean get() = hasClient.get()
    val isActive: Boolean get() = isRunning.get()
}
