package com.northmark.vibescan.ui.analysis

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.northmark.vibescan.ui.main.MainActivity
import com.northmark.vibescan.ui.spectrum.EnhancedSpectrumScreen

class AnalysisFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val activity = requireActivity() as MainActivity
        val engine = activity.engine
        val guardian = activity.batteryGuardian
        
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val diagnosis by engine.diagnosis.collectAsState()
                val spectrumHistory by engine.spectrumHistory.collectAsState()
                val currentAxis by engine.currentAxis.collectAsState()
                val batteryState by guardian.state.collectAsState()
                
                // Get the appropriate spectrum based on current axis
                val spectrum by (when (currentAxis) {
                    0 -> engine.spectrumX
                    1 -> engine.spectrumY
                    2 -> engine.spectrumZ
                    3 -> engine.spectrumAudio
                    4 -> engine.spectrumMag
                    else -> engine.spectrum
                }).collectAsState()

                val peakSpectrum by engine.peakSpectrum.collectAsState()

                EnhancedSpectrumScreen(
                    analysis = diagnosis,
                    batteryState = batteryState,
                    spectrumHistory = spectrumHistory,
                    currentAxis = currentAxis,
                    onAxisChange = { engine.currentAxis.value = it },
                    shaftRpm = engine.shaftRpm,
                    bpfoFactor = engine.bpfoFactor,
                    bpfiFactor = engine.bpfiFactor,
                    currentSpectrum = spectrum,
                    peakSpectrum = peakSpectrum,
                    onResetPeakHold = { engine.resetBaseline() }, // Overloaded for now
                    onSaveClicked = {
                        activity.firebaseManager.uploadReading(
                            assetId = activity.prefs.currentAssetId,
                            assetName = "Manual Scan",
                            d = diagnosis
                        )
                    }
                )
            }
        }
    }
}
