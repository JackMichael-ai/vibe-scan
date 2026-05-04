package com.northmark.vibescan.engine

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.getSystemService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.sqrt

/**
 * The VibeScan V4 Engine Controller.
 * Manages high-speed sensor collection and audio recording, piping them to the JNI engine.
 */
class VibeScanEngine(private val context: Context) : SensorEventListener {

    // ── JNI declarations ─────────────────────────────────────────────────────
    // MUST match extern "C" names in vibescan_engine.cpp exactly.

    private external fun nativeSetThreadPriority()
    private external fun nativePushAudio(data: ShortArray, length: Int)
    private external fun nativePushSensor(x: Float, y: Float, z: Float, tsNs: Long)
    private external fun nativePushMagnet(x: Float, y: Float, z: Float, tsNs: Long)

    private external fun nativeGetPeakSpectrum(axis: Int): FloatArray
    private external fun nativeResetPeakHold()

    /** New signature — 5 params. Old nativeAnalyse(shaftRpm) no longer exists. */
    private external fun nativeAnalyse(
        shaftRpm:      Float,
        minSearchHz:   Float,
        maxSearchHz:   Float,
        bpfoFactor:    Float,
        bpfiFactor:    Float
    ): FloatArray

    /** Returns velocity spectrum (mm/s) for the worst axis — 512 bins */
    private external fun nativeGetSpectrum(): FloatArray

    /** Returns velocity spectrum for a specific axis: 0=X 1=Y 2=Z */
    private external fun nativeGetAxisSpectrum(axisIndex: Int): FloatArray

    /** Returns audio spectrum (magnitude) from the microphone — 512 bins up to 22kHz */
    private external fun nativeGetAudioSpectrum(): FloatArray

    private external fun nativeSetMachineClass(cls: Int)
    private external fun nativeResetBaseline()
    private external fun nativeReset()

    // ── Observable state ──────────────────────────────────────────────────────

    private val _diagnosis = MutableStateFlow(Diagnosis.idle())
    val diagnosis: StateFlow<Diagnosis> = _diagnosis.asStateFlow()

    /** Velocity spectrum (mm/s) for the worst / most energetic axis */
    private val _spectrum  = MutableStateFlow(FloatArray(512))
    val spectrum: StateFlow<FloatArray> = _spectrum.asStateFlow()

    /** Peak Hold spectrum for the current axis — used for Bump Tests */
    private val _peakSpectrum = MutableStateFlow(FloatArray(512))
    val peakSpectrum: StateFlow<FloatArray> = _peakSpectrum.asStateFlow()

    /** Last 20 spectra for history/waterfall display */
    private val _spectrumHistory = MutableStateFlow<List<FloatArray>>(emptyList())
    val spectrumHistory: StateFlow<List<FloatArray>> = _spectrumHistory.asStateFlow()

    /** Current axis being viewed in detail: -1=Worst, 0=X, 1=Y, 2=Z */
    val currentAxis = MutableStateFlow(-1)

    /** Per-axis spectra — useful for waterfall / directional display */
    private val _spectrumX = MutableStateFlow(FloatArray(512))
    private val _spectrumY = MutableStateFlow(FloatArray(512))
    private val _spectrumZ = MutableStateFlow(FloatArray(512))
    val spectrumX: StateFlow<FloatArray> = _spectrumX.asStateFlow()
    val spectrumY: StateFlow<FloatArray> = _spectrumY.asStateFlow()
    val spectrumZ: StateFlow<FloatArray> = _spectrumZ.asStateFlow()

    private val _spectrumAudio = MutableStateFlow(FloatArray(512))
    val spectrumAudio: StateFlow<FloatArray> = _spectrumAudio.asStateFlow()

    /** Electromagnetic spectrum (magnetometer) — for electrical fault detection */
    private val _spectrumMag = MutableStateFlow(FloatArray(512))
    val spectrumMag: StateFlow<FloatArray> = _spectrumMag.asStateFlow()

    /** Gyroscope magnitude — high value means phone is moving (bad mounting) */
    private val _gyroMag   = MutableStateFlow(0f)
    val gyroMag: StateFlow<Float> = _gyroMag.asStateFlow()

    /** Ambient temperature °C from phone sensor (not always available) */
    private val _ambientTemp = MutableStateFlow<Float?>(null)
    val ambientTemp: StateFlow<Float?> = _ambientTemp.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    // ── Parameters ────────────────────────────────────────────────────────────

    var shaftRpm: Float = 1800f
    var powerlineHz: Float = 60f

    var machineClass: Int = 0
        set(v) { field = v; nativeSetMachineClass(v) }

    var minSearchHz: Float = 2.0f
    var maxSearchHz: Float = 500.0f

    var bpfoFactor: Float = 3.58f
    var bpfiFactor: Float = 5.42f

    // ── Internals ─────────────────────────────────────────────────────────────

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var lastAnalysisMs: Long = 0
    private var threadPrioritySet = false

    private val sensorManager by lazy { context.getSystemService<SensorManager>()!! }

    /**
     * Start the data collection and analysis loop.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        if (_isRunning.value) return
        _isRunning.value = true

        // 1. Audio Recording Thread
        recordingThread = Thread {
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * 2
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("VibeScan", "AudioRecord failed to initialize")
                return@Thread
            }

            audioRecord?.startRecording()
            val audioData = ShortArray(1024)

            while (_isRunning.value) {
                if (!threadPrioritySet) {
                    nativeSetThreadPriority()
                    threadPrioritySet = true
                }
                val read = audioRecord?.read(audioData, 0, audioData.size) ?: 0
                if (read > 0) {
                    nativePushAudio(audioData, read)
                }

                // Periodic Analysis Trigger
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastAnalysisMs >= ANALYSIS_INTERVAL_MS) {
                    analyse()
                    lastAnalysisMs = nowMs
                }
            }

            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        }.apply {
            name = "VibeScan-Audio"
            start()
        }

        // 2. Accelerometer + Gyroscope + Magnetometer
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val mag = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val temp = sensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)

        sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_FASTEST)
        sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_NORMAL)
        sensorManager.registerListener(this, mag, SensorManager.SENSOR_DELAY_FASTEST)
        if (temp != null) {
            sensorManager.registerListener(this, temp, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        _isRunning.value = false
        sensorManager.unregisterListener(this)
        try {
            recordingThread?.join(500)
        } catch (e: Exception) {
            Log.e("VibeScan", "Error joining thread: ${e.message}")
        }
        recordingThread = null
    }

    fun resetBaseline() {
        nativeResetBaseline()
        nativeResetPeakHold()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                nativePushSensor(event.values[0], event.values[1], event.values[2], event.timestamp)
            }
            Sensor.TYPE_GYROSCOPE -> {
                val gx = event.values[0]
                val gy = event.values[1]
                val gz = event.values[2]
                _gyroMag.value = sqrt(gx * gx + gy * gy + gz * gz)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                nativePushMagnet(event.values[0], event.values[1], event.values[2], event.timestamp)
            }
            Sensor.TYPE_AMBIENT_TEMPERATURE -> {
                _ambientTemp.value = event.values[0]
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun reset() {
        nativeReset()
        _diagnosis.value = Diagnosis.idle()
        _spectrum.value = FloatArray(512)
        _spectrumHistory.value = emptyList()
        _spectrumX.value = FloatArray(512)
        _spectrumY.value = FloatArray(512)
        _spectrumZ.value = FloatArray(512)
        _spectrumAudio.value = FloatArray(512)
    }

    fun focusSearchOnShaft() {
        val center = shaftRpm / 60.0f
        minSearchHz = (center * 0.8f).coerceAtLeast(1.0f)
        maxSearchHz = (center * 1.2f).coerceAtMost(500.0f)
    }

    /**
     * BPFO = (n/2) * (1 - (d/D) * cos(alpha))
     * BPFI = (n/2) * (1 + (d/D) * cos(alpha))
     */
    fun setBearingGeometry(nBalls: Int, pitchDia: Float, ballDia: Float,
                           contactAngle: Float = 0f) {
        val ratio = ballDia / pitchDia
        val cosA  = Math.cos(Math.toRadians(contactAngle.toDouble())).toFloat()
        bpfoFactor = 0.5f * nBalls * (1f - ratio * cosA)
        bpfiFactor = 0.5f * nBalls * (1f + ratio * cosA)
    }

    // ── Analysis ──────────────────────────────────────────────────────────────

    private fun analyse() {
        Log.d("VibeScan", "Loop: Starting analysis cycle...")
        val raw = nativeAnalyse(
            shaftRpm, minSearchHz, maxSearchHz, bpfoFactor, bpfiFactor
        )
        val diag = Diagnosis.fromRaw(raw)
        _diagnosis.value = diag

        // Get spectrum for selected axis (or worst axis if -1)
        val spec = if (currentAxis.value >= 0) {
            nativeGetAxisSpectrum(currentAxis.value)
        } else {
            nativeGetSpectrum()
        }
        _spectrum.value = spec

        // Update history
        val hist = _spectrumHistory.value.toMutableList()
        hist.add(spec.copyOf())
        if (hist.size > 20) hist.removeAt(0)
        _spectrumHistory.value = hist

        // Per-axis spectra for waterfall / directional display
        _spectrumX.value = nativeGetAxisSpectrum(0)
        _spectrumY.value = nativeGetAxisSpectrum(1)
        _spectrumZ.value = nativeGetAxisSpectrum(2)

        // Audio Spectrum (Up to 22kHz)
        _spectrumAudio.value = nativeGetAudioSpectrum()

        // Magnetic Spectrum (EMF)
        _spectrumMag.value = nativeGetAxisSpectrum(3)

        // Peak Hold Spectrum for the currently viewed axis
        _peakSpectrum.value = if (currentAxis.value == 3) {
            nativeGetPeakSpectrum(3) // Audio peak
        } else {
            nativeGetPeakSpectrum(currentAxis.value.coerceAtLeast(0))
        }
    }

    // ── Derived mount quality ─────────────────────────────────────────────────

    /**
     * Mount stability assessment from gyroscope magnitude.
     * Returns a human-readable warning if the phone is moving during analysis.
     * null = stable (no warning needed).
     */
    val mountWarning: String? get() {
        val g = _gyroMag.value
        return when {
            g > 1.0f -> "Phone moving — press firmly or use magnetic mount"
            g > 0.3f -> "Minor movement detected — stabilise the phone"
            else     -> null
        }
    }

    companion object {
        const val ANALYSIS_INTERVAL_MS = 1_000L
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        init {
            System.loadLibrary("vibescan_engine")
        }
    }
}
