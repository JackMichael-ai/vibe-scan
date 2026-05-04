package com.northmark.vibescan.ui.history

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.northmark.vibescan.R
import com.northmark.vibescan.data.AppPrefs
import com.northmark.vibescan.data.AlertRecord
import com.northmark.vibescan.data.Asset
import com.northmark.vibescan.data.AssetRepository
import com.northmark.vibescan.databinding.FragmentHistoryBinding
import com.northmark.vibescan.ui.main.MainActivity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryFragment : Fragment() {
    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private lateinit var repo: AssetRepository
    private lateinit var prefs: AppPrefs
    private val dateFormat = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity() as MainActivity
        repo = activity.repo
        prefs = activity.prefs

        refreshUI()
        setupAddAsset()
    }

    private fun setupAddAsset() {
        binding.btnAddAsset.setOnClickListener {
            val ctx = requireContext()
            val layout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 24, 48, 24)
            }
            val nameInput = EditText(ctx).apply { hint = "Asset Name (e.g. Pump 4)" }
            val rpmInput = EditText(ctx).apply { 
                hint = "Shaft RPM" 
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
            layout.addView(nameInput)
            layout.addView(rpmInput)

            AlertDialog.Builder(ctx)
                .setTitle("Add New Asset")
                .setView(layout)
                .setPositiveButton("Add") { _, _ ->
                    val name = nameInput.text.toString()
                    val rpm = rpmInput.text.toString().toFloatOrNull() ?: 1500f
                    if (name.isNotEmpty()) {
                        repo.addAsset(name, "Motor", rpm)
                        refreshUI()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun refreshUI() {
        val assets = repo.getAllAssets()
        val alerts = repo.getUnresolvedAlerts()
        
        updateKPIs(assets, alerts)
        loadAssetList(assets)
        loadAlerts(alerts)
    }

    private fun updateKPIs(assets: List<Asset>, alerts: List<AlertRecord>) {
        val avgHealth = if (assets.isEmpty()) 0 else {
            assets.map { repo.getRecentReadings(it.id, 1).firstOrNull()?.health ?: 100 }.average().toInt()
        }
        
        binding.kpiFleetHealth.text = "$avgHealth"
        binding.kpiFleetHealth.setTextColor(when {
            avgHealth >= 85 -> ContextCompat.getColor(requireContext(), R.color.vibe_green)
            avgHealth >= 70 -> ContextCompat.getColor(requireContext(), R.color.vibe_amber)
            else -> ContextCompat.getColor(requireContext(), R.color.vibe_red)
        })
        binding.kpiFleetHealthSub.text = "avg · ${assets.size} assets"

        val critCount = alerts.count { it.severity.contains("CRITICAL", ignoreCase = true) }
        val warnCount = alerts.size - critCount
        binding.kpiActiveAlerts.text = "${alerts.size}"
        binding.kpiActiveAlertsSub.text = "$critCount crit · $warnCount warn"

        binding.kpiNodesOnline.text = "${assets.size}/${assets.size}"
        binding.kpiNodesOnlineSub.text = "all responding"
    }

    private fun loadAlerts(alerts: List<AlertRecord>) {
        binding.historyAlertsContainer.removeAllViews()

        if (alerts.isEmpty()) {
            binding.historyAlertEmpty.visibility = View.VISIBLE
        } else {
            binding.historyAlertEmpty.visibility = View.GONE
            alerts.forEach { a ->
                val view = LayoutInflater.from(requireContext()).inflate(android.R.layout.simple_list_item_2, binding.historyAlertsContainer, false)
                val text1 = view.findViewById<TextView>(android.R.id.text1)
                val text2 = view.findViewById<TextView>(android.R.id.text2)

                text1.text = "${a.assetName}: ${a.faultLabel}"
                text1.setTextColor(ContextCompat.getColor(requireContext(), R.color.vibe_red))
                text1.typeface = android.graphics.Typeface.MONOSPACE
                
                text2.text = dateFormat.format(Date(a.timestamp))
                text2.setTextColor(ContextCompat.getColor(requireContext(), R.color.vibe_muted))
                text2.textSize = 10f

                view.setPadding(0, 16, 0, 16)
                view.setOnClickListener {
                    repo.resolveAlert(a.id)
                    refreshUI()
                }
                binding.historyAlertsContainer.addView(view)
            }
        }
    }

    private fun loadAssetList(assets: List<Asset>) {
        binding.historyAssetsContainer.removeAllViews()
        if (assets.isEmpty()) {
            binding.historyAssetEmpty.visibility = View.VISIBLE
        } else {
            binding.historyAssetEmpty.visibility = View.GONE
            assets.forEach { asset ->
                val view = LayoutInflater.from(requireContext()).inflate(android.R.layout.simple_list_item_2, binding.historyAssetsContainer, false)
                val text1 = view.findViewById<TextView>(android.R.id.text1)
                val text2 = view.findViewById<TextView>(android.R.id.text2)

                val lastReading = repo.getRecentReadings(asset.id, 1).firstOrNull()
                val health = lastReading?.health ?: 100
                
                text1.text = asset.name
                text1.setTextColor(ContextCompat.getColor(requireContext(), R.color.vibe_blue))
                text1.typeface = android.graphics.Typeface.DEFAULT_BOLD
                
                text2.text = "${asset.type} • Health: $health% • ${asset.shaftRpm.toInt()} RPM"
                text2.setTextColor(ContextCompat.getColor(requireContext(), R.color.vibe_muted))
                
                view.setPadding(0, 16, 0, 16)
                view.setOnClickListener {
                    val bundle = Bundle().apply {
                        putLong("assetId", asset.id)
                    }
                    findNavController().navigate(R.id.action_historyFragment_to_assetDetailFragment, bundle)
                }
                binding.historyAssetsContainer.addView(view)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
