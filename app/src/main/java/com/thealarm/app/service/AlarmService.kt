package com.thealarm.app.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.thealarm.app.ArrivalActivity
import com.thealarm.app.MainActivity
import com.thealarm.app.R
import com.thealarm.app.model.AlertStage
import com.thealarm.app.model.AppLanguage
import com.thealarm.app.model.DebugInfo
import com.thealarm.app.model.Destination
import com.thealarm.app.model.EnglishStrings
import com.thealarm.app.model.JourneyState
import com.thealarm.app.model.MalayalamStrings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AlarmService : Service(), LocationListener {

    companion object {
        const val ACTION_START_TRACKING = "ACTION_START_TRACKING"
        const val ACTION_STOP_ALARM = "ACTION_STOP_ALARM"
        const val ACTION_START_SIMULATION = "ACTION_START_SIMULATION"
        const val ACTION_SIMULATE_MISSED_STOP = "ACTION_SIMULATE_MISSED_STOP"

        const val EXTRA_DEST_NAME = "EXTRA_DEST_NAME"
        const val EXTRA_DEST_ADDR = "EXTRA_DEST_ADDR"
        const val EXTRA_DEST_LAT = "EXTRA_DEST_LAT"
        const val EXTRA_DEST_LON = "EXTRA_DEST_LON"
        const val EXTRA_DEST_RADIUS = "EXTRA_DEST_RADIUS"
        const val EXTRA_LANG = "EXTRA_LANG"
        const val EXTRA_ALERT_ZONES = "EXTRA_ALERT_ZONES"
        const val EXTRA_ENABLE_EARLY_WARNING = "EXTRA_ENABLE_EARLY_WARNING"
        const val EXTRA_ENABLE_MISSED_STOP = "EXTRA_ENABLE_MISSED_STOP"
        const val EXTRA_IS_MISSED_STOP = "EXTRA_IS_MISSED_STOP"
        const val EXTRA_MISSED_DIST = "EXTRA_MISSED_DIST"

        private const val NOTIFICATION_ID = 1001
        private const val EARLY_WARNING_NOTIF_ID = 1002
        private const val CHANNEL_ID = "thealarm_tracking_channel"
        private const val EARLY_WARNING_CHANNEL_ID = "thealarm_warning_channel"
        private const val ALARM_CHANNEL_ID = "thealarm_ring_channel"

        // Global state observable by UI
        private val _journeyState = MutableStateFlow(JourneyState.IDLE)
        val journeyState: StateFlow<JourneyState> = _journeyState.asStateFlow()

        private val _currentDestination = MutableStateFlow<Destination?>(null)
        val currentDestination: StateFlow<Destination?> = _currentDestination.asStateFlow()

        private val _debugInfo = MutableStateFlow(DebugInfo())
        val debugInfo: StateFlow<DebugInfo> = _debugInfo.asStateFlow()

        var activeInstance: AlarmService? = null
    }

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): AlarmService = this@AlarmService
    }

    private var locationManager: LocationManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var audioPlayer: AlarmAudioPlayer
    private var simulator: LocationSimulator? = null

    private var destination: Destination? = null
    private var alertZones: List<Float> = listOf(2000f, 500f)
    private val notifiedZones = mutableSetOf<Float>()
    private var enableEarlyWarning = true
    private var enableMissedStopAlert = true

    private var hasTriggeredAlarm = false
    private var hasTriggeredMissedStop = false
    private var minDistanceMeters = Float.MAX_VALUE
    private var currentStage = AlertStage.FAR
    private var currentLangCode = "en"

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        audioPlayer = AlarmAudioPlayer(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager

        createNotificationChannels()
        acquireWakeLock()

        simulator = LocationSimulator { mockLocation ->
            onLocationChanged(mockLocation)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TRACKING -> {
                val name = intent.getStringExtra(EXTRA_DEST_NAME) ?: "Destination"
                val addr = intent.getStringExtra(EXTRA_DEST_ADDR) ?: ""
                val lat = intent.getDoubleExtra(EXTRA_DEST_LAT, 0.0)
                val lon = intent.getDoubleExtra(EXTRA_DEST_LON, 0.0)
                val radius = intent.getFloatExtra(EXTRA_DEST_RADIUS, 500f)
                currentLangCode = intent.getStringExtra(EXTRA_LANG) ?: "en"

                val zonesArray = intent.getFloatArrayExtra(EXTRA_ALERT_ZONES)
                alertZones = if (zonesArray != null && zonesArray.isNotEmpty()) {
                    zonesArray.toList().sortedDescending()
                } else {
                    listOf(2000f, 500f).sortedDescending()
                }

                enableEarlyWarning = intent.getBooleanExtra(EXTRA_ENABLE_EARLY_WARNING, true)
                enableMissedStopAlert = intent.getBooleanExtra(EXTRA_ENABLE_MISSED_STOP, true)

                val dest = Destination(
                    name = name,
                    address = addr,
                    latitude = lat,
                    longitude = lon,
                    radiusMeters = radius,
                    alertZones = alertZones,
                    enableEarlyWarning = enableEarlyWarning,
                    enableMissedStopAlert = enableMissedStopAlert
                )
                Log.i("TheAlarm", "Starting tracking for ${dest.name} at (${dest.latitude}, ${dest.longitude}) with Alert Radius = ${dest.radiusMeters} m")
                startTracking(dest)
            }
            ACTION_START_SIMULATION -> {
                val dest = destination ?: _currentDestination.value
                dest?.let {
                    startTracking(it)
                    simulator?.startSimulation(it)
                    _debugInfo.value = _debugInfo.value.copy(isSimulated = true)
                }
            }
            ACTION_SIMULATE_MISSED_STOP -> {
                val dest = destination ?: _currentDestination.value
                dest?.let {
                    startTracking(it)
                    simulator?.startMissedStopSimulation(it)
                    _debugInfo.value = _debugInfo.value.copy(isSimulated = true)
                }
            }
            ACTION_STOP_ALARM -> {
                stopAlarmAndService()
            }
        }
        return START_NOT_STICKY
    }

    private fun startTracking(dest: Destination) {
        destination = dest
        _currentDestination.value = dest
        _journeyState.value = JourneyState.TRACKING
        hasTriggeredAlarm = false
        hasTriggeredMissedStop = false
        minDistanceMeters = Float.MAX_VALUE
        notifiedZones.clear()
        currentStage = AlertStage.FAR

        startForegroundNotification("NearMe • Journey to ${dest.name}", "Monitoring distance…")
        requestLocationUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        try {
            locationManager?.let { lm ->
                val providersToRequest = mutableListOf(
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    providersToRequest.add(LocationManager.FUSED_PROVIDER)
                }

                for (provider in providersToRequest) {
                    try {
                        if (lm.allProviders.contains(provider) && lm.isProviderEnabled(provider)) {
                            lm.requestLocationUpdates(
                                provider,
                                1000L, // 1 second
                                0f,    // 0 meters: deliver any movement immediately!
                                this
                            )
                            Log.d("TheAlarm", "Registered location listener on $provider")
                        }
                    } catch (e: Exception) {
                        Log.e("TheAlarm", "Failed to register $provider", e)
                    }
                }

                // Initial check with best available last known location
                var bestLoc: Location? = null
                for (provider in lm.allProviders) {
                    val loc = try { lm.getLastKnownLocation(provider) } catch (e: Exception) { null }
                    if (loc != null) {
                        if (bestLoc == null || loc.time > bestLoc.time) {
                            bestLoc = loc
                        }
                    }
                }
                bestLoc?.let {
                    Log.d("TheAlarm", "Triggering initial check with last known location from ${it.provider}: (${it.latitude}, ${it.longitude})")
                    onLocationChanged(it)
                }
            }
        } catch (e: Exception) {
            Log.e("TheAlarm", "Error requesting location updates", e)
        }
    }

    override fun onLocationChanged(location: Location) {
        val dest = destination ?: return
        if (hasTriggeredAlarm || hasTriggeredMissedStop) return

        val distance = FloatArray(1)
        Location.distanceBetween(
            location.latitude,
            location.longitude,
            dest.latitude,
            dest.longitude,
            distance
        )
        val remainingMeters = distance[0]

        Log.i("TheAlarm", "Location update [${location.provider}]: remaining=$remainingMeters m, circle radius=${dest.radiusMeters} m")

        // Update running minimum distance observed
        if (remainingMeters < minDistanceMeters) {
            minDistanceMeters = remainingMeters
        }

        // 1. PRIMARY CHECK: User has entered the Alert Radius circle on the map!
        if (remainingMeters <= dest.radiusMeters) {
            Log.i("TheAlarm", ">>> ENTERED CIRCLE! remaining=$remainingMeters m <= radius=${dest.radiusMeters} m. TRIGGERING ARRIVAL ALARM! <<<")
            currentStage = AlertStage.ARRIVED
            updateDebugAndNotification(location, remainingMeters, dest)
            triggerArrivalAlarm(dest)
            return
        }

        // 2. MULTI-ZONE CHECK: Outer Early Warning zones (strictly for zones LARGER than the arrival circle)
        for (zone in alertZones.sortedDescending()) {
            if (zone > dest.radiusMeters && remainingMeters <= zone && zone !in notifiedZones) {
                notifiedZones.add(zone)
                val isOutermost = (zone == alertZones.maxOrNull())
                if (isOutermost && enableEarlyWarning) {
                    currentStage = AlertStage.EARLY_WARNING
                    triggerEarlyWarning(dest, zone)
                } else {
                    currentStage = AlertStage.APPROACHING
                    triggerApproaching(dest, zone)
                }
            }
        }

        // 3. MISSED STOP CHECK: If user was near destination (< 2.5x radius or 600m) and is now moving away by > 350m
        val closeThreshold = (dest.radiusMeters * 2.5f).coerceAtLeast(600f)
        if (enableMissedStopAlert && minDistanceMeters <= closeThreshold) {
            if (remainingMeters >= (minDistanceMeters + 350f) && remainingMeters > dest.radiusMeters) {
                Log.w("TheAlarm", ">>> MISSED STOP DETECTED! minDistance=$minDistanceMeters m, now=$remainingMeters m. TRIGGERING MISSED STOP! <<<")
                currentStage = AlertStage.MISSED_STOP
                updateDebugAndNotification(location, remainingMeters, dest)
                triggerMissedStopAlarm(dest, remainingMeters)
                return
            }
        }

        updateDebugAndNotification(location, remainingMeters, dest)
    }

    private fun updateDebugAndNotification(location: Location, remainingMeters: Float, dest: Destination) {
        _debugInfo.value = DebugInfo(
            provider = location.provider ?: "Unknown",
            accuracyMeters = location.accuracy,
            currentLat = location.latitude,
            currentLon = location.longitude,
            distanceRemainingMeters = remainingMeters,
            minDistanceMeters = minDistanceMeters,
            currentStage = currentStage,
            isSimulated = simulator?.isRunning() == true
        )

        val distanceText = if (remainingMeters >= 1000) {
            String.format("%.1f km remaining", remainingMeters / 1000f)
        } else {
            "${remainingMeters.toInt()} m remaining"
        }

        val stageText = when (currentStage) {
            AlertStage.EARLY_WARNING -> " (Early Warning Active)"
            AlertStage.APPROACHING -> " (Approaching Zone)"
            AlertStage.ARRIVED -> " (Arrived)"
            AlertStage.MISSED_STOP -> " (⚠️ Missed Stop!)"
            AlertStage.FAR -> ""
        }

        updateNotification("NearMe • Journey to ${dest.name}", "$distanceText$stageText")
    }

    private fun triggerEarlyWarning(dest: Destination, zoneMeters: Float) {
        val strings = if (currentLangCode == "ml") MalayalamStrings else EnglishStrings
        val zoneText = if (zoneMeters >= 1000f) {
            String.format("%.1f %s", zoneMeters / 1000f, strings.kilometers)
        } else {
            "${zoneMeters.toInt()} ${strings.meters}"
        }

        vibratePhone(longArrayOf(0, 300, 200, 300))

        val notif = NotificationCompat.Builder(this, EARLY_WARNING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(strings.earlyWarningNotifTitle)
            .setContentText(String.format(strings.earlyWarningNotifText, dest.name, zoneText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(EARLY_WARNING_NOTIF_ID, notif)
    }

    private fun triggerApproaching(dest: Destination, zoneMeters: Float) {
        val strings = if (currentLangCode == "ml") MalayalamStrings else EnglishStrings
        val zoneText = if (zoneMeters >= 1000f) {
            String.format("%.1f %s", zoneMeters / 1000f, strings.kilometers)
        } else {
            "${zoneMeters.toInt()} ${strings.meters}"
        }

        vibratePhone(longArrayOf(0, 250, 150, 250))

        val notif = NotificationCompat.Builder(this, EARLY_WARNING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(strings.approachingNotifTitle)
            .setContentText(String.format(strings.approachingNotifText, zoneText, dest.name))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(EARLY_WARNING_NOTIF_ID, notif)
    }

    private fun triggerArrivalAlarm(dest: Destination) {
        if (hasTriggeredAlarm || hasTriggeredMissedStop) return
        hasTriggeredAlarm = true
        _journeyState.value = JourneyState.ARRIVED

        // 1. Play loud phone alarm tone continuously + continuous vibration
        audioPlayer.startAlarm()

        // 2. Launch ArrivalActivity over lock screen
        val arrivalIntent = Intent(this, ArrivalActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_DEST_NAME, dest.name)
            putExtra(EXTRA_LANG, currentLangCode)
            putExtra(EXTRA_IS_MISSED_STOP, false)
        }
        try {
            startActivity(arrivalIntent)
        } catch (e: Exception) {
            Log.e("TheAlarm", "startActivity failed: ${e.message}")
        }

        // 3. Show loud arrival alert notification with Stop button
        showArrivalNotification(dest.name)
    }

    private fun triggerMissedStopAlarm(dest: Destination, currentDistMeters: Float) {
        if (hasTriggeredMissedStop || hasTriggeredAlarm) return
        hasTriggeredMissedStop = true
        _journeyState.value = JourneyState.MISSED_STOP

        // Sound loud continuous alarm tone + vibration
        audioPlayer.startAlarm()

        // Launch ArrivalActivity in Missed-Stop Mode over lock screen
        val missedIntent = Intent(this, ArrivalActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_DEST_NAME, dest.name)
            putExtra(EXTRA_LANG, currentLangCode)
            putExtra(EXTRA_IS_MISSED_STOP, true)
            putExtra(EXTRA_MISSED_DIST, currentDistMeters)
        }
        try {
            startActivity(missedIntent)
        } catch (e: Exception) {
            Log.e("TheAlarm", "startActivity failed: ${e.message}")
        }

        showMissedStopNotification(dest.name, currentDistMeters)
    }

    fun stopAlarmAndService() {
        audioPlayer.stopAlarm()
        simulator?.stopSimulation()
        hasTriggeredAlarm = false
        hasTriggeredMissedStop = false
        minDistanceMeters = Float.MAX_VALUE
        notifiedZones.clear()
        currentStage = AlertStage.FAR

        _journeyState.value = JourneyState.IDLE
        _currentDestination.value = null

        try {
            locationManager?.removeUpdates(this)
        } catch (e: Exception) {
            Log.e("TheAlarm", "Error removing location updates", e)
        }

        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun vibratePhone(pattern: LongArray) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, -1)
            }
        } catch (e: Exception) {
            Log.e("TheAlarm", "Vibration failed", e)
        }
    }

    private fun startForegroundNotification(title: String, content: String) {
        val notification = buildTrackingNotification(title, content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(title: String, content: String) {
        val notification = buildTrackingNotification(title, content)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID, notification)
    }

    private fun buildTrackingNotification(title: String, content: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pOpen = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AlarmService::class.java).apply {
            action = ACTION_STOP_ALARM
        }
        val pStop = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pOpen)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel Alarm", pStop)
            .build()
    }

    private fun showArrivalNotification(destName: String) {
        val stopIntent = Intent(this, AlarmService::class.java).apply {
            action = ACTION_STOP_ALARM
        }
        val pStop = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val fullScreenIntent = Intent(this, ArrivalActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_DEST_NAME, destName)
            putExtra(EXTRA_IS_MISSED_STOP, false)
        }
        val pFullScreen = PendingIntent.getActivity(
            this, 3, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmNotif = NotificationCompat.Builder(this, ALARM_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("You've Arrived at $destName!")
            .setContentText("Tap to stop alarm")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pFullScreen, true)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP ALARM", pStop)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID, alarmNotif)
    }

    private fun showMissedStopNotification(destName: String, missedDistMeters: Float) {
        val stopIntent = Intent(this, AlarmService::class.java).apply {
            action = ACTION_STOP_ALARM
        }
        val pStop = PendingIntent.getService(
            this, 4, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val fullScreenIntent = Intent(this, ArrivalActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_DEST_NAME, destName)
            putExtra(EXTRA_IS_MISSED_STOP, true)
            putExtra(EXTRA_MISSED_DIST, missedDistMeters)
        }
        val pFullScreen = PendingIntent.getActivity(
            this, 5, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val strings = if (currentLangCode == "ml") MalayalamStrings else EnglishStrings
        val distText = if (missedDistMeters >= 1000f) {
            String.format("%.1f %s", missedDistMeters / 1000f, strings.kilometers)
        } else {
            "${missedDistMeters.toInt()} ${strings.meters}"
        }

        val missedNotif = NotificationCompat.Builder(this, ALARM_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(strings.missedStopAlertTitle)
            .setContentText(String.format(strings.missedStopAlertText, destName, distText))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pFullScreen, true)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP ALARM", pStop)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID, missedNotif)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

            val trackingChannel = NotificationChannel(
                CHANNEL_ID,
                "Destination Monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live remaining distance to your destination"
            }

            val warningChannel = NotificationChannel(
                EARLY_WARNING_CHANNEL_ID,
                "Early Warning Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts before reaching destination stop"
                enableVibration(true)
            }

            val alarmChannel = NotificationChannel(
                ALARM_CHANNEL_ID,
                "Arrival & Missed Stop Alarm",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Loud alert triggered when reaching or missing destination"
                enableVibration(true)
            }

            nm?.createNotificationChannel(trackingChannel)
            nm?.createNotificationChannel(warningChannel)
            nm?.createNotificationChannel(alarmChannel)
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TheAlarm:TrackingWakeLock")
        wakeLock?.acquire()
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e("TheAlarm", "Error releasing wakelock", e)
        }
    }

    override fun onDestroy() {
        activeInstance = null
        stopAlarmAndService()
        super.onDestroy()
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}
