package com.howard.noisemasker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var sourceText: TextView
    private lateinit var inputLevelText: TextView

    private var currentParams = NoiseParams(
        digitalPreamp = 120f,
        baseVol = 0.04f,
        maxVol = 0.25f,
        attackSwell = 0.20f,
        releaseSink = 0.002f,
        hpCutoffHz = 80f,
        rmsWeight = 0.5f,
        logDetectionScale = 12f,
        volScale = 0.20f,
        warmCutoffMaxHz = 1500f
    )

    private var isRunning = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            statusText.text = if (granted) "Status: permission granted" else "Status: mic permission denied"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        sourceText = findViewById(R.id.sourceText)
        inputLevelText = findViewById(R.id.inputLevelText)
        val startButton: Button = findViewById(R.id.startButton)
        val stopButton: Button = findViewById(R.id.stopButton)

        bindSeek(
            seekId = R.id.preampSeek,
            labelId = R.id.preampLabel,
            labelPrefix = "Digital Preamp",
            format = { "%.0f".format(it) },
            toValue = { it.toFloat() },
            onValue = {
                currentParams = currentParams.copy(digitalPreamp = it)
                pushParamsIfRunning()
            }
        )

        bindSeek(
            seekId = R.id.baseVolSeek,
            labelId = R.id.baseVolLabel,
            labelPrefix = "Base Vol",
            format = { "%.3f".format(it) },
            toValue = { it / 100f },
            onValue = {
                currentParams = currentParams.copy(baseVol = it, maxVol = currentParams.maxVol.coerceAtLeast(it))
                pushParamsIfRunning()
            }
        )

        bindSeek(
            seekId = R.id.maxVolSeek,
            labelId = R.id.maxVolLabel,
            labelPrefix = "Max Vol",
            format = { "%.3f".format(it) },
            toValue = { it / 100f },
            onValue = {
                currentParams = currentParams.copy(maxVol = it.coerceAtLeast(currentParams.baseVol))
                pushParamsIfRunning()
            }
        )

        bindSeek(
            seekId = R.id.attackSeek,
            labelId = R.id.attackLabel,
            labelPrefix = "Attack Swell",
            format = { "%.3f".format(it) },
            toValue = { it / 100f },
            onValue = {
                currentParams = currentParams.copy(attackSwell = it.coerceIn(0f, 1f))
                pushParamsIfRunning()
            }
        )

        bindSeek(
            seekId = R.id.releaseSeek,
            labelId = R.id.releaseLabel,
            labelPrefix = "Release Sink",
            format = { "%.3f".format(it) },
            toValue = { it / 1000f },
            onValue = {
                currentParams = currentParams.copy(releaseSink = it.coerceIn(0.0005f, 0.2f))
                pushParamsIfRunning()
            }
        )

        bindSeek(
            seekId = R.id.hpCutoffSeek,
            labelId = R.id.hpCutoffLabel,
            labelPrefix = "HP Cutoff (Hz)",
            format = { "%.0f".format(it) },
            toValue = { it.toFloat().coerceAtLeast(10f) },
            onValue = {
                currentParams = currentParams.copy(hpCutoffHz = it)
                pushParamsIfRunning()
            }
        )

        bindSeek(
            seekId = R.id.rmsWeightSeek,
            labelId = R.id.rmsWeightLabel,
            labelPrefix = "RMS Weight",
            format = { "%.2f".format(it) },
            toValue = { it / 100f },
            onValue = {
                currentParams = currentParams.copy(rmsWeight = it.coerceIn(0f, 1f))
                pushParamsIfRunning()
            }
        )

        bindSeek(
            seekId = R.id.logScaleSeek,
            labelId = R.id.logScaleLabel,
            labelPrefix = "Log Detection Scale",
            format = { "%.1f".format(it) },
            toValue = { it / 10f },
            onValue = {
                currentParams = currentParams.copy(logDetectionScale = it.coerceAtLeast(0.1f))
                pushParamsIfRunning()
            }
        )

        bindSeek(
            seekId = R.id.volScaleSeek,
            labelId = R.id.volScaleLabel,
            labelPrefix = "Volume Scale",
            format = { "%.3f".format(it) },
            toValue = { it / 100f },
            onValue = {
                currentParams = currentParams.copy(volScale = it.coerceAtLeast(0f))
                pushParamsIfRunning()
            }
        )

        bindSeek(
            seekId = R.id.warmCutoffSeek,
            labelId = R.id.warmCutoffLabel,
            labelPrefix = "Warm Cutoff Max (Hz)",
            format = { "%.0f".format(it) },
            toValue = { it.toFloat().coerceAtLeast(200f) },
            onValue = {
                currentParams = currentParams.copy(warmCutoffMaxHz = it)
                pushParamsIfRunning()
            }
        )

        startButton.setOnClickListener {
            if (!hasMicPermission()) {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return@setOnClickListener
            }

            statusText.text = "Status: starting background audio..."
            sourceText.text = "Input source: foreground service"
            inputLevelText.text = "Input p/r/d/n/v: (hidden while background)"
            val intent = Intent(this, AudioMaskerService::class.java).apply {
                action = AudioMaskerService.ACTION_START
            }
            fillParams(intent)
            ContextCompat.startForegroundService(this, intent)
            isRunning = true
        }

        stopButton.setOnClickListener {
            val intent = Intent(this, AudioMaskerService::class.java).apply {
                action = AudioMaskerService.ACTION_STOP
            }
            startService(intent)
            isRunning = false
            statusText.text = "Status: stopped"
            inputLevelText.text = "Input p/r/d/n/v: 0.000 / 0.000 / 0.000 / 0.00 / 0.000"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun pushParamsIfRunning() {
        if (!isRunning) return
        val intent = Intent(this, AudioMaskerService::class.java).apply {
            action = AudioMaskerService.ACTION_UPDATE_PARAMS
        }
        fillParams(intent)
        startService(intent)
    }

    private fun fillParams(intent: Intent) {
        intent.putExtra(AudioMaskerService.EXTRA_DIGITAL_PREAMP, currentParams.digitalPreamp)
        intent.putExtra(AudioMaskerService.EXTRA_BASE_VOL, currentParams.baseVol)
        intent.putExtra(AudioMaskerService.EXTRA_MAX_VOL, currentParams.maxVol)
        intent.putExtra(AudioMaskerService.EXTRA_ATTACK_SWELL, currentParams.attackSwell)
        intent.putExtra(AudioMaskerService.EXTRA_RELEASE_SINK, currentParams.releaseSink)
        intent.putExtra(AudioMaskerService.EXTRA_HP_CUTOFF_HZ, currentParams.hpCutoffHz)
        intent.putExtra(AudioMaskerService.EXTRA_RMS_WEIGHT, currentParams.rmsWeight)
        intent.putExtra(AudioMaskerService.EXTRA_LOG_DETECTION_SCALE, currentParams.logDetectionScale)
        intent.putExtra(AudioMaskerService.EXTRA_VOL_SCALE, currentParams.volScale)
        intent.putExtra(AudioMaskerService.EXTRA_WARM_CUTOFF_MAX_HZ, currentParams.warmCutoffMaxHz)
    }

    private fun bindSeek(
        seekId: Int,
        labelId: Int,
        labelPrefix: String,
        format: (Float) -> String,
        toValue: (Int) -> Float,
        onValue: (Float) -> Unit
    ) {
        val seek: SeekBar = findViewById(seekId)
        val label: TextView = findViewById(labelId)

        fun update(progress: Int) {
            val value = toValue(progress)
            onValue(value)
            label.text = "$labelPrefix: ${format(value)}"
        }

        update(seek.progress)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                update(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }
}
