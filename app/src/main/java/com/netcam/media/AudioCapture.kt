package com.netcam.media

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import java.nio.ByteBuffer

class AudioCapture(
    private val sampleRate: Int = 44100,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
) {
    private var audioRecord: AudioRecord? = null
    private var mediaCodec: MediaCodec? = null
    private var isRunning = false
    private var encodeThread: Thread? = null

    var onAacFrame: ((ByteArray) -> Unit)? = null

    fun start() {
        if (isRunning) return

        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (bufferSize <= 0) return

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize * 4
        )

        val format = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize * 4)
        }

        mediaCodec = MediaCodec.createEncoderByType("audio/mp4a-latm")
        mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        mediaCodec?.start()
        isRunning = true

        audioRecord?.startRecording()

        encodeThread = Thread({ encodeLoop() }, "audio-encode")
        encodeThread?.start()
    }

    private fun encodeLoop() {
        val codec = mediaCodec ?: return
        val record = audioRecord ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        val inputBufferSize = 4096
        val pcmBuffer = ByteArray(inputBufferSize)

        try {
            while (isRunning && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val bytesRead = record.read(pcmBuffer, 0, pcmBuffer.size)
                if (bytesRead <= 0) continue

                val inputBufferIndex = codec.dequeueInputBuffer(10_000L)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                    if (inputBuffer != null) {
                        inputBuffer.clear()
                        inputBuffer.put(pcmBuffer, 0, bytesRead)
                        codec.queueInputBuffer(inputBufferIndex, 0, bytesRead,
                            System.nanoTime() / 1000, 0)
                    }
                }

                var outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                while (outputIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig) {
                            val frame = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.get(frame, 0, bufferInfo.size)
                            onAacFrame?.invoke(frame)
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                }
            }
        } catch (_: Exception) {
        } finally {
            drainRemaining(codec)
        }
    }

    private fun drainRemaining(codec: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            var outputIndex = codec.dequeueOutputBuffer(bufferInfo, 100L)
            while (outputIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputIndex)
                if (outputBuffer != null && bufferInfo.size > 0) {
                    val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig) {
                        val frame = ByteArray(bufferInfo.size)
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.get(frame, 0, bufferInfo.size)
                        onAacFrame?.invoke(frame)
                    }
                }
                codec.releaseOutputBuffer(outputIndex, false)
                outputIndex = codec.dequeueOutputBuffer(bufferInfo, 100L)
            }
        } catch (_: Exception) {}
    }

    fun stop() {
        isRunning = false
        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        try {
            mediaCodec?.stop()
        } catch (_: Exception) {}
        try {
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null

        encodeThread?.join(500)
        encodeThread = null
    }

    val isActive: Boolean get() = isRunning
}
