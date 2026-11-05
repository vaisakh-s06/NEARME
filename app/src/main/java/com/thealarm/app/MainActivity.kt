package com.thealarm.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.thealarm.app.model.Destination
import com.thealarm.app.model.JourneyState
import com.thealarm.app.service.AlarmService
import com.thealarm.app.ui.screens.HomeScreen
import com.thealarm.app.ui.theme.TheAlarmTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity(), LocationListener {

    private var locationManager: LocationManager? = null
    private val userCurrentLocation = MutableStateFlow<Location?>(null)
    private var pendingDestination: Pair<Destination, String>? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineLocationGranted || coarseLocationGranted) {
            startLiveLocationUpdates()
            pendingDestination?.let { (dest, lang) ->
                startAlarmTrackingService(dest, lang)
                pendingDestination = null
            }
        } else {
            Toast.makeText(this, "Location permission is required for destination alarms.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        locationManager = getSystemService(LOCATION_SERVICE) as? LocationManager

        requestRequiredPermissions()
        promptIgnoreBatteryOptimizations()
        startLiveLocationUpdates()

        setContent {
            TheAlarmTheme {
                val journeyState by AlarmService.journeyState.collectAsState()
                val currentDest by AlarmService.currentDestination.collectAsState()
                val debugInfo by AlarmService.debugInfo.collectAsState()
                val userLocation by userCurrentLocation.collectAsState()

                HomeScreen(
                    journeyState = journeyState,
                    currentDest = currentDest,
                    debugInfo = debugInfo,
                    userLat = userLocation?.latitude,
                    userLon = userLocation?.longitude,
                    onStartAlarm = { dest, langCode ->
                        if (hasLocationPermission()) {
                            startAlarmTrackingService(dest, langCode)
                        } else {
                            pendingDestination = dest to langCode
                            requestRequiredPermissions()
                        }
                    },
                    onCancelAlarm = {
                        stopAlarmTrackingService()
                    },
                    onSimulateJourney = {
                        val simIntent = Intent(this, AlarmService::class.java).apply {
                            action = AlarmService.ACTION_START_SIMULATION
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(simIntent)
                        } else {
                            startService(simIntent)
                        }
                    },
                    onSimulateMissedStop = {
                        val simIntent = Intent(this, AlarmService::class.java).apply {
                            action = AlarmService.ACTION_SIMULATE_MISSED_STOP
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(simIntent)
                        } else {
                            startService(simIntent)
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startLiveLocationUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun startLiveLocationUpdates() {
        if (!hasLocationPermission()) return
        try {
            locationManager?.let { lm ->
                val lastGps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                val lastNet = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                (lastGps ?: lastNet)?.let { userCurrentLocation.value = it }

                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 2f, this)
                }
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 5f, this)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onLocationChanged(location: Location) {
        userCurrentLocation.value = location
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestRequiredPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun promptIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as? PowerManager
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // Ignored if device vendor restricts direct intent
                }
            }
        }
    }

    private fun startAlarmTrackingService(dest: Destination, langCode: String = "en") {
        val intent = Intent(this, AlarmService::class.java).apply {
            action = AlarmService.ACTION_START_TRACKING
            putExtra(AlarmService.EXTRA_DEST_NAME, dest.name)
            putExtra(AlarmService.EXTRA_DEST_ADDR, dest.address)
            putExtra(AlarmService.EXTRA_DEST_LAT, dest.latitude)
            putExtra(AlarmService.EXTRA_DEST_LON, dest.longitude)
            putExtra(AlarmService.EXTRA_DEST_RADIUS, dest.radiusMeters)
            putExtra(AlarmService.EXTRA_LANG, langCode)
            putExtra(AlarmService.EXTRA_ALERT_ZONES, dest.alertZones.toFloatArray())
            putExtra(AlarmService.EXTRA_ENABLE_EARLY_WARNING, dest.enableEarlyWarning)
            putExtra(AlarmService.EXTRA_ENABLE_MISSED_STOP, dest.enableMissedStopAlert)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        val radiusStr = if (dest.radiusMeters >= 1000f) "${(dest.radiusMeters / 1000f).toInt()}km" else "${dest.radiusMeters.toInt()}m"
        Toast.makeText(this, "Destination alarm active • $radiusStr radius", Toast.LENGTH_SHORT).show()
    }

    private fun stopAlarmTrackingService() {
        val intent = Intent(this, AlarmService::class.java).apply {
            action = AlarmService.ACTION_STOP_ALARM
        }
        startService(intent)
    }

    override fun onDestroy() {
        try {
            locationManager?.removeUpdates(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onDestroy()
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}
