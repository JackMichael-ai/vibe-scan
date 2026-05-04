package com.northmark.vibescan.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.northmark.vibescan.R
import com.northmark.vibescan.data.AppPrefs
import com.northmark.vibescan.data.AssetRepository
import com.northmark.vibescan.data.FirebaseManager
import com.northmark.vibescan.data.SyncManager
import com.northmark.vibescan.databinding.ActivityMainBinding
import com.northmark.vibescan.engine.VibeScanEngine
import com.northmark.vibescan.service.BatteryGuardian
import com.northmark.vibescan.service.KioskModeManager
import com.northmark.vibescan.service.MonitoringService
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    lateinit var engine: VibeScanEngine
    lateinit var repo: AssetRepository
    lateinit var firebaseManager: FirebaseManager
    private lateinit var navController: NavController
    lateinit var batteryGuardian: BatteryGuardian
    lateinit var kioskManager: KioskModeManager
    lateinit var syncManager: SyncManager
    lateinit var prefs: AppPrefs

    var currentAssetId: Long
        get() = prefs.currentAssetId
        set(value) {
            prefs.currentAssetId = value
            if (hasRequiredPermissions()) {
                startMonitoringService()
            }
        }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val audioGranted = grants[Manifest.permission.RECORD_AUDIO] != false
        if (!audioGranted) {
            showError("Microphone permission denied. Running accelerometer-only mode.")
        }
        engine.start()
        startMonitoringService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = AppPrefs.getInstance(applicationContext)
        if (prefs.getAuthToken().isNullOrEmpty()) {
            startActivity(Intent(this, com.northmark.vibescan.ui.auth.LoginActivity::class.java))
            finish()
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        repo = AssetRepository(applicationContext)
        engine = VibeScanEngine(applicationContext)
        engine.shaftRpm = prefs.shaftRpm
        engine.machineClass = prefs.machineClass
        engine.powerlineHz = prefs.powerlineHz
        batteryGuardian = BatteryGuardian(applicationContext)
        kioskManager = KioskModeManager(applicationContext)
        syncManager = SyncManager(applicationContext, repo, prefs)
        firebaseManager = FirebaseManager(prefs.nodeId)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHost = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHost.navController
        binding.bottomNav.setupWithNavController(navController)

        lifecycleScope.launch {
            engine.diagnosis.collect { d ->
                if (d.isError) showError(d.errorMsg)
            }
        }

        requestPermissionsAndStart()
    }

    override fun onResume() {
        super.onResume()
        if (hasRequiredPermissions()) engine.start()
        batteryGuardian.start()
        syncManager.startSync()
    }

    override fun onPause() {
        super.onPause()
        if (!prefs.backgroundMonitoring) engine.stop()
        batteryGuardian.stop()
        syncManager.stopSync()
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.stop()
    }

    private fun hasRequiredPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            engine.start()
            startMonitoringService()
        }
    }

    private fun startMonitoringService() {
        val serviceIntent = Intent(this, MonitoringService::class.java).apply {
            putExtra(MonitoringService.EXTRA_ASSET_ID, prefs.currentAssetId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun showError(msg: String) {
        com.google.android.material.snackbar.Snackbar
            .make(binding.root, msg, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
            .show()
    }
}
