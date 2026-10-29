package com.howard.noisemasker

import android.media.AudioAttributes
import android.os.Build
import android.os.Process
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

data class NoiseParams(
    val digitalPreamp: Float,
    val baseVol: Float,
    val maxVol: Float,
    val attackSwell: Float,
    val releaseSink: Float,
    val hpCutoffHz: Float,
    val rmsWeight: Float,
    val logDetectionScale: Float,
    val volScale: Float,
    val warmCutoffMaxHz: Float
)

class NoiseEngine(
    private val sampleRate: Int = 44100,
    private val blockSize: Int = 256
) {
    @Volatile var digitalPreamp: Float = 120f
    @Volatile var baseVol: Float = 0.04f
    @Volatile var maxVol: Float = 0.25f
    @Volatile var attackSwell: Float = 0.20f
    @Volatile var releaseSink: Float = 0.002f
    @Volatile var hpCutoffHz: Float = 80f
    @Volatile var rmsWeight: Float = 0.5f
    @Volatile var logDetectionScale: Float = 12f
    @Volatile var volScale: Float = 0.20f
    @Volatile var warmCutoffMaxHz: Float = 1500f

    @Volatile private var running = false

    private var smoothVol = baseVol
    private var hpPrevX = 0f
    private var hpPrevY = 0f
    private var warmPrevY1 = 0f
    private var warmPrevY2 = 0f
    private var detFloor = 0.02f
    private var detCeil = 0.25f

    private var worker: Thread? = null

    fun updateParams(params: NoiseParams) {
        digitalPreamp = params.digitalPreamp
        baseVol = params.baseVol
        maxVol = params.maxVol.coerceAtLeast(baseVol)
        attackSwell = params.attackSwell.coerceIn(0f, 1f)
        releaseSink = params.releaseSink.coerceIn(0.0005f, 0.2f)
        hpCutoffHz = params.hpCutoffHz.coerceIn(10f, 1000f)
        rmsWeight = params.rmsWeight.coerceIn(0f, 1f)
        logDetectionScale = params.logDetectionScale.coerceAtLeast(0.1f)
        volScale = params.volScale.coerceAtLeast(0f)
        warmCutoffMaxHz = params.warmCutoffMaxHz.coerceAtLeast(200f)
    }

    fun start(onState: (String) -> Unit, onLevel: (Float, Float, Float, Float, Float) -> Unit) {
        if (running) return
        running = true

        worker = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            val recordMin = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val trackMin = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            if (recordMin <= 0 || trackMin <= 0) {
                onState("Audio init failed: invalid min buffer size")
                running = false
                return@Thread
            }

            val requested = blockSize * 2
            val minDevice = max(recordMin, trackMin)
            val ioBytes = max(requested, min(minDevice, blockSize * 6))
            val readBuffer = ShortArray(blockSize)
            val writeBuffer = ShortArray(blockSize)
            val random = Random(System.currentTimeMillis())

            val sourceCandidates = mutableListOf<Int>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                sourceCandidates.add(MediaRecorder.AudioSource.UNPROCESSED)
            }
            sourceCandidates.addAll(
                listOf(
                    MediaRecorder.AudioSource.VOICE_PERFORMANCE,
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.CAMCORDER
                )
            )

            var usedSource = -1
            var audioRecord: AudioRecord? = null
            for (source in sourceCandidates) {
                val candidate = AudioRecord(
                    source,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    ioBytes
                )

                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    audioRecord = candidate
                    usedSource = source
                    break
                }
                candidate.release()
            }

            if (audioRecord == null) {
                onState("Audio init failed: could not open microphone")
                running = false
                return@Thread
            }

            val audioTrack = createAudioTrack(ioBytes)

            try {
                disableCaptureEffects(audioRecord.audioSessionId)
                audioRecord.startRecording()
                if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    onState("Mic failed to start recording")
                    running = false
                    return@Thread
                }
                audioTrack.play()
                onState("Running (source: ${sourceName(usedSource)})")

                while (running) {
                    val n = audioRecord.read(readBuffer, 0, blockSize, AudioRecord.READ_BLOCKING)
                    if (n <= 0) continue

                    var peak = 0f
                    var sumSq = 0.0
                    val hpAlpha = computeHpAlpha(hpCutoffHz)

                    for (i in 0 until n) {
                        val x = readBuffer[i] / 32768f
                        val cleaned = x - hpPrevX + hpAlpha * hpPrevY
                        hpPrevX = x
                        hpPrevY = cleaned

                        val absV = kotlin.math.abs(cleaned)
                        if (absV > peak) peak = absV
                        sumSq += (cleaned * cleaned).toDouble()
                    }

                    val rms = sqrt((sumSq / n).toFloat())
                    val boostedPeak = peak * digitalPreamp
                    val boostedRms = rms * digitalPreamp
                    val transientBoost = (boostedPeak - boostedRms).coerceAtLeast(0f) * 0.35f
                    val detection = max(boostedPeak, boostedRms * rmsWeight) + transientBoost

                    // Adaptive normalization prevents always-on saturation on different phones/mics.
                    val floorAlpha = 0.06f
                    val ceilAlpha = 0.10f
                    detFloor = (detFloor * (1f - floorAlpha)) + (detection * floorAlpha)
                    if (detection > detCeil) {
                        detCeil = (detCeil * (1f - ceilAlpha)) + (detection * ceilAlpha)
                    } else {
                        detCeil = (detCeil * 0.995f) + (detection * 0.005f)
                    }
                    detCeil = max(detCeil, detFloor + 0.01f)

                    val normalizedDetection =
                        ((detection - detFloor * 1.1f) / (detCeil - detFloor + 1e-4f)).coerceIn(0f, 1f)

                    // Reverse-exponential response in normalized space for audible, dynamic changes.
                    val curveK = max(logDetectionScale / 4f, 0.1f)
                    val curve = 1f - exp(-curveK * normalizedDetection)
                    val targetVol = min(baseVol + curve * volScale, maxVol)

                    val fastAttack = max(attackSwell, 0.45f)
                    smoothVol = if (targetVol > smoothVol) {
                        smoothVol * (1f - fastAttack) + targetVol * fastAttack
                    } else {
                        smoothVol * (1f - releaseSink) + targetVol * releaseSink
                    }

                    onLevel(peak, rms, detection, normalizedDetection, smoothVol)

                    // Keep spectral color more stable to avoid "worse quality" timbre pumping.
                    val warmCutoff = min(1000f, warmCutoffMaxHz)
                    val warmAlpha = min(warmCutoff / sampleRate, 0.25f)

                    for (i in 0 until n) {
                        val white = random.nextFloat() * 2f - 1f
                        val warm1 = warmAlpha * white + (1f - warmAlpha) * warmPrevY1
                        warmPrevY1 = warm1
                        val warm2 = warmAlpha * warm1 + (1f - warmAlpha) * warmPrevY2
                        warmPrevY2 = warm2
                        val warm = (0.7f * warm1) + (0.3f * warm2)

                        val out = (warm * smoothVol).coerceIn(-1f, 1f)
                        writeBuffer[i] = (out * Short.MAX_VALUE).toInt().toShort()
                    }

                    // Blocking write avoids dropped samples that can sound rough/low quality.
                    audioTrack.write(writeBuffer, 0, n)
                }
            } catch (t: Throwable) {
                onState("Error: ${t.message ?: "unknown"}")
            } finally {
                try {
                    audioRecord.stop()
                } catch (_: Throwable) {
                }
                try {
                    audioTrack.stop()
                } catch (_: Throwable) {
                }
                audioRecord.release()
                audioTrack.release()
                onState("Stopped")
                running = false
            }
        }

        worker?.start()
    }

    fun stop() {
        running = false
        worker?.join(800)
        worker = null
    }

    private fun computeHpAlpha(cutoffHz: Float): Float {
        val capped = cutoffHz.coerceIn(10f, 1000f)
        return (1f - (2f * Math.PI.toFloat() * capped / sampleRate)).coerceIn(0f, 0.999f)
    }

    private fun sourceName(source: Int): String {
        return when (source) {
            MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.VOICE_PERFORMANCE -> "VOICE_PERFORMANCE"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
            else -> "UNKNOWN"
        }
    }

    private fun createAudioTrack(ioBytes: Int): AudioTrack {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(ioBytes)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } else {
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                ioBytes,
                AudioTrack.MODE_STREAM
            )
        }
    }

    private fun disableCaptureEffects(sessionId: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sessionId)?.enabled = false
            }
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.enabled = false
            }
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(sessionId)?.enabled = false
            }
        } catch (_: Throwable) {
            // Some devices reject effect control; ignore and continue.
        }
    }
}
