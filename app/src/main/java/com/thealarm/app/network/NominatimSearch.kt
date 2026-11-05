package com.thealarm.app.network

import com.thealarm.app.model.Destination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object NominatimSearch {

    suspend fun searchPlaces(query: String): List<Destination> = withContext(Dispatchers.IO) {
        if (query.trim().length < 2) return@withContext emptyList()

        val results = mutableListOf<Destination>()
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://nominatim.openstreetmap.org/search?q=$encodedQuery&format=json&limit=6&addressdetails=1")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "TheAlarmAndroidApp/1.0 (destination.alarm.app@contact.org)")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000

            if (conn.responseCode == 200) {
                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(responseText)

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val displayName = obj.optString("display_name", "Unknown Location")
                    val parts = displayName.split(",")
                    val shortName = parts.firstOrNull()?.trim() ?: displayName
                    val remainingAddress = if (parts.size > 1) parts.drop(1).take(3).joinToString(",").trim() else displayName

                    val lat = obj.getString("lat").toDoubleOrNull() ?: continue
                    val lon = obj.getString("lon").toDoubleOrNull() ?: continue

                    results.add(
                        Destination(
                            name = shortName,
                            address = remainingAddress,
                            latitude = lat,
                            longitude = lon
                        )
                    )
                }
            }
            conn.disconnect()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        results
    }
}

