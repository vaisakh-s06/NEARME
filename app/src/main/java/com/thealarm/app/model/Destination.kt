package com.thealarm.app.model

data class Destination(
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float = 500f,
    val alertZones: List<Float> = listOf(2000f, 500f, 100f),
    val enableEarlyWarning: Boolean = true,
    val enableMissedStopAlert: Boolean = true
)

enum class JourneyState {
    IDLE,
    TRACKING,
    ARRIVED,
    MISSED_STOP
}

enum class AlertStage {
    FAR,
    EARLY_WARNING,
    APPROACHING,
    ARRIVED,
    MISSED_STOP
}

data class DebugInfo(
    val provider: String = "None",
    val accuracyMeters: Float = 0f,
    val currentLat: Double = 0.0,
    val currentLon: Double = 0.0,
    val distanceRemainingMeters: Float = 0f,
    val minDistanceMeters: Float = Float.MAX_VALUE,
    val currentStage: AlertStage = AlertStage.FAR,
    val isSimulated: Boolean = false
)


