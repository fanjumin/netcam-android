package com.netcam.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import java.nio.ByteBuffer

class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int = 2_000_000
) {
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var isRunning = false

    var onNalUnit: ((ByteArray) -> Unit)? = null
    var onSpsPps: ((ByteArray, ByteArray) -> Unit)? = null

    private val nalPrefix = byteArrayOf(0x00, 0x00, 0x00, 0x01)

    fun start() {
        if (isRunning) return

        val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setFloat(MediaFormat.KEY_QUALITY, 0.5f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
        }

        mediaCodec = MediaCodec.createEncoderByType("video/avc")
        mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = mediaCodec?.createInputSurface()
        mediaCodec?.start()
        isRunning = true
    }

    fun getInputSurface(): Surface? = inputSurface

    fun stop() {
        isRunning = false
        try {
            mediaCodec?.stop()
        } catch (_: Exception) {}
        try {
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null
        inputSurface = null
    }

    fun drainEncoder(endOfStream: Boolean) {
        val codec = mediaCodec ?: return
        if (endOfStream) {
            codec.signalEndOfInputStream()
        }

        val bufferInfo = MediaCodec.BufferInfo()
        var outputDone = false

        while (!outputDone) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)

            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) {
                        outputDone = true
                    }
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = codec.outputFormat
                    val csd0 = newFormat.getByteBuffer("csd-0")
                    val csd1 = newFormat.getByteBuffer("csd-1")
                    if (csd0 != null) {
                        val sps = ByteArray(csd0.remaining()).also { csd0.get(it) }
                        if (csd1 != null) {
                            val pps = ByteArray(csd1.remaining()).also { csd1.get(it) }
                            onSpsPps?.invoke(sps, pps)
                        }
                    }
                }
                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig) {
                            val bytes = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.get(bytes, 0, bufferInfo.size)
                            val nalUnits = extractNalUnits(bytes)
                            for (nal in nalUnits) {
                                onNalUnit?.invoke(nal)
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
                else -> {
                    outputDone = true
                }
            }
        }
    }

    private fun extractNalUnits(data: ByteArray): List<ByteArray> {
        val nals = mutableListOf<ByteArray>()
        var i = 0
        var start = -1

        while (i < data.size - 3) {
            if (data[i] == 0x00.toByte() && data[i + 1] == 0x00.toByte() &&
                data[i + 2] == 0x00.toByte() && data[i + 3] == 0x01.toByte()
            ) {
                if (start >= 0) {
                    val nal = data.copyOfRange(start, i)
                    nals.add(nal)
                }
                start = i + 4
                i += 4
            } else if (data[i] == 0x00.toByte() && data[i + 1] == 0x00.toByte() &&
                data[i + 2] == 0x01.toByte()
            ) {
                if (start >= 0) {
                    val nal = data.copyOfRange(start, i)
                    nals.add(nal)
                }
                start = i + 3
                i += 3
            } else {
                i++
            }
        }

        if (start >= 0 && start < data.size) {
            val nal = data.copyOfRange(start, data.size)
            if (nal.isNotEmpty()) {
                nals.add(nal)
            }
        }

        return nals
    }

    val isActive: Boolean get() = isRunning
}
