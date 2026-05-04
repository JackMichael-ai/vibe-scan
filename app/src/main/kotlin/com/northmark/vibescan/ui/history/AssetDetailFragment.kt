package com.northmark.vibescan.ui.history

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.northmark.vibescan.databinding.FragmentAssetDetailBinding
import com.northmark.vibescan.ui.main.MainActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class AssetDetailFragment : Fragment() {
    private var _binding: FragmentAssetDetailBinding? = null
    private val binding get() = _binding!!

    private var assetId: Long = -1L
    private val dateFormat = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        assetId = arguments?.getLong("assetId") ?: -1L
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAssetDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity() as MainActivity
        val repo = activity.repo

        if (assetId == -1L) {
            findNavController().popBackStack()
            return
        }

        val asset = repo.getAssetById(assetId)
        if (asset != null) {
            binding.tvDetailTitle.text = "Trend: ${asset.name}"
        }

        val readings = repo.getRecentReadings(assetId, 50)
        setupChart(readings)
        
        binding.tvStats.text = "Data points: ${readings.size}\nLast sync: ${if(readings.isNotEmpty()) dateFormat.format(Date(readings.last().timestamp)) else "Never"}"

        binding.btnExportCsv.setOnClickListener {
            exportToCsv(readings)
        }

        binding.btnExportReport.setOnClickListener {
            generateMarkdownReport()
        }

        binding.btnStartScan.setOnClickListener {
            val bundle = Bundle().apply {
                putLong("assetId", assetId)
            }
            findNavController().navigate(com.northmark.vibescan.R.id.scanFragment, bundle)
        }

        binding.btnBack.setOnClickListener {
            findNavController().popBackStack()
        }
    }

    private fun setupChart(readings: List<com.northmark.vibescan.data.Reading>) {
        if (readings.isEmpty()) return

        val healthEntries = readings.mapIndexed { index, reading ->
            Entry(index.toFloat(), reading.health.toFloat())
        }

        val rmsEntries = readings.mapIndexed { index, reading ->
            Entry(index.toFloat(), reading.rms)
        }

        val healthSet = LineDataSet(healthEntries, "Health Score").apply {
            color = Color.parseColor("#4285F4")
            setCircleColor(Color.parseColor("#4285F4"))
            lineWidth = 2f
            circleRadius = 3f
            setDrawCircleHole(false)
            valueTextSize = 0f
            setDrawFilled(true)
            fillColor = Color.parseColor("#4285F4")
            fillAlpha = 30
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }

        val rmsSet = LineDataSet(rmsEntries, "RMS (mm/s)").apply {
            color = Color.parseColor("#FBBC04")
            setCircleColor(Color.parseColor("#FBBC04"))
            lineWidth = 2f
            circleRadius = 3f
            setDrawCircleHole(false)
            valueTextSize = 0f
            axisDependency = com.github.mikephil.charting.components.YAxis.AxisDependency.RIGHT
        }

        binding.healthChart.apply {
            data = LineData(healthSet, rmsSet)
            description.isEnabled = false
            legend.textColor = Color.WHITE
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                textColor = Color.GRAY
                setDrawGridLines(false)
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        val idx = value.toInt()
                        return if (idx >= 0 && idx < readings.size) {
                            dateFormat.format(Date(readings[idx].timestamp))
                        } else ""
                    }
                }
            }
            axisLeft.apply {
                textColor = Color.parseColor("#4285F4")
                axisMinimum = 0f
                axisMaximum = 100f
                setDrawGridLines(true)
                gridColor = Color.parseColor("#33FFFFFF")
            }
            axisRight.apply {
                isEnabled = true
                textColor = Color.parseColor("#FBBC04")
                axisMinimum = 0f
                setDrawGridLines(false)
            }
            setTouchEnabled(true)
            setScaleEnabled(true)
            invalidate()
        }
    }

    private fun exportToCsv(readings: List<com.northmark.vibescan.data.Reading>) {
        if (readings.isEmpty()) {
            Toast.makeText(context, "No data to export", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val assetName = (requireActivity() as MainActivity).repo.getAssetById(assetId)?.name ?: "Unknown"
            val fileName = "VibeScan_${assetName.replace(" ", "_")}_${System.currentTimeMillis()}.csv"
            val file = File(requireContext().cacheDir, fileName)
            file.writeText("Timestamp,Date,Health,RMS_mm_s,RMS_m_s2,Kurtosis,Crest,Dominant_Hz,ISO_Zone,BPFO_Energy,BPFI_Energy,Reliability\n")
            readings.forEach { r ->
                val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(r.timestamp))
                file.appendText("${r.timestamp},$dateStr,${r.health},${r.rms},${r.rmsMs2},${r.kurtosis},${r.crest},${r.dominantHz},${r.isoZone},${r.bpfoEnergy},${r.bpfiEnergy},${r.aiReliability}\n")
            }

            val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_SUBJECT, "VibeScan Export - $assetName")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share CSV"))
        } catch (e: Exception) {
            Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun generateMarkdownReport() {
        val activity = requireActivity() as MainActivity
        val report = com.northmark.vibescan.util.ReportGenerator.generateSiteAuditReport(
            activity.repo,
            activity.kioskManager,
            activity.batteryGuardian,
            activity.engine.diagnosis.value
        )

        try {
            val fileName = "VibeScan_Site_Audit_${System.currentTimeMillis()}.md"
            val file = File(requireContext().cacheDir, fileName)
            file.writeText(report)

            val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/markdown"
                putExtra(Intent.EXTRA_SUBJECT, "VibeScan Site Audit Report")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share Markdown Report"))
        } catch (e: Exception) {
            Toast.makeText(context, "Report generation failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
