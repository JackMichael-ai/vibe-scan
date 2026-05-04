package com.northmark.vibescan.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.northmark.vibescan.data.AppPrefs
import com.northmark.vibescan.service.MonitoringService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = AppPrefs.getInstance(context)
            if (prefs.backgroundMonitoring) {
                val assetId = prefs.currentAssetId.takeIf { it >= 0L } ?: prefs.lastAssetId
                val serviceIntent = Intent(context, MonitoringService::class.java).apply {
                    putExtra(MonitoringService.EXTRA_ASSET_ID, assetId)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
