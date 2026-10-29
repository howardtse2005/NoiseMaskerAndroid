package com.howard.noisemasker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class AudioMaskerService : Service() {
    companion object {
        const val ACTION_START = "com.howard.noisemasker.action.START"
        const val ACTION_STOP = "com.howard.noisemasker.action.STOP"
        const val ACTION_UPDATE_PARAMS = "com.howard.noisemasker.action.UPDATE_PARAMS"

        const val EXTRA_DIGITAL_PREAMP = "digital_preamp"
        const val EXTRA_BASE_VOL = "base_vol"
        const val EXTRA_MAX_VOL = "max_vol"
        const val EXTRA_ATTACK_SWELL = "attack_swell"
        const val EXTRA_RELEASE_SINK = "release_sink"
        const val EXTRA_HP_CUTOFF_HZ = "hp_cutoff_hz"
        const val EXTRA_RMS_WEIGHT = "rms_weight"
        const val EXTRA_LOG_DETECTION_SCALE = "log_detection_scale"
        const val EXTRA_VOL_SCALE = "vol_scale"
        const val EXTRA_WARM_CUTOFF_MAX_HZ = "warm_cutoff_max_hz"

        private const val CHANNEL_ID = "noise_masker_channel"
        private const val CHANNEL_NAME = "Noise Masker Background"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var engine: NoiseEngine
    @Volatile private var started = false
    private var nativeSampleRate = 44100
    private var nativeFramesPerBuffer = 256

    override fun onCreate() {
        super.onCreate()
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        nativeSampleRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 44100
        nativeFramesPerBuffer = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 256

        val tunedBlock = nativeFramesPerBuffer.coerceIn(64, 256)
        engine = NoiseEngine(sampleRate = nativeSampleRate, blockSize = tunedBlock)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val params = paramsFromIntent(intent)
                engine.updateParams(params)
                if (!started) {
                    startForegroundCompat("Starting (${nativeSampleRate}Hz/${nativeFramesPerBuffer}f)...")
                    engine.start(
                        onState = { state -> updateNotification("$state") },
                        onLevel = { _, _, _, _, _ -> }
                    )
                    started = true
                }
            }
            ACTION_UPDATE_PARAMS -> {
                val params = paramsFromIntent(intent)
                engine.updateParams(params)
            }
            ACTION_STOP -> {
                stopEngineAndService()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        stopEngineAndService()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun paramsFromIntent(intent: Intent): NoiseParams {
        return NoiseParams(
            digitalPreamp = intent.getFloatExtra(EXTRA_DIGITAL_PREAMP, 120f),
            baseVol = intent.getFloatExtra(EXTRA_BASE_VOL, 0.04f),
            maxVol = intent.getFloatExtra(EXTRA_MAX_VOL, 0.25f),
            attackSwell = intent.getFloatExtra(EXTRA_ATTACK_SWELL, 0.20f),
            releaseSink = intent.getFloatExtra(EXTRA_RELEASE_SINK, 0.002f),
            hpCutoffHz = intent.getFloatExtra(EXTRA_HP_CUTOFF_HZ, 80f),
            rmsWeight = intent.getFloatExtra(EXTRA_RMS_WEIGHT, 0.5f),
            logDetectionScale = intent.getFloatExtra(EXTRA_LOG_DETECTION_SCALE, 12f),
            volScale = intent.getFloatExtra(EXTRA_VOL_SCALE, 0.20f),
            warmCutoffMaxHz = intent.getFloatExtra(EXTRA_WARM_CUTOFF_MAX_HZ, 1500f)
        )
    }

    private fun stopEngineAndService() {
        if (started) {
            engine.stop()
            started = false
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundCompat(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Noise Masker Running")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
