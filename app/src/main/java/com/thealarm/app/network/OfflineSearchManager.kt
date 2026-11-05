package com.thealarm.app.network

import android.content.Context
import com.thealarm.app.model.Destination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader

data class OfflinePlace(
    val name: String,
    val address: String,
    val lat: Double,
    val lon: Double,
    val keywords: String
)

object OfflineSearchManager {

    @Volatile
    private var cachedPlaces: List<OfflinePlace>? = null
    private var isInitializing = false

    fun initialize(context: Context) {
        if (cachedPlaces != null || isInitializing) return
        isInitializing = true
        Thread {
            try {
                loadFromAssets(context.applicationContext)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isInitializing = false
            }
        }.start()
    }

    private fun loadFromAssets(context: Context): List<OfflinePlace> {
        val list = mutableListOf<OfflinePlace>()
        try {
            val inputStream = context.assets.open("india_offline_locations.json")
            val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))
            val jsonString = reader.use { it.readText() }
            val array = JSONArray(jsonString)

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    OfflinePlace(
                        name = obj.getString("n"),
                        address = obj.getString("a"),
                        lat = obj.getDouble("lat"),
                        lon = obj.getDouble("lon"),
                        keywords = obj.optString("k", "")
                    )
                )
            }
            cachedPlaces = list
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    suspend fun searchOffline(context: Context?, query: String, limit: Int = 8): List<Destination> = withContext(Dispatchers.Default) {
        val q = query.trim().lowercase()
        if (q.length < 2) return@withContext emptyList()

        val places = cachedPlaces ?: (context?.let { loadFromAssets(it.applicationContext) } ?: emptyList())
        if (places.isEmpty()) return@withContext emptyList()

        val scoredResults = places.mapNotNull { place ->
            val nameLower = place.name.lowercase()
            val score = when {
                nameLower.startsWith(q) -> 100
                nameLower.contains("[$q") -> 95 // Station code match e.g. [ERS
                nameLower.contains(q) -> 80
                place.keywords.contains(q) -> 50
                else -> 0
            }
            if (score > 0) place to score else null
        }
            .sortedByDescending { it.second }
            .take(limit)
            .map { (place, _) ->
                Destination(
                    name = place.name,
                    address = "⚡ " + place.address,
                    latitude = place.lat,
                    longitude = place.lon
                )
            }

        scoredResults
    }
}
