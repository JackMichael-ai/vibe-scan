package com.northmark.vibescan.engine

/**
 * Diagnosis v4
 * Northmark Intelligence
 *
 * Immutable snapshot of one analysis cycle from the C++ engine.
 * Unpacks the float[26] JNI array — see vibescan_engine.cpp header for layout.
 *
 * Key additions vs v3:
 *  - Per-axis velocity RMS, dominant Hz, kurtosis (X/Y/Z)
 *  - worstAxis: which axis carries the most vibration energy
 *  - bpfoHarmonicRatio: h2/h1 ratio — > 1.5 confirms outer race fault
 *  - harmonicConfidence: how many 1X/2X/3X shaft harmonics are visible
 *
 * All numeric fields carry units in their name:
 *   rmsMs2   → m/s²   (raw acceleration RMS, overall magnitude)
 *   rmsMms   → mm/s   (velocity RMS — ISO 10816 standard unit)
 *   ...Hz    → Hz     (frequency, always ≥ 2 Hz or exactly 0.0 = no signal)
 */
data class Diagnosis(

    // ── Overall fields (indices 0–13, same layout as v3) ──────────────────────
    val health:             Int,     // 0–100
    val faultCode:          Int,     // 0=none 1=wear 2=BPFO 3=BPFI 4=imbalance 5=critical
    val rmsMs2:             Float,   // m/s²  overall magnitude RMS
    val rmsMms:             Float,   // mm/s  velocity RMS (ISO 10816)
    val kurtosis:           Float,   // worst-axis kurtosis (most sensitive)
    val crest:              Float,   // peak / RMS overall
    val dominantHz:         Float,   // peak frequency from worst axis (≥2Hz or 0.0)
    val bpfoEnergy:         Float,   // max BPFO band energy across axes
    val bpfiEnergy:         Float,   // max BPFI band energy across axes
    val actualSampleRate:   Float,   // Hz measured from timestamps (not assumed 200)
    val signalConfidence:   Float,   // 0–100 SNR proxy
    val baselineReady:      Boolean,
    val baselineProgress:   Int,     // 0–100 %
    val isoZone:            Char,    // 'A' 'B' 'C' 'D'

    // ── Per-axis RMS velocity (indices 14–16) ─────────────────────────────────
    val rmsMmsX:            Float,   // mm/s X-axis
    val rmsMmsY:            Float,   // mm/s Y-axis
    val rmsMmsZ:            Float,   // mm/s Z-axis

    // ── Per-axis dominant frequency (indices 17–19) ───────────────────────────
    val dominantHzX:        Float,   // Hz X-axis (0.0 = no clear signal)
    val dominantHzY:        Float,   // Hz Y-axis
    val dominantHzZ:        Float,   // Hz Z-axis

    // ── Per-axis kurtosis (indices 20–22) ─────────────────────────────────────
    val kurtosisX:          Float,
    val kurtosisY:          Float,
    val kurtosisZ:          Float,

    // ── Enhanced diagnostic fields (indices 23–25) ────────────────────────────
    /**
     * BPFO harmonic ratio = 2×BPFO band energy / 1×BPFO band energy.
     * > 1.5 → strong 2nd harmonic → confirms outer race fault (not noise spike).
     * < 1.0 → no harmonic structure → probably noise or imbalance, not BPFO.
     */
    val bpfoHarmonicRatio:  Float,

    /**
     * Index of the axis with the highest velocity RMS energy.
     * 0 = X (often radial)
     * 1 = Y (often radial, perpendicular to X)
     * 2 = Z (often axial — along shaft)
     * Axial dominance (Z) suggests misalignment.
     * Radial dominance (X or Y) suggests imbalance or bearing fault.
     */
    val worstAxis:          Int,     // 0=X 1=Y 2=Z

    /**
     * How many of the first 3 shaft harmonics (1X, 2X, 3X) are visible
     * in the spectrum. 0–100:
     *   0  = no harmonics visible → probably not machine vibration
     *   33 = only 1X visible → could be 1X noise or real imbalance
     *   67 = 1X + 2X → moderate imbalance or looseness
     *  100 = 1X + 2X + 3X → strong harmonic structure → confirmed mechanical
     */
    val harmonicConfidence: Float,   // 0–100

    // ── Mounting detection (indices 26–28) ────────────────────────────────────
    val mountingQuality:     Float,   // 0–100 %
    val isMounted:           Boolean, // True if > 45%
    val mountingStatusLevel: Int,     // 0=poor, 1=fair, 2=good, 3=excellent

    // ── Environmental & Metadata ──
    val ambientTemp:         Float? = null,

    // ── Error state ───────────────────────────────────────────────────────────
    val isError:    Boolean = false,
    val errorMsg:   String  = ""

) {

    // ── Severity ──────────────────────────────────────────────────────────────

    val severity: Severity get() = when {
        isError        -> Severity.ERROR
        !baselineReady -> Severity.LEARNING
        health >= 90   -> Severity.HEALTHY
        health >= 60   -> Severity.MONITOR
        health >= 30   -> Severity.WARNING
        else           -> Severity.CRITICAL
    }

    // ── ISO zone label ────────────────────────────────────────────────────────

    val isoZoneLabel: String get() = when (isoZone) {
        'A'  -> "ISO Zone A — Good"
        'B'  -> "ISO Zone B — Satisfactory"
        'C'  -> "ISO Zone C — Unsatisfactory"
        'D'  -> "ISO Zone D — Danger"
        else -> "Learning baseline"
    }

    // ── Worst axis label ──────────────────────────────────────────────────────

    val worstAxisLabel: String get() = when (worstAxis) {
        0    -> "X-axis (radial)"
        1    -> "Y-axis (radial)"
        2    -> "Z-axis (axial)"
        else -> "—"
    }

    /**
     * Likely fault direction based on which axis dominates.
     * Axial (Z) dominance → misalignment.
     * Radial (X/Y) dominance → imbalance or bearing fault.
     */
    val axialNote: String get() = when {
        worstAxis == 2 && rmsMmsZ > rmsMmsX * 1.5f && rmsMmsZ > rmsMmsY * 1.5f ->
            "Axial dominance — possible misalignment or thrust bearing issue"
        else -> ""
    }

    // ── Signal reliability ────────────────────────────────────────────────────

    val isSignalReliable: Boolean get() = signalConfidence >= 20f

    /**
     * Whether the BPFO fault is confirmed by harmonic structure.
     * A single BPFO band energy spike could be coincidence.
     * Harmonic ratio > 1.5 means there's also energy at 2×BPFO → real fault.
     */
    val isBpfoConfirmed: Boolean get() =
        faultCode == 2 && bpfoHarmonicRatio > 1.5f

    // ── Fault label ───────────────────────────────────────────────────────────

    val faultLabel: String get() = when {
        !isSignalReliable -> "Signal too weak — check mounting"
        else -> when (faultCode) {
            0    -> "No fault detected"
            1    -> "Bearing wear (elevated kurtosis)"
            2    -> if (isBpfoConfirmed)
                "Outer race fault — confirmed (BPFO + harmonic)"
            else
                "Outer race fault — probable (BPFO)"
            3    -> "Inner race fault (BPFI)"
            4    -> "Rotor imbalance (1× dominant)"
            5    -> "Critical — multiple faults"
            else -> "Unknown"
        }
    }

    // ── Action label ──────────────────────────────────────────────────────────

    val actionLabel: String get() = when (severity) {
        Severity.HEALTHY  ->
            "Machine healthy. $isoZoneLabel. Next inspection in 7 days."
        Severity.MONITOR  ->
            "Elevated readings. $isoZoneLabel. Inspect within 30 days."
        Severity.WARNING  ->
            "$faultLabel. $isoZoneLabel. Schedule maintenance this week."
        Severity.CRITICAL ->
            "STOP MACHINE. $isoZoneLabel. Immediate intervention required."
        Severity.LEARNING ->
            "Learning machine baseline… ${baselineProgress}% — keep machine running at normal load."
        Severity.ERROR    -> errorMsg
    }

    // ── Mounting recommendation ───────────────────────────────────────────────

    val mountingMessage: String get() = when (mountingStatusLevel) {
        0 -> "Poor mount. High jitter. Use magnetic base or epoxy stud."
        1 -> "Fair mount. Significant signal loss. Check for loose bolts."
        2 -> "Good mount. Magnetic base or clamp confirmed."
        3 -> "Excellent mount. Rigid stud mounting detected."
        else -> "Unknown mounting state."
    }

    // ── Per-axis summary for display ──────────────────────────────────────────

    /** Returns axis RMS values as a labelled triple for display in UI */
    val axisRmsSummary: List<Pair<String, Float>> get() = listOf(
        "X" to rmsMmsX,
        "Y" to rmsMmsY,
        "Z" to rmsMmsZ
    )

    val axisDominantHzSummary: List<Pair<String, Float>> get() = listOf(
        "X" to dominantHzX,
        "Y" to dominantHzY,
        "Z" to dominantHzZ
    )

    val axisKurtosisSummary: List<Pair<String, Float>> get() = listOf(
        "X" to kurtosisX,
        "Y" to kurtosisY,
        "Z" to kurtosisZ
    )

    // ── Factory ───────────────────────────────────────────────────────────────

    companion object {

        fun idle() = Diagnosis(
            health = 100, faultCode = 0,
            rmsMs2 = 0f, rmsMms = 0f,
            kurtosis = 0f, crest = 0f, dominantHz = 0f,
            bpfoEnergy = 0f, bpfiEnergy = 0f,
            actualSampleRate = 1000f, signalConfidence = 0f,
            baselineReady = false, baselineProgress = 0, isoZone = '?',
            rmsMmsX = 0f, rmsMmsY = 0f, rmsMmsZ = 0f,
            dominantHzX = 0f, dominantHzY = 0f, dominantHzZ = 0f,
            kurtosisX = 3f, kurtosisY = 3f, kurtosisZ = 3f,
            bpfoHarmonicRatio = 0f, worstAxis = 0, harmonicConfidence = 0f,
            mountingQuality = 0f, isMounted = false, mountingStatusLevel = 0
        )

        fun noSensor() = idle().copy(
            isError  = true,
            errorMsg = "No accelerometer found on this device."
        )

        /**
         * Unpack the float[29] array from nativeAnalyse().
         * Array layout is defined in vibescan_engine.cpp JNI header comment.
         * Returns idle() if the array is too short (engine not yet ready).
         */
        fun fromRaw(raw: FloatArray): Diagnosis {
            if (raw.size < 29) return idle()
            return Diagnosis(
                // 0–13: original fields
                health             = raw[0].toInt().coerceIn(0, 100),
                faultCode          = raw[1].toInt(),
                rmsMs2             = raw[2],
                rmsMms             = raw[3],
                kurtosis           = raw[4],
                crest              = raw[5],
                dominantHz         = raw[6],
                bpfoEnergy         = raw[7],
                bpfiEnergy         = raw[8],
                actualSampleRate   = raw[9],
                signalConfidence   = raw[10].coerceIn(0f, 100f),
                baselineReady      = raw[11] > 0.5f,
                baselineProgress   = raw[12].toInt().coerceIn(0, 100),
                isoZone            = when (val z = raw[13].toInt()) {
                    0 -> 'A'
                    1 -> 'B'
                    2 -> 'C'
                    3 -> 'D'
                    in 65..68 -> z.toChar()
                    else -> '?' 
                },

                // 14–16: per-axis velocity RMS
                rmsMmsX            = raw[14],
                rmsMmsY            = raw[15],
                rmsMmsZ            = raw[16],

                // 17–19: per-axis dominant Hz
                dominantHzX        = raw[17],
                dominantHzY        = raw[18],
                dominantHzZ        = raw[19],

                // 20–22: per-axis kurtosis
                kurtosisX          = raw[20],
                kurtosisY          = raw[21],
                kurtosisZ          = raw[22],

                // 23–25: enhanced diagnostics
                bpfoHarmonicRatio  = raw[23],
                worstAxis          = raw[24].toInt().coerceIn(0, 2),
                harmonicConfidence = raw[25].coerceIn(0f, 100f),

                // 26–28: mounting detection
                mountingQuality    = raw[26],
                isMounted          = raw[27] > 0.5f,
                mountingStatusLevel = raw[28].toInt()
            )
        }
    }
}

// ── Enums ─────────────────────────────────────────────────────────────────────

enum class Severity {
    HEALTHY, MONITOR, WARNING, CRITICAL, LEARNING, ERROR
}

/**
 * ISO 10816-3 Machine Class.
 * Used to select the correct velocity threshold table in C++.
 * Pass the ordinal (0–3) to engine.machineClass.
 */
enum class MachineClass(val label: String, val detail: String,
                        val zoneA: Float, val zoneB: Float, val zoneC: Float) {
    CLASS_1("Class 1", "Small motors — under 15 kW",       0.71f, 1.8f,  4.5f),
    CLASS_2("Class 2", "Medium motors — 15 to 75 kW",      1.12f, 2.8f,  7.1f),
    CLASS_3("Class 3", "Large motors > 75 kW, rigid mount",1.8f,  4.5f,  11.2f),
    CLASS_4("Class 4", "Large motors > 75 kW, flex mount",  2.8f,  7.1f,  18.0f);

    /** Returns the ISO zone letter for a given velocity reading */
    fun zone(rmsMms: Float): Char = when {
        rmsMms <= zoneA -> 'A'
        rmsMms <= zoneB -> 'B'
        rmsMms <= zoneC -> 'C'
        else            -> 'D'
    }
}

/**
 * Common bearing types with pre-calculated BPFO/BPFI factors.
 * Use engine.bpfoFactor / engine.bpfiFactor directly,
 * or call engine.setBearingGeometry() for custom specs.
 */
enum class CommonBearing(
    val label: String,
    val bpfoFactor: Float,
    val bpfiFactor: Float
) {
    BEARING_6205("6205 deep groove",    3.585f, 5.415f),
    BEARING_6305("6305 deep groove",    3.572f, 5.428f),
    BEARING_6206("6206 deep groove",    3.589f, 5.411f),
    BEARING_6306("6306 deep groove",    3.576f, 5.424f),
    BEARING_6207("6207 deep groove",    3.591f, 5.409f),
    BEARING_22205("22205 spherical",    4.862f, 7.138f),
    BEARING_NU205("NU205 cylindrical",  3.589f, 5.411f),
    GENERIC_6BALL("Generic 6-ball",     3.0f,   4.5f),
    GENERIC_8BALL("Generic 8-ball",     4.0f,   6.0f),
    GENERIC_10BALL("Generic 10-ball",   5.0f,   7.5f);

    fun applyTo(engine: VibeScanEngine) {
        engine.bpfoFactor = bpfoFactor
        engine.bpfiFactor = bpfiFactor
    }
}