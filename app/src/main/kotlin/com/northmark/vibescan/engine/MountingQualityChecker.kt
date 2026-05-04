package com.northmark.vibescan.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * MountingQualityChecker — Validates physical mounting before accepting a baseline.
 */
class MountingQualityChecker {

    enum class MountQuality {
        UNKNOWN,
        EXCELLENT,   // < 0.5ms jitter, high coherence
        GOOD,        // 0.5–1.5ms jitter
        ACCEPTABLE,  // 1.5–3ms jitter
        POOR,        // 3–5ms jitter
        REJECTED     // > 5ms jitter
    }

    data class MountingReport(
        val quality:          MountQuality,
        val meanJitterMs:     Float,
        val coherenceScore:   Float,
        val rmsStability:     Float,
        val recommendation:   String,
        val certifiedForBase: Boolean
    )

    private val _report = MutableStateFlow(MountingReport(
        quality          = MountQuality.UNKNOWN,
        meanJitterMs     = 0f,
        coherenceScore   = 0f,
        rmsStability     = 0f,
        recommendation   = "Run mounting test before taking baseline.",
        certifiedForBase = false
    ))
    val report: StateFlow<MountingReport> = _report.asStateFlow()

    fun evaluate(jitterMs: Float, rmsValues: FloatArray): MountingReport {
        val mean = rmsValues.average().toFloat()
        val cv   = if (mean > 0.001f) {
            val variance = rmsValues.map { (it - mean) * (it - mean) }.average().toFloat()
            kotlin.math.sqrt(variance) / mean
        } else 0f

        val coherence = (1f - cv * 5f).coerceIn(0f, 1f)

        val quality = when {
            jitterMs > 5f   -> MountQuality.REJECTED
            jitterMs > 3f   -> MountQuality.POOR
            jitterMs > 1.5f -> MountQuality.ACCEPTABLE
            jitterMs > 0.5f -> MountQuality.GOOD
            else            -> MountQuality.EXCELLENT
        }

        val recommendation = when (quality) {
            MountQuality.EXCELLENT   -> "Excellent mount. Proceed to baseline capture."
            MountQuality.GOOD        -> "Good mount. Magnetic or clamp mounting confirmed."
            MountQuality.ACCEPTABLE  -> "Acceptable for low-speed machines (<1000 RPM). Consider rigid mount for better accuracy."
            MountQuality.POOR        -> "Poor coupling detected. Re-mount using magnetic base or epoxy plate. Clean mounting surface."
            MountQuality.REJECTED    -> "Mount rejected. Phone is not rigidly coupled to machine. Cannot accept baseline — results will be unreliable."
            MountQuality.UNKNOWN     -> "Run mounting test."
        }

        val report = MountingReport(
            quality          = quality,
            meanJitterMs     = jitterMs,
            coherenceScore   = coherence,
            rmsStability     = cv,
            recommendation   = recommendation,
            certifiedForBase = quality != MountQuality.REJECTED && quality != MountQuality.POOR
        )
        _report.value = report
        return report
    }
}
