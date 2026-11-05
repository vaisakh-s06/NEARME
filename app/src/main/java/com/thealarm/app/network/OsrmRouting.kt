package com.thealarm.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class RoadRouteInfo(
    val roadDistanceMeters: Float,
    val durationSeconds: Float,
    val waypoints: List<GeoPoint>
)

object OsrmRouting {

    suspend fun getRoadRoute(
        startLat: Double,
        startLon: Double,
        destLat: Double,
        destLon: Double
    ): RoadRouteInfo? = withContext(Dispatchers.IO) {
        try {
            // OSRM API expects lon,lat format
            val urlString = String.format(
                Locale.US,
                "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson",
                startLon, startLat, destLon, destLat
            )
            val conn = URL(urlString).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "TheAlarmApp/1.0 (contact: info@thealarm.org)")
            conn.connectTimeout = 7000
            conn.readTimeout = 7000

            if (conn.responseCode == 200) {
                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseText)

                if (json.optString("code") == "Ok") {
                    val routes = json.getJSONArray("routes")
                    if (routes.length() > 0) {
                        val routeObj = routes.getJSONObject(0)
                        val dist = routeObj.getDouble("distance").toFloat()
                        val duration = routeObj.getDouble("duration").toFloat()

                        val pointList = mutableListOf<GeoPoint>()
                        val geometryObj = routeObj.optJSONObject("geometry")
                        if (geometryObj != null) {
                            val coordsArray = geometryObj.getJSONArray("coordinates")
                            for (i in 0 until coordsArray.length()) {
                                val coord = coordsArray.getJSONArray(i)
                                val lon = coord.getDouble(0)
                                val lat = coord.getDouble(1)
                                pointList.add(GeoPoint(lat, lon))
                            }
                        }

                        return@withContext RoadRouteInfo(
                            roadDistanceMeters = dist,
                            durationSeconds = duration,
                            waypoints = pointList
                        )
                    }
                }
            }
            conn.disconnect()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }
}
