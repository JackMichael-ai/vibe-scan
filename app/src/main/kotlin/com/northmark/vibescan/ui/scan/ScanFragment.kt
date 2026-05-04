package com.northmark.vibescan.ui.scan

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.northmark.vibescan.R
import com.northmark.vibescan.databinding.FragmentScanBinding
import com.northmark.vibescan.engine.Diagnosis
import com.northmark.vibescan.engine.Severity
import com.northmark.vibescan.ui.main.MainActivity
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ScanFragment : Fragment() {

    private var _binding: FragmentScanBinding? = null
    private val binding get() = _binding!!

    private lateinit var engine: com.northmark.vibescan.engine.VibeScanEngine
    private var assetId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        assetId = arguments?.getLong("assetId") ?: -1L
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity() as MainActivity
        engine = activity.engine

        // Ensure engine is synced with latest prefs when coming to this screen
        engine.machineClass = activity.prefs.machineClass
        engine.powerlineHz = activity.prefs.powerlineHz

        setupRpmSlider()
        setupResetButton()
        setupAnalysisLink()
        setupAssetHeader()
        observeEngine()
    }

    private fun setupAssetHeader() {
        if (assetId != -1L) {
            val activity = requireActivity() as MainActivity
            val asset = activity.repo.getAssetById(assetId)
            if (asset != null) {
                // If we have a layout element for the asset name, update it here.
                // For now, let's just make sure the engine uses the correct RPM if it's a known asset.
                engine.shaftRpm = asset.shaftRpm
                binding.rpmSlider.value = asset.shaftRpm
                binding.rpmLabel.text = "${asset.shaftRpm.toInt()} RPM"
                
                // Set the current asset in the activity so readings can be saved
                activity.currentAssetId = assetId

                // Pre-populate trend view with historical data
                val history = activity.repo.getRecentReadings(assetId, 60)
                history.forEach { binding.trendView.addHealthPoint(it.health) }
            }
        } else {
            (requireActivity() as MainActivity).currentAssetId = -1L
        }
    }

    private fun setupAnalysisLink() {
        binding.spectrumView.setOnClickListener {
            findNavController().navigate(R.id.action_scanFragment_to_analysisFragment)
        }
    }

    private fun setupRpmSlider() {
        binding.rpmSlider.apply {
            valueFrom = 300f
            valueTo   = 6000f
            value     = engine.shaftRpm
            stepSize  = 50f
        }
        binding.rpmLabel.text = "${engine.shaftRpm.toInt()} RPM"

        binding.rpmSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                engine.shaftRpm = value
                binding.rpmLabel.text = "${value.toInt()} RPM"
                // Persist RPM immediately so monitoring service can pick it up
                (requireActivity() as MainActivity).prefs.shaftRpm = value
                
                // If it's a known asset, maybe update the DB record too?
                // For now, let's keep it simple.
            }
        }
    }

    private fun setupResetButton() {
        binding.btnReset.setOnClickListener {
            engine.resetBaseline()
            val pulse = AnimationUtils.loadAnimation(requireContext(), R.anim.pulse)
            binding.healthRing.startAnimation(pulse)
        }
    }

    private fun observeEngine() {
        val activity = requireActivity() as MainActivity
        viewLifecycleOwner.lifecycleScope.launch {
            combine(engine.diagnosis, engine.spectrum, activity.batteryGuardian.state) { diag, spec, batt ->
                // Inject temperature into diagnosis for recording
                if (batt.tempCelsius > -100f) {
                    diag.copy(ambientTemp = batt.tempCelsius)
                } else {
                    diag
                } to spec to batt
            }.collect { (dbb, batt) ->
                val (diag, spec) = dbb
                Log.d("VibeScan-UI", "Observed: health=${diag.health}, mounted=${diag.isMounted}, baseline=${diag.baselineProgress}%")
                updateHealthRing(diag)
                updateMetrics(diag)
                updateStatus(diag)
                updateMounting(diag)
                binding.spectrumView.setSpectrum(spec, engine.shaftRpm, diag.actualSampleRate)
                binding.trendView.addHealthPoint(diag.health)
                updateBaselineBar(diag)
                updateBattery(batt)
            }
        }
    }

    private fun updateBattery(b: com.northmark.vibescan.service.BatteryGuardian.BatteryState) {
        if (b.level < 0) return
        binding.batteryIndicator.setBattery(b.level, b.isCharging, b.tempCelsius)
    }

    private fun updateMounting(d: Diagnosis) {
        if (!d.isMounted) {
            binding.isoBadge.setZone("!", ContextCompat.getColor(requireContext(), R.color.vibe_red), d.mountingMessage)
        }
    }

    private fun updateHealthRing(d: Diagnosis) {
        binding.healthRing.setHealth(d.health)
        binding.hNum.text = "${d.health}"

        val color = when (d.severity) {
            Severity.HEALTHY  -> ContextCompat.getColor(requireContext(), R.color.vibe_green)
            Severity.MONITOR  -> ContextCompat.getColor(requireContext(), R.color.vibe_amber)
            Severity.WARNING  -> ContextCompat.getColor(requireContext(), R.color.vibe_coral)
            Severity.CRITICAL -> ContextCompat.getColor(requireContext(), R.color.vibe_red)
            Severity.LEARNING -> ContextCompat.getColor(requireContext(), R.color.vibe_purple)
            Severity.ERROR    -> ContextCompat.getColor(requireContext(), R.color.vibe_muted)
        }
        binding.hNum.setTextColor(color)
        binding.healthRing.setRingColor(color)
    }

    private fun updateMetrics(d: Diagnosis) {
        val color = when (d.severity) {
            Severity.HEALTHY  -> ContextCompat.getColor(requireContext(), R.color.vibe_green)
            Severity.MONITOR  -> ContextCompat.getColor(requireContext(), R.color.vibe_amber)
            Severity.WARNING  -> ContextCompat.getColor(requireContext(), R.color.vibe_coral)
            Severity.CRITICAL -> ContextCompat.getColor(requireContext(), R.color.vibe_red)
            else -> ContextCompat.getColor(requireContext(), R.color.vibe_text)
        }

        binding.mcVel.setMetric("VELOCITY", "%.2f".format(d.rmsMms), "mm/s", color)
        binding.mcAcc.setMetric("ACCEL", "%.3f".format(d.rmsMs2), "m/s²", color)
        binding.mcKurt.setMetric("KURTOSIS", "%.1f".format(d.kurtosis), "", color)
        binding.mcCrest.setMetric("CREST", "%.1f".format(d.crest), "pk/RMS", color)
        binding.mcHz.setMetric("PEAK HZ", "%.1f".format(d.dominantHz), "Hz", if (d.dominantHz > 0) color else ContextCompat.getColor(requireContext(), R.color.vibe_muted))

        binding.mcVel.addHistoryPoint(d.rmsMms)
        binding.mcAcc.addHistoryPoint(d.rmsMs2)
        binding.mcKurt.addHistoryPoint(d.kurtosis)
        binding.mcCrest.addHistoryPoint(d.crest)
        binding.mcHz.addHistoryPoint(d.dominantHz)

        binding.pbConf.progress = d.signalConfidence.toInt()
        binding.bvConf.text = "${d.signalConfidence.toInt()}%"
        
        binding.pbRate.progress = ((d.actualSampleRate - 50) / 150 * 100).toInt().coerceIn(0, 100)
        binding.bvRate.text = "${d.actualSampleRate.toInt()}Hz (In: 1000Hz)"
        
        binding.pbBpfo.progress = d.bpfoEnergy.toInt().coerceIn(0, 100)
        binding.bvBpfo.text = "${d.bpfoEnergy.toInt()}%"

        // Reliability Filtering (Professional Data Quality)
        if (d.signalConfidence < 10.0f) {
            binding.mcCrest.setMetric("CREST", "--", "JITTER", ContextCompat.getColor(requireContext(), R.color.vibe_muted))
        }
    }

    private fun updateStatus(d: Diagnosis) {
        val zoneText = if (!d.baselineReady) "?" else d.isoZone.toString()
        val zoneColor = when (zoneText) {
            "A" -> ContextCompat.getColor(requireContext(), R.color.vibe_green)
            "B" -> ContextCompat.getColor(requireContext(), R.color.vibe_amber)
            "C" -> ContextCompat.getColor(requireContext(), R.color.vibe_coral)
            "D" -> ContextCompat.getColor(requireContext(), R.color.vibe_red)
            else -> ContextCompat.getColor(requireContext(), R.color.vibe_muted)
        }
        
        binding.isoBadge.setZone(zoneText, zoneColor, d.isoZoneLabel)

        val pCol = if (d.signalConfidence >= 60) R.color.vibe_green else if (d.signalConfidence >= 30) R.color.vibe_amber else R.color.vibe_red
        binding.plConf.text = "Confidence: ${d.signalConfidence.toInt()}%"
        binding.plConf.setTextColor(ContextCompat.getColor(requireContext(), pCol))
        
        binding.plRate.text = "Rate: ${d.actualSampleRate.toInt()}Hz"
        binding.plHz.text = "Peak Hz: %.1fHz".format(d.dominantHz)
        binding.plIso2.text = "ISO Zone $zoneText"
        binding.plIso2.setTextColor(zoneColor)
    }

    private fun updateBaselineBar(d: Diagnosis) {
        if (d.baselineReady) {
            binding.baselineGroup.visibility = View.GONE
        } else {
            binding.baselineGroup.visibility = View.VISIBLE
            binding.baselineProgress.progress = d.baselineProgress
            binding.baselineLabel.text =
                "Learning baseline… ${d.baselineProgress}%"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
