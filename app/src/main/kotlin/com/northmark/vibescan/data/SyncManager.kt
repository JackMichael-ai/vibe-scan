package com.northmark.vibescan.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pushes local outbox data to the Node API when credentials are available.
 */
class SyncManager(
    context: Context,
    private val repo: AssetRepository,
    private val prefs: AppPrefs
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null

    @Suppress("UNUSED_PARAMETER")
    private val appContext = context.applicationContext

    fun startSync() {
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            while (isActive) {
                try {
                    val hasReadings = repo.getOutboxCount() > 0
                    val hasAlerts = repo.getOutboxAlerts().isNotEmpty()
                    if ((hasReadings || hasAlerts) && prefs.jwtToken.isNotEmpty()) {
                        performSync()
                    }
                } catch (e: Exception) {
                    Log.e("SyncManager", "Sync loop failed: ${e.message}", e)
                }
                delay(SYNC_INTERVAL_MS)
            }
        }
    }

    fun stopSync() {
        syncJob?.cancel()
    }

    private suspend fun performSync() {
        val jwt = prefs.jwtToken
        if (jwt.isEmpty()) return

        syncReadings(jwt)
        syncAlerts(jwt)
    }

    private fun syncReadings(jwt: String) {
        val readings = repo.getOutboxReadings(limit = BATCH_SIZE)
        if (readings.isEmpty()) return

        val payload = JSONObject().apply {
            put("sent_at", System.currentTimeMillis())
            put("readings", JSONArray().apply {
                readings.forEach { r ->
                    put(JSONObject().apply {
                        put("asset_id", r.assetId)
                        put("health", r.health)
                        put("fault_code", r.faultCode)
                        put("rms", r.rms)
                        put("kurtosis", r.kurtosis)
                        put("crest", r.crest)
                        put("dominant_hz", r.dominantHz)
                        put("rms_ms2", r.rmsMs2)
                        put("signal_confidence", r.signalConfidence)
                        put("iso_zone", r.isoZone)
                        put("bpfo_energy", r.bpfoEnergy)
                        put("bpfi_energy", r.bpfiEnergy)
                        put("ai_reliability", r.aiReliability)
                        put("mount_grade", r.mountGrade)
                        put("timestamp", r.timestamp)
                    })
                }
            })
        }

        val success = postToCloud("/api/v1/readings/batch", payload.toString(), jwt)
        if (success) {
            repo.markReadingsSynced(readings.map { it.outboxId })
            Log.d("SyncManager", "Synced ${readings.size} readings")
        }
    }

    private fun syncAlerts(jwt: String) {
        val alerts = repo.getOutboxAlerts().take(BATCH_SIZE)
        if (alerts.isEmpty()) return

        val payload = JSONObject().apply {
            put("sent_at", System.currentTimeMillis())
            put("alerts", JSONArray().apply {
                alerts.forEach { alert ->
                    put(JSONObject().apply {
                        put("asset_id", alert.assetId)
                        put("severity", alert.severity)
                        put("fault_label", alert.faultLabel)
                        put("action", alert.action)
                        put("timestamp", alert.timestamp)
                    })
                }
            })
        }

        val success = postToCloud("/api/v1/alerts/batch", payload.toString(), jwt)
        if (success) {
            repo.markAlertsSynced(alerts.map { it.outboxId })
            Log.d("SyncManager", "Synced ${alerts.size} alerts")
        }
    }

    private fun postToCloud(endpoint: String, body: String, jwt: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("${prefs.apiEndpoint}$endpoint")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $jwt")
                setRequestProperty("X-Node-Id", prefs.nodeId)
                setRequestProperty("X-Org-Id", prefs.orgId)
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            connection.outputStream.use { it.write(body.toByteArray()) }
            connection.responseCode in 200..299
        } catch (e: Exception) {
            Log.e("SyncManager", "POST failed: ${e.message}", e)
            false
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        const val ACTION_FINAL_SYNC = "com.northmark.vibescan.ACTION_FINAL_SYNC"
        private const val SYNC_INTERVAL_MS = 300_000L
        private const val BATCH_SIZE = 50
    }
}
