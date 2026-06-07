package com.markcs.footyradio.data

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Serializable
data class SquiggleResponse(
    val games: List<SquiggleGame> = emptyList()
)

@Serializable
data class SquiggleGame(
    val id: Int? = null,
    val year: Int? = null,
    val round: Int? = null,
    val hteam: String? = null, // Team Name (from standard API)
    val ateam: String? = null, // Team Name (from standard API)
    val hteamid: Int? = null,
    val ateamid: Int? = null,
    val date: String? = null,
    val tz: String? = null,
    val localtime: String? = null,
    val complete: Int? = null,
    val winner: String? = null, // Team Name (from standard API)
    val winnerteamid: Int? = null,
    val hscore: Int? = null,
    val ascore: Int? = null,
    val hgoals: Int? = null,
    val hbehinds: Int? = null,
    val agoals: Int? = null,
    val abehinds: Int? = null,
    val venue: String? = null,
    val timestr: String? = null,
    val updated: String? = null,
    val is_final: Int? = null,
    val is_grand_final: Int? = null
)

@Serializable
data class SSEGameEvent(
    val id: Int,
    val hscore: Int? = null,
    val ascore: Int? = null,
    val complete: Int? = null,
    val timestr: String? = null,
    val winner: Int? = null // Team ID (from SSE)
)

@Serializable
data class SSEScoreEvent(
    val gameid: Int,
    val complete: Int? = null,
    val timestr: String? = null,
    val score: SSEScoreDetail? = null
)

@Serializable
data class SSEScoreDetail(
    val hscore: Int,
    val ascore: Int,
    val hgoals: Int? = null,
    val hbehinds: Int? = null,
    val agoals: Int? = null,
    val abehinds: Int? = null
)

@Serializable
data class SquiggleWinnerEvent(
    val gameid: Int,
    val winner: Int // Team ID (from SSE)
)

@Serializable
data class SquiggleTimeStrEvent(
    val gameid: Int,
    val timestr: String
)

@Serializable
data class SquiggleCompleteEvent(
    val gameid: Int,
    val complete: Int
)

data class LiveScoreState(
    val scoreText: String,
    val hTeam: String,
    val aTeam: String
)

class SquiggleService(
    okHttpClient: OkHttpClient,
    scope: CoroutineScope
) {
    private val TAG = "SquiggleService"
    private val jsonConfig = Json { 
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val _activeScreenCount = kotlinx.coroutines.flow.MutableStateFlow(0)
    fun incrementActiveScreens() { 
        _activeScreenCount.value++ 
        Log.d(TAG, "Active screens: ${_activeScreenCount.value}")
    }
    fun decrementActiveScreens() { 
        _activeScreenCount.value = (_activeScreenCount.value - 1).coerceAtLeast(0)
        Log.d(TAG, "Active screens: ${_activeScreenCount.value}")
    }
    
    fun setScreenActive(active: Boolean) {
        if (active) incrementActiveScreens() else decrementActiveScreens()
    }

    private val client = HttpClient(OkHttp) {
        engine {
            preconfigured = okHttpClient.newBuilder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // Disable read timeout for SSE
                .build()
        }
        install(ContentNegotiation) {
            json(jsonConfig)
        }
        install(SSE)
        install(HttpTimeout) {
            requestTimeoutMillis = Long.MAX_VALUE
            socketTimeoutMillis = Long.MAX_VALUE
        }
        defaultRequest {
            header(HttpHeaders.UserAgent, "SwiftRadioAndroid - contact@campbellsmith.me")
        }
    }

    private val gamesMap = mutableMapOf<Int, SquiggleGame>()
    private val winnerReceivedTime = mutableMapOf<Int, Long>()
    private var initialFetchDone = false

    val liveScore: StateFlow<LiveScoreState?> = callbackFlow {
        val localSdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val dateSdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        val activeJob = scope.launch {
            _activeScreenCount.map { it > 0 }.distinctUntilChanged().collectLatest { active ->
                if (!active) {
                    trySend(null)
                    return@collectLatest
                }

                // 1. Initial Fetch (Standard API) - only once per app session when screen becomes active
                if (!initialFetchDone) {
                    while (isActive) {
                        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
                        Log.d(TAG, "Initial fetch for $currentYear")
                        val allGames = fetchGames(currentYear)
                        if (allGames != null) {
                            allGames.forEach { g -> g.id?.let { gamesMap[it] = g } }
                            initialFetchDone = true
                            break
                        }
                        Log.e(TAG, "Initial fetch failed, retrying in 30 seconds")
                        delay(30_000L)
                    }
                }

                // 2. Main loop while screen is active
                while (isActive) {
                    val now = System.currentTimeMillis()
                    val today = dateSdf.format(Date())
                    
                    val todayGames = gamesMap.values.filter { it.date?.startsWith(today) == true }
                    
                    // Remove games that were completed more than 15 minutes ago
                    val toRemove = winnerReceivedTime.filter { now - it.value > 15 * 60 * 1000L }.keys
                    toRemove.forEach { 
                        winnerReceivedTime.remove(it)
                    }

                    // Decide if we need SSE
                    val firstGameStart = todayGames.minOfOrNull { 
                        try { localSdf.parse(it.localtime ?: "")?.time ?: Long.MAX_VALUE } catch (e: Exception) { Long.MAX_VALUE }
                    } ?: Long.MAX_VALUE

                    val timeToFirstGame = firstGameStart - now
                    val startSSEThreshold = 5 * 60 * 1000L // 5 minutes before game
                    
                    val activeGames = todayGames.filter { (it.complete ?: 0) in 1..99 }
                    
                    if (activeGames.isEmpty() && timeToFirstGame > startSSEThreshold) {
                        // Update UI with static scores if any, then wait
                        trySend(formatScores(todayGames, winnerReceivedTime))
                        val waitTime = (timeToFirstGame - startSSEThreshold).coerceAtLeast(30_000L)
                        Log.d(TAG, "Waiting ${waitTime/1000}s for next game start")
                        delay(waitTime)
                        continue
                    }

                    // 3. Open SSE Connection
                    Log.d(TAG, "Opening SSE connection")
                    try {
                        client.sse("https://sse.squiggle.com.au/events") {
                            incoming.collect { event ->
                                var disconnectNeeded = false
                                when (event.event) {
                                    "game" -> {
                                        try {
                                            val g = jsonConfig.decodeFromString<SSEGameEvent>(event.data ?: "{}")
                                            val existing = gamesMap[g.id]
                                            if (existing != null) {
                                                // Find winner name if winner ID is provided
                                                val winnerName = if (g.winner != null) {
                                                    if (g.winner == existing.hteamid) existing.hteam
                                                    else if (g.winner == existing.ateamid) existing.ateam
                                                    else null
                                                } else existing.winner

                                                gamesMap[g.id] = existing.copy(
                                                    hscore = g.hscore ?: existing.hscore,
                                                    ascore = g.ascore ?: existing.ascore,
                                                    complete = g.complete ?: existing.complete,
                                                    timestr = g.timestr ?: existing.timestr,
                                                    winner = winnerName
                                                )
                                            }
                                            
                                            if (g.complete == 100 && !winnerReceivedTime.containsKey(g.id)) {
                                                winnerReceivedTime[g.id] = System.currentTimeMillis()
                                            }
                                        } catch (e: Exception) { Log.e(TAG, "Parse game error", e) }
                                    }
                                    "score" -> {
                                        try {
                                            val update = jsonConfig.decodeFromString<SSEScoreEvent>(event.data ?: "{}")
                                            gamesMap[update.gameid]?.let { existing ->
                                                gamesMap[update.gameid] = existing.copy(
                                                    hscore = update.score?.hscore ?: existing.hscore,
                                                    ascore = update.score?.ascore ?: existing.ascore,
                                                    complete = update.complete ?: existing.complete,
                                                    timestr = update.timestr ?: existing.timestr
                                                )
                                            }
                                        } catch (e: Exception) { Log.e(TAG, "Parse score error", e) }
                                    }
                                    "winner" -> {
                                        try {
                                            val win = jsonConfig.decodeFromString<SquiggleWinnerEvent>(event.data ?: "{}")
                                            gamesMap[win.gameid]?.let { existing ->
                                                val winnerName = if (win.winner == existing.hteamid) existing.hteam
                                                    else if (win.winner == existing.ateamid) existing.ateam
                                                    else existing.winner

                                                gamesMap[win.gameid] = existing.copy(winner = winnerName, complete = 100)
                                                if (!winnerReceivedTime.containsKey(win.gameid)) {
                                                    winnerReceivedTime[win.gameid] = System.currentTimeMillis()
                                                }
                                            }
                                            
                                            // Disconnect if only one game active
                                            val stillActive = gamesMap.values.filter { it.date?.startsWith(today) == true && (it.complete ?: 0) < 100 }
                                            if (stillActive.isEmpty()) {
                                                Log.d(TAG, "Winner received and no more active games. Disconnecting SSE.")
                                                disconnectNeeded = true
                                            }
                                        } catch (e: Exception) { Log.e(TAG, "Parse winner error", e) }
                                    }
                                    "timestr" -> {
                                        try {
                                            val t = jsonConfig.decodeFromString<SquiggleTimeStrEvent>(event.data ?: "{}")
                                            gamesMap[t.gameid]?.let { existing ->
                                                gamesMap[t.gameid] = existing.copy(timestr = t.timestr)
                                            }
                                        } catch (e: Exception) { Log.e(TAG, "Parse timestr error", e) }
                                    }
                                    "complete" -> {
                                        try {
                                            val c = jsonConfig.decodeFromString<SquiggleCompleteEvent>(event.data ?: "{}")
                                            gamesMap[c.gameid]?.let { existing ->
                                                gamesMap[c.gameid] = existing.copy(complete = c.complete)
                                                if (c.complete == 100 && !winnerReceivedTime.containsKey(c.gameid)) {
                                                    winnerReceivedTime[c.gameid] = System.currentTimeMillis()
                                                }
                                            }
                                        } catch (e: Exception) { Log.e(TAG, "Parse complete error", e) }
                                    }
                                }

                                trySend(formatScores(gamesMap.values.filter { it.date?.startsWith(today) == true }, winnerReceivedTime))
                                if (disconnectNeeded) this@sse.cancel()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "SSE Error: ${e.message}")
                        delay(30_000L)
                    }
                }
            }
        }
        awaitClose { activeJob.cancel() }
    }.stateIn(
        scope = scope,
        started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    suspend fun fetchGames(year: Int): List<SquiggleGame>? {
        return try {
            Log.d(TAG, "Fetching games for year $year")
            val response: SquiggleResponse = client.get("https://api.squiggle.com.au/?q=games;year=$year").body()
            response.games
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch games from standard API", e)
            null
        }
    }

    fun formatScores(games: List<SquiggleGame>, winnerTimes: Map<Int, Long>): LiveScoreState? {
        val now = System.currentTimeMillis()
        val toShow = games.filter { g ->
            val complete = g.complete ?: 0
            if (complete == 0) return@filter false
            if (complete < 100) return@filter true
            
            val winTime = winnerTimes[g.id] ?: return@filter false
            now - winTime < 15 * 60 * 1000L
        }

        if (toShow.isEmpty()) return null

        val sorted = toShow.sortedByDescending { it.complete ?: 0 }
        val scoreText = sorted.joinToString(" | ") { game ->
            val hTeam = game.hteam ?: "Unknown"
            val aTeam = game.ateam ?: "Unknown"
            val hScore = game.hscore ?: 0
            val aScore = game.ascore ?: 0
            val timeStr = game.timestr ?: ""
            val scoreStr = "$hTeam ($hScore) v $aTeam ($aScore)"
            if (timeStr.isNotBlank()) "$scoreStr $timeStr" else scoreStr
        }
        // Use first game's teams for the logo (most active game)
        return LiveScoreState(
            scoreText = scoreText,
            hTeam = sorted.first().hteam ?: "",
            aTeam = sorted.first().ateam ?: ""
        )
    }

    @Deprecated("Use fetchGames and handle logic in caller", ReplaceWith("fetchGames(year)"))
    suspend fun fetchLiveScore(year: Int): String? {
        val games = fetchGames(year) ?: return null
        return formatScores(games, emptyMap())?.scoreText
    }
}
