package com.thealarm.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.thealarm.app.model.Destination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object NominatimSearch {

    private fun isOnline(context: Context?): Boolean {
        if (context == null) return true
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            true
        }
    }

    suspend fun searchPlaces(query: String, context: Context? = null): List<Destination> = withContext(Dispatchers.IO) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.length < 2) return@withContext emptyList()

        val onlineResults = mutableListOf<Destination>()
        var isNetworkError = false

        // 1. When ONLINE: Fetch live results directly from Nominatim (covers all streets, shops, landmarks worldwide)
        if (isOnline(context)) {
            try {
                val encodedQuery = URLEncoder.encode(trimmedQuery, "UTF-8")
                val url = URL("https://nominatim.openstreetmap.org/search?q=$encodedQuery&format=json&limit=8&addressdetails=1")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "NearMeAndroidApp/1.0 (travel.alarm.nearme@github.com)")
                conn.connectTimeout = 3000
                conn.readTimeout = 3000

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

                        onlineResults.add(
                            Destination(
                                name = shortName,
                                address = remainingAddress,
                                latitude = lat,
                                longitude = lon
                            )
                        )
                    }
                } else {
                    isNetworkError = true
                }
                conn.disconnect()
            } catch (e: Exception) {
                isNetworkError = true
            }
        } else {
            isNetworkError = true
        }

        // 2. If online search found matches, return online results directly
        if (onlineResults.isNotEmpty() && !isNetworkError) {
            return@withContext onlineResults
        }

        // 3. Fallback: ONLY when offline, disconnected, or if online search fails,
        // instantly surface the built-in offline Indian transit & cities list!
        val offlineResults = OfflineSearchManager.searchOffline(context, trimmedQuery, limit = 8)
        if (offlineResults.isNotEmpty()) {
            return@withContext offlineResults
        }

        onlineResults
    }
}
