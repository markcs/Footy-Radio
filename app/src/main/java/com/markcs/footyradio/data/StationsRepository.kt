package com.markcs.footyradio.data

import android.content.Context
import com.markcs.footyradio.Config
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import kotlinx.serialization.encodeToString

class StationsRepository(
    private val context: Context,
    okHttpClient: OkHttpClient
) {
    private val prefs = context.getSharedPreferences("footy_radio_prefs", Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(OkHttp) {
        engine {
            preconfigured = okHttpClient
        }
        install(ContentNegotiation) {
            json(json)
        }
    }

    fun getStationsUrl(): String {
        return prefs.getString("custom_stations_url", Config.stationsURL) ?: Config.stationsURL
    }

    fun setStationsUrl(url: String) {
        prefs.edit().putString("custom_stations_url", url).apply()
    }

    fun resetStationsUrl() {
        prefs.edit().remove("custom_stations_url").apply()
    }

    suspend fun loadStations(remoteUrl: String? = null): List<RadioStation> = withContext(Dispatchers.IO) {
        val targetUrl = remoteUrl ?: getStationsUrl()
        val stations = try {
            val fetched = loadFromNetwork(targetUrl)
            saveToCache(fetched)
            fetched
        } catch (e: Exception) {
            val cached = loadFromCache()
            if (cached.isNotEmpty()) {
                cached
            } else {
                throw e
            }
        }
        
        val uniqueStations = stations.mapIndexed { index, station ->
            val finalId = if (station.id.isBlank()) "station_$index" else station.id
            
            station.copy(id = finalId).apply {
                resolvedImageUrl = if (imageURL.startsWith("http")) {
                    imageURL
                } else {
                    "file:///android_asset/stationImage.png"
                }
            }
        }
        
        uniqueStations
    }

    private suspend fun loadFromNetwork(url: String): List<RadioStation> {
        return client.get(url).body<StationsResponse>().station
    }

    private fun getCacheFile(): File {
        return File(context.filesDir, "cached_stations.json")
    }

    private fun saveToCache(stations: List<RadioStation>) {
        try {
            val response = StationsResponse(stations)
            val jsonString = json.encodeToString(response)
            getCacheFile().writeText(jsonString)
        } catch (e: Exception) {
            android.util.Log.e("StationsRepository", "Failed to cache stations", e)
        }
    }

    private fun loadFromCache(): List<RadioStation> {
        return try {
            val file = getCacheFile()
            if (file.exists()) {
                val jsonString = file.readText()
                json.decodeFromString<StationsResponse>(jsonString).station
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.e("StationsRepository", "Failed to load cached stations", e)
            emptyList()
        }
    }
}
