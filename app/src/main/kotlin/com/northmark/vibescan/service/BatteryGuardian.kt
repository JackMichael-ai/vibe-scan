package com.northmark.vibescan.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BatteryGuardian — Smart charging manager for node phones.
 */
class BatteryGuardian(private val context: Context) {

    data class BatteryState(
        val level:       Int     = -1,    // 0–100 %
        val isCharging:  Boolean = false,
        val tempCelsius: Float   = 0f,    // battery temperature
        val status:      Status  = Status.UNKNOWN,
        val warning:     String  = ""
    )

    enum class Status { UNKNOWN, SAFE, WARN_HIGH, WARN_LOW, WARN_HOT, CRITICAL_HOT }

    private val _state = MutableStateFlow(BatteryState())
    val state: StateFlow<BatteryState> = _state.asStateFlow()

    companion object {
        const val CHARGE_UPPER    = 70    // %
        const val CHARGE_LOWER    = 30    // %
        const val TEMP_WARN_C     = 40f
        const val TEMP_CRITICAL_C = 45f
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BATTERY_CHANGED) return

            val level   = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale   = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val pct     = if (scale > 0) (level * 100) / scale else -1

            val status  = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                           status == BatteryManager.BATTERY_STATUS_FULL

            val tempRaw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            val tempC   = tempRaw / 10.0f

            val (battStatus, warning) = evaluate(pct, charging, tempC)

            _state.value = BatteryState(
                level       = pct,
                isCharging  = charging,
                tempCelsius = tempC,
                status      = battStatus,
                warning     = warning
            )
        }
    }

    private fun evaluate(pct: Int, charging: Boolean, tempC: Float): Pair<Status, String> {
        if (tempC >= TEMP_CRITICAL_C) return Pair(
            Status.CRITICAL_HOT,
            "CRITICAL: Battery at ${tempC.toInt()}°C — unplug and move phone away from heat source immediately."
        )
        if (tempC >= TEMP_WARN_C) return Pair(
            Status.WARN_HOT,
            "Battery temperature ${tempC.toInt()}°C — check phone is not exposed to direct heat from machine."
        )
        if (charging && pct >= CHARGE_UPPER) return Pair(
            Status.WARN_HIGH,
            "Battery at $pct% — unplug charger to extend battery life. Target: 30–70%."
        )
        if (!charging && pct <= CHARGE_LOWER) return Pair(
            Status.WARN_LOW,
            "Battery at $pct% — reconnect charger. Node will shut down below 10%."
        )
        return Pair(Status.SAFE, "")
    }

    fun start() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
    }

    fun stop() {
        try { context.unregisterReceiver(receiver) } catch (e: Exception) { }
    }
}
