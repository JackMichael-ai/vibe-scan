package com.northmark.vibescan.ui.spectrum

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.northmark.vibescan.engine.Diagnosis
import com.northmark.vibescan.service.BatteryGuardian
import kotlin.math.*

@Composable
fun EnhancedSpectrumScreen(
    analysis: Diagnosis,
    batteryState: BatteryGuardian.BatteryState,
    spectrumHistory: List<FloatArray>,
    currentAxis: Int,
    onAxisChange: (Int) -> Unit,
    shaftRpm: Float = 0f,
    bpfoFactor: Float = 0f,
    bpfiFactor: Float = 0f,
    currentSpectrum: FloatArray = FloatArray(512),
    peakSpectrum: FloatArray = FloatArray(512),
    onResetPeakHold: () -> Unit = {},
    onSaveClicked: () -> Unit
) {
    var viewMode by remember { mutableIntStateOf(0) } // 0 = Spectrum, 1 = Waterfall
    var maxFreqView by remember { mutableFloatStateOf(500f) } // Default zoom to 500Hz for mechanical
    var isLogScale by remember { mutableStateOf(false) }
    val spectrum = currentSpectrum
    
    // Nyquist varies between vibration (accelerometer) and audio (mic)
    val nyquist = if (currentAxis == 3) 22050f else (analysis.actualSampleRate / 2f)

    // Reset maxFreqView when switching to/from audio mode
    LaunchedEffect(currentAxis) {
        if (currentAxis == 3) {
            maxFreqView = 22050f
        } else if (maxFreqView > 2000f) {
            maxFreqView = 500f
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A)) // Dark industrial
            .verticalScroll(rememberScrollState())
    ) {
        // ... (Battery and Mounting banners remain same)
        // ... (Battery and Mounting banners remain same)
        // 0. Battery Warning Banner
        if (batteryState.warning.isNotEmpty()) {
            BatteryWarningBanner(batteryState)
        }

        // 1. Mounting Banner
        MountingStatusBanner(analysis)

        // 2. Header with Health & Axis Selector
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1F1F))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Health: ${analysis.health}%",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            analysis.health > 80 -> Color(0xFF00C853)
                            analysis.health > 60 -> Color(0xFFFFC107)
                            else -> Color(0xFFF44336)
                        }
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "ISO Zone: ${analysis.isoZone}",
                        fontSize = 18.sp,
                        color = Color.LightGray
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Axis Selector
                Text("View Axis", style = MaterialTheme.typography.titleSmall, color = Color.Gray)
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item { AxisChip("Overall", -1, currentAxis, onAxisChange) }
                    item { AxisChip("X", 0, currentAxis, onAxisChange) }
                    item { AxisChip("Y", 1, currentAxis, onAxisChange) }
                    item { AxisChip("Z", 2, currentAxis, onAxisChange) }
                    item { AxisChip("Audio (Mic)", 3, currentAxis, onAxisChange) }
                    item { AxisChip("EMF (Mag)", 4, currentAxis, onAxisChange) }
                }

                Spacer(Modifier.height(12.dp))

                // View Mode Toggle
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TabRow(
                        selectedTabIndex = viewMode,
                        modifier = Modifier.weight(1f),
                        containerColor = Color.Transparent,
                        contentColor = Color.Cyan,
                        divider = {}
                    ) {
                        Tab(selected = viewMode == 0, onClick = { viewMode = 0 }) {
                            Text("Live Spectrum", modifier = Modifier.padding(8.dp))
                        }
                        Tab(selected = viewMode == 1, onClick = { viewMode = 1 }) {
                            Text("Waterfall", modifier = Modifier.padding(8.dp))
                        }
                    }
                    
                    // Freq Range Selector
                    IconButton(onClick = { 
                        maxFreqView = if (maxFreqView < 1000f) nyquist else 500f 
                    }) {
                        Icon(
                            imageVector = Icons.Default.FilterList, 
                            contentDescription = "Toggle Range",
                            tint = if (maxFreqView < nyquist) Color.Cyan else Color.Gray
                        )
                    }

                    // Log Scale Toggle
                    IconButton(onClick = { isLogScale = !isLogScale }) {
                        Icon(
                            imageVector = if (isLogScale) Icons.Default.AutoGraph else Icons.Default.BarChart,
                            contentDescription = "Toggle Log Scale",
                            tint = if (isLogScale) Color.Cyan else Color.Gray
                        )
                    }
                }
            }
        }

        // 3. Main Live Spectrum or Waterfall
        val labelText = if (viewMode == 0) {
            if (currentAxis == 3) "Acoustic Spectrum (0-22k Hz)"
            else "Velocity Spectrum (0-${maxFreqView.toInt()} Hz)"
        } else {
            "Waterfall History (last 20 readings)"
        }
        
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = labelText,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
            if (currentAxis == 3) {
                Text(
                    text = "Note: Phone microphones typically roll off above 8kHz. Not a calibrated acoustic instrument.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }

        if (viewMode == 0) {
            Box(modifier = Modifier.fillMaxWidth().height(280.dp).padding(horizontal = 16.dp)) {
                SpectrumCanvas(
                    spectrum = spectrum,
                    dominantHz = analysis.dominantHz,
                    shaftHz = shaftRpm / 60f,
                    nyquist = nyquist,
                    maxFreqView = maxFreqView,
                    isLogScale = isLogScale,
                    modifier = Modifier.fillMaxSize()
                )
                
                // Overlay Peak Hold for Bump Tests
                SpectrumCanvas(
                    spectrum = peakSpectrum,
                    dominantHz = -1f,
                    shaftHz = 0f,
                    nyquist = nyquist,
                    maxFreqView = maxFreqView,
                    isLogScale = isLogScale,
                    color = Color.Red.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxSize()
                )
                
                // Peak Hold Label & Reset
                Column(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    Text("PEAK HOLD", color = Color.Red, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onResetPeakHold, contentPadding = PaddingValues(0.dp)) {
                        Text("Reset", color = Color.White, fontSize = 10.sp)
                    }
                }
            }
        } else {
            WaterfallSpectrogram(
                history = spectrumHistory,
                nyquist = nyquist,
                maxFreqView = maxFreqView,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .padding(horizontal = 16.dp)
            )
        }

        // 4. Key Metrics Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val label = if (currentAxis == 3) "Mic Level" else "RMS"
            val unit = if (currentAxis == 3) "dBfs" else "mm/s"
            
            MetricCard(label, "${analysis.rmsMms.format(if (currentAxis == 3) 1 else 4)} $unit", Color.Cyan)
            MetricCard("Peak Freq", "${analysis.dominantHz.format(0)} Hz", Color.Magenta)
            MetricCard("Kurtosis", analysis.kurtosis.format(1), Color.Yellow)
        }

        // 5. Significant Frequencies List
        SignificantFrequenciesList(
            analysis = analysis,
            shaftHz = shaftRpm / 60f,
            bpfoFactor = bpfoFactor,
            bpfiFactor = bpfiFactor
        )

        // 6. Scrolling History (Mini Spectra)
        if (spectrumHistory.isNotEmpty()) {
            Text(
                text = "Recent Spectra (${spectrumHistory.size} frames)",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = Color.Gray
            )

            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(spectrumHistory.reversed()) { index, histSpectrum ->
                    MiniSpectrum(
                        spectrum = histSpectrum,
                        label = "${index}s",
                        modifier = Modifier.width(68.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // 7. Action Buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onSaveClicked,
                enabled = analysis.isMounted && analysis.mountingQuality >= 40f,
                modifier = Modifier.weight(1f)
            ) {
                Text("Upload to Firebase")
            }

            OutlinedButton(
                onClick = { /* Reset baseline or export */ },
                modifier = Modifier.weight(1f)
            ) {
                Text("Reset Baseline")
            }
        }

        Spacer(Modifier.height(80.dp)) // Bottom padding
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AxisChip(label: String, axis: Int, selected: Int, onSelect: (Int) -> Unit) {
    val isSelected = axis == selected
    FilterChip(
        selected = isSelected,
        onClick = { onSelect(axis) },
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Color(0xFF2196F3),
            selectedLabelColor = Color.White
        )
    )
}

@Composable
private fun SpectrumCanvas(
    spectrum: FloatArray,
    dominantHz: Float,
    shaftHz: Float,
    nyquist: Float,
    maxFreqView: Float,
    isLogScale: Boolean,
    color: Color? = null,
    modifier: Modifier = Modifier
) {
    val maxAmp = remember(spectrum) { spectrum.maxOrNull()?.coerceAtLeast(0.01f) ?: 1f }
    val binCount = spectrum.size
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = modifier
        .background(if (color == null) Color(0xFF0F0F0F) else Color.Transparent)
        .border(if (color == null) 1.dp else 0.dp, Color(0xFF333333))
    ) {
        val width = size.width
        val height = size.height
        
        // Only draw up to maxFreqView
        val maxBinToShow = ((maxFreqView / nyquist) * binCount).toInt().coerceIn(1, binCount)
        val binWidth = width / maxBinToShow

        // Grid
        if (color == null) {
            drawEnhancedGrid(width, height, maxFreqView, maxAmp, isLogScale, textMeasurer)
        }

        // Spectrum bars
        for (i in 1 until maxBinToShow) {  
            val amp = spectrum[i]
            val normalized = if (isLogScale) {
                (ln(amp.coerceAtLeast(0.001f) * 1000f) / ln(maxAmp * 1000f)).coerceIn(0f, 1f)
            } else {
                (amp / maxAmp).coerceIn(0f, 1f)
            }
            
            val barHeight = normalized * height * 0.90f
            val x = i * binWidth

            val barColor = color ?: when {
                amp > maxAmp * 0.8f -> Color(0xFFFF5252)
                amp > maxAmp * 0.5f -> Color(0xFFFFC107)
                else -> Color(0xFF00E5FF).copy(alpha = 0.8f)
            }

            drawRect(
                color = barColor,
                topLeft = Offset(x, height - barHeight),
                size = Size((binWidth * 0.9f).coerceAtLeast(1f), barHeight)
            )
        }
        
        // ... rest of marker logic

        // Dominant frequency vertical line + label
        if (dominantHz > 2f && dominantHz <= maxFreqView) {
            val x = (dominantHz / maxFreqView) * width
            drawLine(
                color = Color.White,
                start = Offset(x, 0f),
                end = Offset(x, height),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
            )
            
            drawText(
                textMeasurer = textMeasurer,
                text = "${dominantHz.format(1)}Hz",
                style = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold),
                topLeft = Offset((x + 6f).coerceAtMost(width - 60f), 10f)
            )
        }

        // Shaft 1X marker
        if (shaftHz > 1f && shaftHz <= maxFreqView) {
            val x1x = (shaftHz / maxFreqView) * width
            drawLine(
                color = Color.Yellow.copy(alpha = 0.6f),
                start = Offset(x1x, 0f),
                end = Offset(x1x, height),
                strokeWidth = 1.5f
            )
            
            drawText(
                textMeasurer = textMeasurer,
                text = "1X",
                style = TextStyle(color = Color.Yellow, fontSize = 10.sp),
                topLeft = Offset(x1x - 14f, height - 26f)
            )
        }
    }
}

@Composable
private fun WaterfallSpectrogram(
    history: List<FloatArray>,
    nyquist: Float,
    maxFreqView: Float,
    modifier: Modifier = Modifier
) {
    if (history.isEmpty()) {
        Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            Text("Waiting for data...", color = Color.Gray)
        }
        return
    }

    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = modifier
        .background(Color.Black)
        .border(1.dp, Color(0xFF333333))
    ) {
        val w = size.width
        val h = size.height
        val rowHeight = h / 20f // Fixed to last 20 frames
        
        val fullBinCount = history.first().size
        val maxBinToShow = ((maxFreqView / nyquist) * fullBinCount).toInt().coerceIn(1, fullBinCount)
        val binWidth = w / maxBinToShow

        // Draw background grid for frequency
        for (i in 1..4) {
            val x = w * i / 4
            val hz = maxFreqView * i / 4
            drawLine(Color(0xFF222222), Offset(x, 0f), Offset(x, h), 1f)
            drawText(
                textMeasurer = textMeasurer,
                text = "${hz.toInt()}Hz",
                style = TextStyle(color = Color.DarkGray, fontSize = 9.sp),
                topLeft = Offset(x - 20f, h - 18f)
            )
        }

        history.takeLast(20).forEachIndexed { rowIndex, spectrum ->
            val y = h - (rowIndex + 1) * rowHeight
            val maxInRow = spectrum.maxOrNull()?.coerceAtLeast(0.1f) ?: 1f
            
            for (binIndex in 0 until maxBinToShow) {
                val amp = spectrum[binIndex]
                if (amp < 0.005f) continue 
                
                val normalized = (amp / maxInRow).coerceIn(0f, 1f)
                val color = getWaterfallColor(normalized)
                
                drawRect(
                    color = color,
                    topLeft = Offset(binIndex * binWidth, y),
                    size = Size(binWidth + 0.5f, rowHeight + 0.5f)
                )
            }
        }
    }
}

private fun DrawScope.drawEnhancedGrid(
    width: Float, height: Float, 
    maxFreq: Float, maxAmp: Float,
    isLogScale: Boolean,
    textMeasurer: androidx.compose.ui.text.TextMeasurer
) {
    val gridColor = Color(0xFF222222)
    val labelStyle = TextStyle(color = Color(0xFF555555), fontSize = 9.sp)

    // Vertical Frequency lines
    for (i in 0..5) {
        val x = width * i / 5
        val hz = maxFreq * i / 5
        drawLine(gridColor, Offset(x, 0f), Offset(x, height), 1f)
        if (i > 0) {
            drawText(
                textMeasurer = textMeasurer,
                text = "${hz.toInt()}",
                style = labelStyle,
                topLeft = Offset(x - 15f, height - 16f)
            )
        }
    }

    // Horizontal Amplitude lines
    for (i in 1..4) {
        val y = height * i / 5
        val normalized = (5 - i) / 5f
        val ampLabel = if (isLogScale) {
            "dB" // Simplified for log
        } else {
            "%.1f".format(maxAmp * normalized)
        }
        
        drawLine(gridColor, Offset(0f, y), Offset(width, y), 1f)
        drawText(
            textMeasurer = textMeasurer,
            text = ampLabel,
            style = labelStyle,
            topLeft = Offset(4f, y - 14f)
        )
    }
}

private fun getWaterfallColor(normalized: Float): Color {
    return when {
        normalized > 0.8f -> Color(0xFFFF5252) // Red
        normalized > 0.5f -> Color(0xFFFFC107) // Yellow
        normalized > 0.2f -> Color(0xFF00C853) // Green
        else -> Color(0xFF00E5FF).copy(alpha = normalized * 2f) // Fade blue
    }
}

private fun DrawScope.drawGrid(width: Float, height: Float) {
    val gridColor = Color(0xFF333333)
    for (i in 1..9) {
        val y = height * i / 10
        drawLine(gridColor, Offset(0f, y), Offset(width, y), 1f)
    }
    for (i in 1..8) {
        val x = width * i / 8
        drawLine(gridColor, Offset(x, 0f), Offset(x, height), 1f)
    }
}

@Composable
private fun MiniSpectrum(
    spectrum: FloatArray,
    label: String,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier
        .background(Color(0xFF1A1A1A))
        .border(1.dp, Color.DarkGray)
    ) {
        val max = spectrum.maxOrNull() ?: 1f
        val w = size.width / spectrum.size

        for (i in spectrum.indices step 8) {  // downsample for mini view
            val h = (spectrum[i] / max * size.height * 0.85f).coerceAtLeast(2f)
            drawRect(
                color = Color(0xFF00BCD4),
                topLeft = Offset(i * w, size.height - h),
                size = Size(w * 0.8f, h)
            )
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, accent: Color) {
    Card(
        modifier = Modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1F1F))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, color = Color.Gray, fontSize = 12.sp)
            Text(value, color = accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SignificantFrequenciesList(
    analysis: Diagnosis,
    shaftHz: Float,
    bpfoFactor: Float,
    bpfiFactor: Float
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1F1F))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Significant Frequencies",
                style = MaterialTheme.typography.titleSmall,
                color = Color.Gray
            )
            Spacer(Modifier.height(12.dp))

            PeakRow("Dominant", analysis.dominantHz, "Hz", null)
            Divider(color = Color(0xFF333333), modifier = Modifier.padding(vertical = 6.dp))
            PeakRow("1X Shaft", shaftHz, "Hz", null)
            Divider(color = Color(0xFF333333), modifier = Modifier.padding(vertical = 6.dp))
            
            val bpfoHz = shaftHz * bpfoFactor
            PeakRow("BPFO", bpfoHz, "Hz", analysis.bpfoEnergy)
            
            Divider(color = Color(0xFF333333), modifier = Modifier.padding(vertical = 6.dp))
            
            val bpfiHz = shaftHz * bpfiFactor
            PeakRow("BPFI", bpfiHz, "Hz", analysis.bpfiEnergy)
        }
    }
}

@Composable
private fun PeakRow(label: String, freq: Float, unit: String, energy: Float?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = Color.LightGray,
            modifier = Modifier.width(85.dp),
            fontSize = 14.sp
        )
        Text(
            text = "${freq.format(1)} $unit",
            color = if (freq > 0) Color.White else Color.DarkGray,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
        if (energy != null) {
            Spacer(Modifier.weight(1f))
            Text(
                text = "Energy: ${energy.format(2)}",
                color = if (energy > 0.5f) Color(0xFFFFC107) else Color.Gray,
                fontSize = 12.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
        }
    }
}

private fun axisLabel(axis: Int): String = when (axis) {
    0 -> "X (Radial)"
    1 -> "Y (Radial)"
    2 -> "Z (Axial)"
    else -> "Overall (Worst Axis)"
}

private fun Float.format(decimals: Int) = "%.${decimals}f".format(this)

@Composable
fun MountingStatusBanner(analysis: Diagnosis) {
    if (!analysis.isMounted || analysis.mountingQuality < 60f) {
        val mountingColor = when (analysis.mountingStatusLevel) {
            0 -> Color(0xFFF44336) // Red
            1 -> Color(0xFFFFC107) // Amber
            else -> Color(0xFF00C853) // Green
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = mountingColor.copy(alpha = 0.15f)
            ),
            border = BorderStroke(2.dp, mountingColor)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (analysis.isMounted) Icons.Default.Warning else Icons.Default.Error,
                    contentDescription = null,
                    tint = mountingColor,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = analysis.mountingMessage,
                        style = MaterialTheme.typography.titleMedium,
                        color = mountingColor
                    )
                    Text(
                        "Quality: ${analysis.mountingQuality.toInt()}%",
                        color = mountingColor.copy(alpha = 0.9f)
                    )
                }
            }
        }
    }
}

@Composable
fun BatteryWarningBanner(state: BatteryGuardian.BatteryState) {
    val color = when (state.status) {
        BatteryGuardian.Status.CRITICAL_HOT -> Color(0xFFF44336)
        BatteryGuardian.Status.WARN_HOT -> Color(0xFFFF9800)
        BatteryGuardian.Status.WARN_LOW -> Color(0xFFFFC107)
        BatteryGuardian.Status.WARN_HIGH -> Color(0xFF2196F3)
        else -> Color.Gray
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.15f)
        ),
        border = BorderStroke(2.dp, color)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = state.warning,
                    style = MaterialTheme.typography.titleSmall,
                    color = color
                )
                Text(
                    "Battery: ${state.level}% | ${state.tempCelsius}°C",
                    color = color.copy(alpha = 0.9f),
                    fontSize = 12.sp
                )
            }
        }
    }
}
