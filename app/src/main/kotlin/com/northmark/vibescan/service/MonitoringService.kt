package com.northmark.vibescan.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.util.Log
import com.northmark.vibescan.data.AppPrefs
import com.northmark.vibescan.data.AssetRepository
import com.northmark.vibescan.data.SyncManager
import com.northmark.vibescan.engine.Diagnosis
import com.northmark.vibescan.engine.Severity
import com.northmark.vibescan.engine.VibeScanEngine
import com.northmark.vibescan.ui.main.MainActivity
import kotlinx.coroutines.*

/**
 * MonitoringService — foreground service for continuous background monitoring.
 */
class MonitoringService : Service() {

    private lateinit var engine: VibeScanEngine
    private lateinit var repo:   AssetRepository
    private lateinit var syncManager: SyncManager
    private lateinit var prefs: AppPrefs
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var observeJob: Job? = null

    private var currentAssetId: Long = -1L
    private var lastAlertSeverity: Severity = Severity.HEALTHY

    override fun onCreate() {
        super.onCreate()
        val context = applicationContext
        prefs = AppPrefs.getInstance(context)
        engine = VibeScanEngine(context)
        repo   = AssetRepository(context)
        syncManager = SyncManager(context, repo, prefs)
        
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("Starting…", 100),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("Starting…", 100))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val assetIdFromIntent = intent?.getLongExtra(EXTRA_ASSET_ID, -1L) ?: -1L
        if (assetIdFromIntent >= 0L) {
            currentAssetId = assetIdFromIntent
            prefs.currentAssetId = currentAssetId
            prefs.lastAssetId = currentAssetId
        } else {
            currentAssetId = prefs.currentAssetId
        }
        engine.start()
        syncManager.startSync()
        observeJob?.cancel()
        observeEngine()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observeJob?.cancel()
        engine.stop()
        syncManager.stopSync()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun observeEngine() {
        Log.d("MonitoringService", "Started observing engine for background alerts")
        observeJob = serviceScope.launch {
            engine.diagnosis.collect { d ->
                Log.v("MonitoringService", "New Diagnosis: health=${d.health}, sev=${d.severity}, fault=${d.faultLabel}")
                updateNotification(d)
                persistReading(d)
                checkForAlert(d)
            }
        }
    }

    private fun updateNotification(d: Diagnosis) {
        val statusText = "${d.severity.name} — Health ${d.health}/100 · ${d.faultLabel}"
        val notif = buildNotification(statusText, d.health)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notif)
    }

    private fun buildNotification(text: String, health: Int): Notification {
        val intent  = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, intent,
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VibeScan monitoring")
            .setContentText(text)
            .setSubText("Health ${health.coerceIn(0, 100)}/100")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private var lastPersistMs = 0L
    private fun persistReading(d: Diagnosis) {
        if (currentAssetId < 0) {
            Log.w("MonitoringService", "Skip persist: No asset ID selected")
            return
        }
        if (!d.baselineReady) return
        val now = System.currentTimeMillis()
        if (now - lastPersistMs < PERSIST_INTERVAL_MS) return
        lastPersistMs = now
        Log.i("MonitoringService", "Persisting reading to DB for asset $currentAssetId")
        serviceScope.launch(Dispatchers.IO) {
            repo.insertReading(currentAssetId, d)
        }
    }

    private fun checkForAlert(d: Diagnosis) {
        if (d.severity == lastAlertSeverity) return
        Log.d("MonitoringService", "Severity changed: $lastAlertSeverity -> ${d.severity}")
        if (d.severity == Severity.HEALTHY || d.severity == Severity.LEARNING) {
            lastAlertSeverity = d.severity
            return
        }

        lastAlertSeverity = d.severity
        serviceScope.launch(Dispatchers.IO) {
            if (currentAssetId >= 0) repo.insertAlert(currentAssetId, d)
        }
        fireCriticalNotification(d)
        if (d.severity == Severity.CRITICAL) vibratePhone()
    }

    private fun fireCriticalNotification(d: Diagnosis) {
        val intent  = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 1, intent,
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)

        val notif = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle("VibeScan Alert — ${d.severity.name}")
            .setContentText(d.faultLabel)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("${d.faultLabel}\n${d.actionLabel}"))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ALERT_NOTIFICATION_ID, notif)
    }

    @Suppress("DEPRECATION")
    private fun vibratePhone() {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(android.os.VibrationEffect.createWaveform(
                longArrayOf(0, 300, 200, 300, 200, 300), -1))
        } else {
            vibrator.vibrate(longArrayOf(0, 300, 200, 300, 200, 300), -1)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "VibeScan Status",
                NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(
                ALERT_CHANNEL_ID, "VibeScan Alerts",
                NotificationManager.IMPORTANCE_HIGH))
        }
    }

    companion object {
        const val NOTIFICATION_ID       = 1001
        const val ALERT_NOTIFICATION_ID = 1002
        const val CHANNEL_ID            = "vibescan_status"
        const val ALERT_CHANNEL_ID      = "vibescan_alerts"
        const val EXTRA_ASSET_ID        = "asset_id"
        const val PERSIST_INTERVAL_MS   = 60_000L
    }
}
