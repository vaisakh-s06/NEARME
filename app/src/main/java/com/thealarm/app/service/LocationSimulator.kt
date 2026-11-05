package com.thealarm.app.service

import android.location.Location
import com.thealarm.app.model.Destination
import kotlinx.coroutines.*

class LocationSimulator(
    private val onLocationUpdate: (Location) -> Unit
) {
    private var simulationJob: Job? = null

    /**
     * Simulates movement toward the destination starting from ~2.4km away down to 50m.
     * Passes through the outer 2km zone (Early Warning), 500m zone, and reaches arrival.
     */
    fun startSimulation(destination: Destination) {
        stopSimulation()

        simulationJob = CoroutineScope(Dispatchers.Default).launch {
            // Start ~2.4 km away (offset lat by ~0.021 degrees)
            var currentLat = destination.latitude + 0.021
            var currentLon = destination.longitude + 0.021

            val steps = 22
            val latStep = (destination.latitude - currentLat) / steps
            val lonStep = (destination.longitude - currentLon) / steps

            for (i in 0..steps) {
                if (!isActive) break

                val mockLoc = Location("Simulator").apply {
                    latitude = currentLat
                    longitude = currentLon
                    accuracy = 4.0f
                    time = System.currentTimeMillis()
                }

                withContext(Dispatchers.Main) {
                    onLocationUpdate(mockLoc)
                }

                currentLat += latStep
                currentLon += lonStep
                delay(1000) // update every second
            }
        }
    }

    /**
     * Simulates approaching destination (~80m away), then missing the stop
     * and continuing past it to 700m+ away to test Missed-Stop Detection.
     */
    fun startMissedStopSimulation(destination: Destination) {
        stopSimulation()

        simulationJob = CoroutineScope(Dispatchers.Default).launch {
            // Step 1: Start ~1.2 km away
            var currentLat = destination.latitude + 0.010
            var currentLon = destination.longitude + 0.010

            // Step toward destination to ~100m
            val approachSteps = 10
            val latApproachStep = (destination.latitude - currentLat) / 12
            val lonApproachStep = (destination.longitude - currentLon) / 12

            for (i in 0 until approachSteps) {
                if (!isActive) break
                val mockLoc = Location("Simulator").apply {
                    latitude = currentLat
                    longitude = currentLon
                    accuracy = 4.0f
                    time = System.currentTimeMillis()
                }
                withContext(Dispatchers.Main) { onLocationUpdate(mockLoc) }
                currentLat += latApproachStep
                currentLon += lonApproachStep
                delay(1000)
            }

            // Step 2: Now continue moving straight PAST the stop to 700m away
            val overshootSteps = 8
            val latOvershoot = 0.0010 // ~110m per step away
            val lonOvershoot = 0.0010

            for (i in 0 until overshootSteps) {
                if (!isActive) break
                currentLat -= latOvershoot
                currentLon -= lonOvershoot
                val mockLoc = Location("Simulator").apply {
                    latitude = currentLat
                    longitude = currentLon
                    accuracy = 4.0f
                    time = System.currentTimeMillis()
                }
                withContext(Dispatchers.Main) { onLocationUpdate(mockLoc) }
                delay(1000)
            }
        }
    }

    fun stopSimulation() {
        simulationJob?.cancel()
        simulationJob = null
    }

    fun isRunning(): Boolean = simulationJob?.isActive == true
}


