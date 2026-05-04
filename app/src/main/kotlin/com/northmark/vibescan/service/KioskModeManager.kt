package com.northmark.vibescan.service

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import com.northmark.vibescan.receiver.VibeScanDeviceAdminReceiver
import com.northmark.vibescan.ui.main.MainActivity

/**
 * KioskModeManager — Single-purpose "dumb sensor" lockdown.
 */
class KioskModeManager(private val context: Context) {

    enum class KioskStatus {
        INACTIVE, SCREEN_PINNED, DEVICE_OWNER
    }

    private val dpm: DevicePolicyManager by lazy {
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }

    private val adminComponent by lazy {
        ComponentName(context, VibeScanDeviceAdminReceiver::class.java)
    }

    fun getStatus(): KioskStatus {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (dpm.isDeviceOwnerApp(context.packageName)) return KioskStatus.DEVICE_OWNER
        }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE)
                return KioskStatus.SCREEN_PINNED
        }
        return KioskStatus.INACTIVE
    }

    fun enableScreenPinning(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                activity.startLockTask()
            } catch (e: Exception) { }
        }
    }

    fun disableScreenPinning(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                activity.stopLockTask()
            } catch (e: Exception) { }
        }
    }

    fun applyDeviceOwnerLockdown(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        if (!dpm.isDeviceOwnerApp(context.packageName)) return

        dpm.setLockTaskPackages(adminComponent, arrayOf(context.packageName))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            dpm.setGlobalSetting(adminComponent,
                android.provider.Settings.Global.ADB_ENABLED, "0")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            dpm.setFactoryResetProtectionPolicy(adminComponent, null)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            activity.window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }

        activity.startLockTask()

        val filter = android.content.IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_HOME)
            addCategory(android.content.Intent.CATEGORY_DEFAULT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            dpm.addPersistentPreferredActivity(adminComponent, filter,
                ComponentName(context, MainActivity::class.java))
        }
    }

    fun getDeploymentReport(): String {
        val status = getStatus()
        return buildString {
            appendLine("VibeScan Node Security Report")
            appendLine("==============================")
            appendLine("Package:      ${context.packageName}")
            appendLine("Kiosk status: ${status.name}")
            appendLine("Device owner: ${
                if (Build.VERSION.SDK_INT >= 21)
                    dpm.isDeviceOwnerApp(context.packageName)
                else "N/A (API < 21)"
            }")
            appendLine("Android API:  ${Build.VERSION.SDK_INT}")
            appendLine("Device:       ${Build.MANUFACTURER} ${Build.MODEL}")
            
            // Note: SiteAuditReportGenerator provides a more detailed Markdown version
            // including sensor stability and asset risks.
        }
    }
}
