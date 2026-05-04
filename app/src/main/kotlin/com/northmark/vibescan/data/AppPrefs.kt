package com.northmark.vibescan.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.northmark.vibescan.engine.VibeScanEngine
import java.util.UUID

/**
 * AppPrefs — single source of truth for every persisted setting.
 */
class AppPrefs private constructor(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    // ── Identity ──────────────────────────────────────────────────────────────

    var nodeId: String
        get() = prefs.getString(KEY_NODE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit { putString(KEY_NODE_ID, it) }
        }
        set(v) = prefs.edit { putString(KEY_NODE_ID, v) }

    var orgId: String
        get() = prefs.getString(PREF_TENANT_ID, "default") ?: "default"
        set(v) = prefs.edit { putString(PREF_TENANT_ID, v) }

    var nodeName: String
        get() = prefs.getString(KEY_NODE_NAME, "VibeScan Node") ?: "VibeScan Node"
        set(v) = prefs.edit { putString(KEY_NODE_NAME, v) }

    // ── Auth ──────────────────────────────────────────────────────────────────

    var jwtToken: String
        get() = prefs.getString(PREF_AUTH_TOKEN, "") ?: ""
        set(v) = prefs.edit { putString(PREF_AUTH_TOKEN, v) }

    var jwtExpiryMs: Long
        get() = prefs.getLong(KEY_JWT_EXPIRY, 0L)
        set(v) = prefs.edit { putLong(KEY_JWT_EXPIRY, v) }

    val isJwtExpired: Boolean
        get() = System.currentTimeMillis() > jwtExpiryMs - 60_000L

    var isProvisioned: Boolean
        get() = prefs.getBoolean(KEY_PROVISIONED, false)
        set(v) = prefs.edit { putBoolean(KEY_PROVISIONED, v) }

    // ── Account (local MVP — swap for server auth in production) ─────────────

    var accountEmail: String
        get() = prefs.getString(KEY_ACC_EMAIL, "") ?: ""
        set(v) = prefs.edit { putString(KEY_ACC_EMAIL, v) }

    /** Never log or transmit this in production — use a proper auth server. */
    var accountPassHash: String
        get() = prefs.getString(KEY_ACC_PASS, "") ?: ""
        set(v) = prefs.edit { putString(KEY_ACC_PASS, v) }

    fun saveAccount(email: String, password: String) {
        accountEmail    = email
        // Simple hash for local storage only — not cryptographic security
        accountPassHash = password.hashCode().toString()
    }

    fun checkPassword(password: String): Boolean =
        accountPassHash == password.hashCode().toString()

    // ── Engine — machine & shaft ──────────────────────────────────────────────

    var machineClass: Int
        get() = prefs.getInt(KEY_MACHINE_CLASS, 0).coerceIn(0, 3)
        set(v) = prefs.edit { putInt(KEY_MACHINE_CLASS, v.coerceIn(0, 3)) }

    var shaftRpm: Float
        get() = prefs.getFloat(KEY_SHAFT_RPM, 1500f).coerceIn(100f, 60_000f)
        set(v) {
            prefs.edit { putFloat(KEY_SHAFT_RPM, v.coerceIn(100f, 60_000f)) }
        }

    // ── Engine — bearing geometry ─────────────────────────────────────────────

    var bpfoFactor: Float
        get() = prefs.getFloat(KEY_BPFO, 3.585f)
        set(v) = prefs.edit { putFloat(KEY_BPFO, v) }

    var bpfiFactor: Float
        get() = prefs.getFloat(KEY_BPFI, 5.415f)
        set(v) = prefs.edit { putFloat(KEY_BPFI, v) }

    var bearingPresetName: String
        get() = prefs.getString(KEY_BEARING_NAME, "6205 deep groove") ?: "6205 deep groove"
        set(v) = prefs.edit { putString(KEY_BEARING_NAME, v) }

    // ── Engine — frequency search ─────────────────────────────────────────────

    var minSearchHz: Float
        get() = prefs.getFloat(KEY_MIN_HZ, 2.0f).coerceAtLeast(0.5f)
        set(v) = prefs.edit { putFloat(KEY_MIN_HZ, v.coerceAtLeast(0.5f)) }

    var maxSearchHz: Float
        get() = prefs.getFloat(KEY_MAX_HZ, 300f).coerceIn(10f, 500f)
        set(v) = prefs.edit { putFloat(KEY_MAX_HZ, v.coerceIn(10f, 500f)) }

    var focusSearchOnShaft: Boolean
        get() = prefs.getBoolean(KEY_FOCUS_SHAFT, false)
        set(v) = prefs.edit { putBoolean(KEY_FOCUS_SHAFT, v) }

    // ── Signal processing ─────────────────────────────────────────────────────

    var powerlineHz: Float
        get() = prefs.getFloat(KEY_POWERLINE, 50f)
        set(v) = prefs.edit { putFloat(KEY_POWERLINE, v) }

    var analysisIntervalMs: Long
        get() = prefs.getLong(KEY_ANALYSIS_INTERVAL, 1000L).coerceIn(500L, 5000L)
        set(v) = prefs.edit { putLong(KEY_ANALYSIS_INTERVAL, v.coerceIn(500L, 5000L)) }

    // ── Battery guardian ──────────────────────────────────────────────────────

    var batteryUpperPct: Int
        get() = prefs.getInt(KEY_BATT_UPPER, 70).coerceIn(50, 90)
        set(v) = prefs.edit { putInt(KEY_BATT_UPPER, v.coerceIn(50, 90)) }

    var batteryLowerPct: Int
        get() = prefs.getInt(KEY_BATT_LOWER, 30).coerceIn(10, 50)
        set(v) = prefs.edit { putInt(KEY_BATT_LOWER, v.coerceIn(10, 50)) }

    var batteryWarnTempC: Float
        get() = prefs.getFloat(KEY_BATT_WARN_TEMP, 40f).coerceIn(30f, 50f)
        set(v) = prefs.edit { putFloat(KEY_BATT_WARN_TEMP, v.coerceIn(30f, 50f)) }

    var batteryCritTempC: Float
        get() = prefs.getFloat(KEY_BATT_CRIT_TEMP, 45f).coerceIn(35f, 60f)
        set(v) = prefs.edit { putFloat(KEY_BATT_CRIT_TEMP, v.coerceIn(35f, 60f)) }

    // ── Connectivity ──────────────────────────────────────────────────────────

    var apiEndpoint: String
        get() {
            val endpoint = prefs.getString(KEY_API_ENDPOINT, DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT
            Log.d("AppPrefs", "API Endpoint: $endpoint")
            return endpoint
        }
        set(v) = prefs.edit { putString(KEY_API_ENDPOINT, v) }

    var backgroundMonitoring: Boolean
        get() = prefs.getBoolean(KEY_BG_MONITORING, true)
        set(v) = prefs.edit { putBoolean(KEY_BG_MONITORING, v) }

    var currentAssetId: Long
        get() = prefs.getLong(KEY_ASSET_ID, -1L)
        set(v) = prefs.edit { putLong(KEY_ASSET_ID, v) }

    var lastAssetId: Long
        get() = prefs.getLong(KEY_LAST_ASSET, -1L)
        set(v) = prefs.edit { putLong(KEY_LAST_ASSET, v) }

    var pendingBaselineReset: Boolean
        get() = prefs.getBoolean(KEY_PENDING_BASELINE, false)
        set(v) = prefs.edit { putBoolean(KEY_PENDING_BASELINE, v) }

    // ── Apply to engine ───────────────────────────────────────────────────────

    fun applyToEngine(engine: VibeScanEngine) {
        engine.machineClass  = machineClass
        engine.shaftRpm      = shaftRpm
        engine.bpfoFactor    = bpfoFactor
        engine.bpfiFactor    = bpfiFactor
        engine.minSearchHz   = minSearchHz
        engine.maxSearchHz   = maxSearchHz
        if (focusSearchOnShaft) engine.focusSearchOnShaft()
    }

    // ── Auth helpers ──────────────────────────────────────────────────────────

    fun getAuthToken(): String? = if (jwtToken.isEmpty()) null else jwtToken
    fun setAuthToken(token: String) { jwtToken = token }

    fun getUserEmail(): String? = prefs.getString(PREF_USER_EMAIL, null)
    fun setUserEmail(email: String) = prefs.edit().putString(PREF_USER_EMAIL, email).apply()

    fun getTenantId(): String? = if (orgId == "default") null else orgId
    fun setTenantId(id: String) { orgId = id }

    // Add to AppPrefs.kt if not already present
    fun isLoggedIn(): Boolean {
        return getAuthToken() != null && getAuthToken()!!.isNotEmpty()
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    fun signOut() {
        prefs.edit().apply {
            remove(PREF_AUTH_TOKEN)
            remove(PREF_USER_EMAIL)
            remove(PREF_TENANT_ID)
            apply()
        }
    }

    fun factoryReset() = prefs.edit { clear() }

    // ── Singleton ─────────────────────────────────────────────────────────────

    companion object {
        @Volatile
        private var INSTANCE: AppPrefs? = null

        fun getInstance(context: Context): AppPrefs {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppPrefs(context.applicationContext).also { INSTANCE = it }
            }
        }

        const val PREF_FILE           = "vibescan_prefs"
        const val DEFAULT_ENDPOINT    = "https://api.vibescan.io"

        private const val PREF_AUTH_TOKEN = "auth_token"
        private const val PREF_USER_EMAIL = "user_email"
        private const val PREF_TENANT_ID = "tenant_id"

        // Identity
        private const val KEY_NODE_ID         = "node_id"
        private const val KEY_ORG_ID          = "org_id"
        private const val KEY_NODE_NAME       = "node_name"

        // Auth
        private const val KEY_JWT             = "jwt_token"
        private const val KEY_JWT_EXPIRY      = "jwt_expiry_ms"
        private const val KEY_PROVISIONED     = "is_provisioned"
        private const val KEY_ACC_EMAIL       = "acc_email"
        private const val KEY_ACC_PASS        = "acc_pass"

        // Engine
        private const val KEY_MACHINE_CLASS   = "machine_class"
        private const val KEY_SHAFT_RPM       = "shaft_rpm"
        private const val KEY_BPFO            = "bpfo_factor"
        private const val KEY_BPFI            = "bpfi_factor"
        private const val KEY_BEARING_NAME    = "bearing_name"
        private const val KEY_MIN_HZ          = "min_search_hz"
        private const val KEY_MAX_HZ          = "max_search_hz"
        private const val KEY_FOCUS_SHAFT     = "focus_shaft"

        // Signal
        private const val KEY_POWERLINE       = "powerline_hz"
        private const val KEY_ANALYSIS_INTERVAL = "analysis_interval_ms"

        // Battery
        private const val KEY_BATT_UPPER      = "batt_upper_pct"
        private const val KEY_BATT_LOWER      = "batt_lower_pct"
        private const val KEY_BATT_WARN_TEMP  = "batt_warn_temp"
        private const val KEY_BATT_CRIT_TEMP  = "batt_crit_temp"

        // Connectivity
        private const val KEY_API_ENDPOINT    = "api_endpoint"
        private const val KEY_BG_MONITORING   = "background_monitoring"
        private const val KEY_ASSET_ID        = "current_asset_id"
        private const val KEY_LAST_ASSET      = "last_asset_id"
        private const val KEY_PENDING_BASELINE = "pending_baseline_reset"
    }
}