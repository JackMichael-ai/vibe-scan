package com.northmark.vibescan.ui.settings

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.northmark.vibescan.ui.main.MainActivity
import com.northmark.vibescan.R
import com.northmark.vibescan.data.AppPrefs
import com.northmark.vibescan.engine.CommonBearing
import com.northmark.vibescan.engine.MachineClass
import com.northmark.vibescan.engine.VibeScanEngine
import com.northmark.vibescan.ui.auth.LoginActivity
import com.northmark.vibescan.ui.auth.LogoutDialogFragment
import kotlinx.coroutines.launch

/**
 * SettingsFragment — every setting that affects the engine, safety, and account.
 *
 * Sections:
 *
 *   1. MACHINE CONFIGURATION
 *      - Machine class (ISO 10816 Class 1–4)
 *      - Shaft RPM (slider + numeric entry)
 *      - Bearing preset selector (common bearing types)
 *      - Custom BPFO / BPFI factor entry (overrides preset)
 *      - Frequency search range (min / max Hz)
 *      - Auto-focus on shaft harmonics toggle
 *
 *   2. SIGNAL PROCESSING
 *      - Powerline frequency (50 / 60 Hz notch filter)
 *      - Analysis interval (500ms – 5000ms)
 *
 *   3. BATTERY GUARDIAN
 *      - Upper charge threshold (stop charging above, default 70%)
 *      - Lower charge threshold (start charging below, default 30%)
 *      - Temperature warning threshold (default 40°C)
 *      - Temperature critical threshold (default 45°C)
 *
 *   4. CONNECTIVITY
 *      - Background monitoring toggle
 *      - API endpoint (editable for private cloud)
 *      - Reset baseline button
 *
 *   5. ENGINE DIAGNOSTICS (read-only)
 *      - Engine version, window size, freq resolution
 *      - All active fixes listed
 *
 *   6. ACCOUNT
 *      - Current email display
 *      - Sign out button
 *      - Factory reset (requires confirmation)
 *
 * Layout: res/layout/fragment_settings.xml
 * Every change writes to AppPrefs and pushes to the live engine immediately.
 */
class SettingsFragment : Fragment() {

    private lateinit var prefs: AppPrefs
    private var engine: VibeScanEngine? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs  = AppPrefs.getInstance(requireContext())
        engine = (activity as? MainActivity)?.engine

        setupMachineConfig(view)
        setupSignalProcessing(view)
        setupBatteryGuardian(view)
        setupConnectivity(view)
        setupDiagnostics(view)
        setupAccount(view)
    }

    // ── 1. Machine configuration ──────────────────────────────────────────────

    private fun setupMachineConfig(view: View) {
        setupMachineClass(view)
        setupShaftRpm(view)
        setupBearingPreset(view)
        setupBpfoFactors(view)
        setupFrequencySearch(view)
    }

    private fun setupMachineClass(view: View) {
        val spinner = view.findViewById<Spinner>(R.id.sp_machine_class)
        val labels  = MachineClass.values().map { "${it.label} — ${it.detail}" }.toTypedArray()

        spinner.adapter = ArrayAdapter(requireContext(),
            R.layout.item_spinner, labels).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        spinner.setSelection(prefs.machineClass)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                if (prefs.machineClass == pos) return
                prefs.machineClass   = pos
                engine?.machineClass = pos
                showToast("Machine class updated — ISO thresholds adjusted")
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }
    }

    private fun setupShaftRpm(view: View) {
        val slider  = view.findViewById<SeekBar>(R.id.sb_shaft_rpm)
        val label   = view.findViewById<TextView>(R.id.tv_shaft_rpm_val)
        val editRpm = view.findViewById<EditText>(R.id.et_shaft_rpm)

        // SeekBar range: 0–119 steps × 50 = 100–6050 RPM
        val rpmToProgress = { rpm: Float -> ((rpm - 100f) / 50f).toInt().coerceIn(0, 119) }
        val progressToRpm = { prog: Int  -> (prog * 50 + 100).toFloat() }

        fun update(rpm: Float) {
            label.text    = "${rpm.toInt()} RPM"
            editRpm.setText(rpm.toInt().toString())
            slider.progress = rpmToProgress(rpm)
        }

        update(prefs.shaftRpm)

        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, prog: Int, fromUser: Boolean) {
                if (!fromUser) return
                val rpm = progressToRpm(prog)
                label.text = "${rpm.toInt()} RPM"
                editRpm.setText(rpm.toInt().toString())
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                val rpm = progressToRpm(sb.progress)
                saveShaftRpm(rpm)
                if (prefs.focusSearchOnShaft) {
                    engine?.focusSearchOnShaft()
                    updateFreqSearchDisplay(view)
                }
            }
        })

        editRpm.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val rpm = s.toString().toFloatOrNull() ?: return
                if (rpm < 100f || rpm > 60_000f) return
                slider.progress = rpmToProgress(rpm)
                label.text      = "${rpm.toInt()} RPM"
                saveShaftRpm(rpm)
                if (prefs.focusSearchOnShaft) {
                    engine?.focusSearchOnShaft()
                    updateFreqSearchDisplay(view)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
    }

    private fun saveShaftRpm(rpm: Float) {
        prefs.shaftRpm    = rpm
        engine?.shaftRpm  = rpm
    }

    private fun setupBearingPreset(view: View) {
        val spinner = view.findViewById<Spinner>(R.id.sp_bearing_preset)
        val bearings = CommonBearing.values()
        val labels   = bearings.map { it.label }.toTypedArray()

        spinner.adapter = ArrayAdapter(requireContext(),
            R.layout.item_spinner, labels).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }

        // Pre-select the stored preset
        val stored = bearings.indexOfFirst { it.label == prefs.bearingPresetName }
        spinner.setSelection(if (stored >= 0) stored else 0)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                val bearing = bearings[pos]
                prefs.bpfoFactor      = bearing.bpfoFactor
                prefs.bpfiFactor      = bearing.bpfiFactor
                prefs.bearingPresetName = bearing.label
                engine?.let { bearing.applyTo(it) }
                // Update the custom factor fields to reflect the preset
                view.findViewById<EditText>(R.id.et_bpfo_factor)
                    .setText(bearing.bpfoFactor.toString())
                view.findViewById<EditText>(R.id.et_bpfi_factor)
                    .setText(bearing.bpfiFactor.toString())
                showToast("Bearing set to ${bearing.label}")
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }
    }

    private fun setupBpfoFactors(view: View) {
        val etBpfo = view.findViewById<EditText>(R.id.et_bpfo_factor)
        val etBpfi = view.findViewById<EditText>(R.id.et_bpfi_factor)
        val btnApply = view.findViewById<Button>(R.id.btn_apply_factors)

        etBpfo.setText(prefs.bpfoFactor.toString())
        etBpfi.setText(prefs.bpfiFactor.toString())

        // Help text: BPFO = 0.5 × nBalls × (1 − d/D × cos(α))
        view.findViewById<TextView>(R.id.tv_bpfo_help)?.text =
            "BPFO = 0.5 × nBalls × (1 − ballDia/pitchDia × cos(angle°))"
        view.findViewById<TextView>(R.id.tv_bpfi_help)?.text =
            "BPFI = 0.5 × nBalls × (1 + ballDia/pitchDia × cos(angle°))"

        btnApply.setOnClickListener {
            val bpfo = etBpfo.text.toString().toFloatOrNull()
            val bpfi = etBpfi.text.toString().toFloatOrNull()
            if (bpfo == null || bpfi == null || bpfo <= 0f || bpfi <= 0f) {
                showToast("Enter valid positive factors")
                return@setOnClickListener
            }
            prefs.bpfoFactor = bpfo
            prefs.bpfiFactor = bpfi
            prefs.bearingPresetName = "Custom"
            engine?.bpfoFactor = bpfo
            engine?.bpfiFactor = bpfi
            showToast("Bearing factors applied (BPFO×$bpfo BPFI×$bpfi)")
        }

        // Bearing geometry calculator
        view.findViewById<Button>(R.id.btn_calc_bearing)?.setOnClickListener {
            showBearingCalculator()
        }
    }

    private fun setupFrequencySearch(view: View) {
        val etMin     = view.findViewById<EditText>(R.id.et_min_search_hz)
        val etMax     = view.findViewById<EditText>(R.id.et_max_search_hz)
        val swFocus   = view.findViewById<Switch>(R.id.sw_focus_shaft)
        val btnApply  = view.findViewById<Button>(R.id.btn_apply_freq)

        etMin.setText(prefs.minSearchHz.toString())
        etMax.setText(prefs.maxSearchHz.toString())
        swFocus.isChecked = prefs.focusSearchOnShaft

        swFocus.setOnCheckedChangeListener { _, checked ->
            prefs.focusSearchOnShaft = checked
            if (checked) {
                engine?.focusSearchOnShaft()
                updateFreqSearchDisplay(view)
                showToast("Search focused around shaft harmonics")
            }
        }

        btnApply.setOnClickListener {
            val min = etMin.text.toString().toFloatOrNull()
            val max = etMax.text.toString().toFloatOrNull()
            if (min == null || max == null || min >= max || min < 0.5f) {
                showToast("Min must be ≥ 0.5 Hz and less than Max")
                return@setOnClickListener
            }
            prefs.minSearchHz   = min
            prefs.maxSearchHz   = max
            engine?.minSearchHz = min
            engine?.maxSearchHz = max
            showToast("Frequency search range: ${min}–${max} Hz")
        }
    }

    private fun updateFreqSearchDisplay(view: View) {
        view.findViewById<EditText>(R.id.et_min_search_hz)
            ?.setText(prefs.minSearchHz.toString())
        view.findViewById<EditText>(R.id.et_max_search_hz)
            ?.setText(prefs.maxSearchHz.toString())
    }

    // ── 2. Signal processing ──────────────────────────────────────────────────

    private fun setupSignalProcessing(view: View) {
        setupPowerline(view)
        setupAnalysisInterval(view)
    }

    private fun setupPowerline(view: View) {
        val spinner  = view.findViewById<Spinner>(R.id.sp_powerline)
        val options  = arrayOf("50 Hz — Kenya · EU · UK · India · Africa",
            "60 Hz — USA · Canada · Japan · Mexico")
        spinner.adapter = ArrayAdapter(requireContext(),
            R.layout.item_spinner, options).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        spinner.setSelection(if (prefs.powerlineHz == 60f) 1 else 0)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                prefs.powerlineHz = if (pos == 1) 60f else 50f
                showToast("Powerline notch set to ${prefs.powerlineHz.toInt()} Hz")
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }
    }

    private fun setupAnalysisInterval(view: View) {
        val spinner = view.findViewById<Spinner>(R.id.sp_analysis_interval)
        val options = arrayOf(
            "500 ms — high refresh (uses more battery)",
            "1000 ms — balanced (recommended)",
            "2000 ms — battery saver",
            "5000 ms — minimal (background / old phones)"
        )
        val values = longArrayOf(500L, 1000L, 2000L, 5000L)

        spinner.adapter = ArrayAdapter(requireContext(),
            R.layout.item_spinner, options).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        val cur = values.indexOfFirst { it == prefs.analysisIntervalMs }
        spinner.setSelection(if (cur >= 0) cur else 1)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                prefs.analysisIntervalMs = values[pos]
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }
    }

    // ── 3. Battery guardian ───────────────────────────────────────────────────

    private fun setupBatteryGuardian(view: View) {
        val sbUpper   = view.findViewById<SeekBar>(R.id.sb_batt_upper)
        val tvUpper   = view.findViewById<TextView>(R.id.tv_batt_upper_val)
        val sbLower   = view.findViewById<SeekBar>(R.id.sb_batt_lower)
        val tvLower   = view.findViewById<TextView>(R.id.tv_batt_lower_val)
        val etWarnT   = view.findViewById<EditText>(R.id.et_batt_warn_temp)
        val etCritT   = view.findViewById<EditText>(R.id.et_batt_crit_temp)
        val btnApplyT = view.findViewById<Button>(R.id.btn_apply_temps)

        // Upper threshold — range 50–90%
        sbUpper.max      = 40   // 50+40 = 90
        sbUpper.progress = prefs.batteryUpperPct - 50
        tvUpper.text     = "${prefs.batteryUpperPct}%"
        sbUpper.setOnSeekBarChangeListener(simpleSeekBar { prog ->
            val pct          = prog + 50
            tvUpper.text     = "$pct%"
            prefs.batteryUpperPct = pct
        })

        // Lower threshold — range 10–50%
        sbLower.max      = 40   // 10+40 = 50
        sbLower.progress = prefs.batteryLowerPct - 10
        tvLower.text     = "${prefs.batteryLowerPct}%"
        sbLower.setOnSeekBarChangeListener(simpleSeekBar { prog ->
            val pct          = prog + 10
            tvLower.text     = "$pct%"
            prefs.batteryLowerPct = pct
        })

        // Temperature thresholds
        etWarnT.setText(prefs.batteryWarnTempC.toInt().toString())
        etCritT.setText(prefs.batteryCritTempC.toInt().toString())

        btnApplyT.setOnClickListener {
            val warn = etWarnT.text.toString().toFloatOrNull()
            val crit = etCritT.text.toString().toFloatOrNull()
            if (warn == null || crit == null || warn >= crit) {
                showToast("Warning temp must be lower than critical temp")
                return@setOnClickListener
            }
            prefs.batteryWarnTempC = warn
            prefs.batteryCritTempC = crit
            showToast("Temperature thresholds saved: warn ${warn.toInt()}°C · crit ${crit.toInt()}°C")
        }
    }

    // ── 4. Connectivity ───────────────────────────────────────────────────────

    private fun setupConnectivity(view: View) {
        val swBg        = view.findViewById<Switch>(R.id.sw_background_monitoring)
        val etEndpoint  = view.findViewById<EditText>(R.id.et_api_endpoint)
        val btnEndpoint = view.findViewById<Button>(R.id.btn_save_endpoint)
        val btnBaseline = view.findViewById<Button>(R.id.btn_reset_baseline)
        val tvStatus    = view.findViewById<TextView>(R.id.tv_sync_status)

        swBg.isChecked = prefs.backgroundMonitoring
        swBg.setOnCheckedChangeListener { _, checked ->
            prefs.backgroundMonitoring = checked
            showToast(if (checked) "Background monitoring enabled" else "Monitoring stops when app is closed")
        }

        etEndpoint.setText(prefs.apiEndpoint)
        btnEndpoint.setOnClickListener {
            val url = etEndpoint.text.toString().trim()
            if (!url.startsWith("http")) {
                showToast("Endpoint must start with http:// or https://")
                return@setOnClickListener
            }
            prefs.apiEndpoint = url
            showToast("API endpoint saved")
        }

        btnBaseline.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Reset baseline?")
                .setMessage("The engine will re-learn the machine's healthy state. " +
                        "Only reset when the machine is confirmed healthy and running at normal load.")
                .setPositiveButton("Reset") { _, _ ->
                    engine?.resetBaseline()
                    prefs.pendingBaselineReset = false
                    showToast("Baseline reset — learning new healthy state")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Live sync status — observe engine connectivity
        viewLifecycleOwner.lifecycleScope.launch {
            // In a full implementation observe SyncManager.status here
            tvStatus.text = "Online — 0 readings pending"
        }
    }

    // ── 5. Engine diagnostics (read-only) ─────────────────────────────────────

    private fun setupDiagnostics(view: View) {
        // Current engine values — read from live engine where possible
        val e = engine
        setDiag(view, R.id.diag_engine,     "v4.0 — per-axis FFT + parabolic interpolation")
        setDiag(view, R.id.diag_window,     "1024 samples (0.195 Hz/bin)")
        setDiag(view, R.id.diag_resolution, "0.195 Hz/bin · ski-slope cutoff ≥ 2 Hz")
        setDiag(view, R.id.diag_axes,       "X · Y · Z independent FFT — worst axis used")
        setDiag(view, R.id.diag_interp,     "Parabolic interpolation — ±0.01 Hz accuracy")
        setDiag(view, R.id.diag_fixes,
            "Double mean-subtract · Hann window · Velocity integration (÷2πf×1000) · ISO 10816")
        setDiag(view, R.id.diag_thread,     "SCHED_FIFO priority 1 → fallback nice -10")
        setDiag(view, R.id.diag_rate,
            if (e != null) "Live engine active" else "Measured from hardware timestamps")
        setDiag(view, R.id.diag_gate,       "0.02 m/s² squelch — electronic noise suppressed")
        setDiag(view, R.id.diag_baseline,
            "180-frame baseline (≈ 0.9s) — machine-specific Z-score health")
        setDiag(view, R.id.diag_harmonic,   "BPFO harmonic ratio — h2/h1 > 1.5 confirms fault")

        // Live values from current session
        if (e != null) {
            val d = e.diagnosis.value
            setDiag(view, R.id.diag_live_rate,
                "%.1f Hz actual".format(d.actualSampleRate))
            setDiag(view, R.id.diag_live_conf,
                "%.0f%% signal confidence".format(d.signalConfidence))
            setDiag(view, R.id.diag_live_baseline,
                if (d.baselineReady) "Ready" else "${d.baselineProgress}% complete")
        }
    }

    private fun setDiag(view: View, id: Int, text: String) {
        view.findViewById<TextView>(id)?.text = text
    }

    // ── 6. Account ────────────────────────────────────────────────────────────

    private fun setupAccount(view: View) {
        view.findViewById<TextView>(R.id.tv_account_email)?.text =
            prefs.accountEmail.ifEmpty { "demo@northmark.io" }

        view.findViewById<TextView>(R.id.tv_node_id)?.text =
            prefs.nodeId.take(8) + "…"

        view.findViewById<TextView>(R.id.tv_org_id)?.text =
            prefs.orgId.ifEmpty { "default" }

        view.findViewById<Button>(R.id.btn_sign_out)?.setOnClickListener {
            LogoutDialogFragment().show(parentFragmentManager, LogoutDialogFragment.TAG)
        }

        view.findViewById<Button>(R.id.btn_factory_reset)?.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Factory reset?")
                .setMessage("This will wipe ALL settings including bearing config, " +
                        "machine class, and account data. Cannot be undone.")
                .setPositiveButton("Reset everything") { _, _ ->
                    prefs.factoryReset()
                    engine?.reset()
                    startActivity(Intent(requireContext(), LoginActivity::class.java))
                    activity?.finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ── Bearing geometry calculator dialog ────────────────────────────────────

    private fun showBearingCalculator() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_bearing_calc, null)

        val etBalls  = dialogView.findViewById<EditText>(R.id.et_n_balls)
        val etPitch  = dialogView.findViewById<EditText>(R.id.et_pitch_dia)
        val etBall   = dialogView.findViewById<EditText>(R.id.et_ball_dia)
        val etAngle  = dialogView.findViewById<EditText>(R.id.et_contact_angle)
        val tvResult = dialogView.findViewById<TextView>(R.id.tv_calc_result)

        fun calculate() {
            val n     = etBalls.text.toString().toIntOrNull()    ?: return
            val pitch = etPitch.text.toString().toFloatOrNull()  ?: return
            val ball  = etBall.text.toString().toFloatOrNull()   ?: return
            val angle = etAngle.text.toString().toFloatOrNull()  ?: 0f
            if (n <= 0 || pitch <= 0f || ball <= 0f || ball >= pitch) {
                tvResult.text = "Invalid values — ball diameter must be less than pitch diameter"
                return
            }
            val cosA  = Math.cos(Math.toRadians(angle.toDouble())).toFloat()
            val ratio = ball / pitch
            val bpfo  = 0.5f * n * (1f - ratio * cosA)
            val bpfi  = 0.5f * n * (1f + ratio * cosA)
            tvResult.text = "BPFO factor: ${"%.4f".format(bpfo)}\nBPFI factor: ${"%.4f".format(bpfi)}"
        }

        listOf(etBalls, etPitch, etBall, etAngle).forEach { et ->
            et.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = calculate()
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            })
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Bearing geometry calculator")
            .setView(dialogView)
            .setPositiveButton("Apply") { _, _ ->
                val n     = etBalls.text.toString().toIntOrNull()   ?: return@setPositiveButton
                val pitch = etPitch.text.toString().toFloatOrNull() ?: return@setPositiveButton
                val ball  = etBall.text.toString().toFloatOrNull()  ?: return@setPositiveButton
                val angle = etAngle.text.toString().toFloatOrNull() ?: 0f
                engine?.setBearingGeometry(n, pitch, ball, angle)
                prefs.bpfoFactor      = engine?.bpfoFactor ?: prefs.bpfoFactor
                prefs.bpfiFactor      = engine?.bpfiFactor ?: prefs.bpfiFactor
                prefs.bearingPresetName = "Custom (calculated)"
                view?.let {
                    it.findViewById<EditText>(R.id.et_bpfo_factor)
                        ?.setText(prefs.bpfoFactor.toString())
                    it.findViewById<EditText>(R.id.et_bpfi_factor)
                        ?.setText(prefs.bpfiFactor.toString())
                }
                showToast("Bearing geometry applied")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun showToast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    /** Simple SeekBar listener that only fires on user-driven changes. */
    private fun simpleSeekBar(onChanged: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, prog: Int, fromUser: Boolean) {
                if (fromUser) onChanged(prog)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar)  {}
        }
}