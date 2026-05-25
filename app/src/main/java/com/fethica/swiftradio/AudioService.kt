package com.fethica.swiftradio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.fethica.swiftradio.data.RadioStation
import com.fethica.swiftradio.data.StationsRepository
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class AudioService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private var player: ExoPlayer? = null
    
    private var retryJob: Job? = null
    private var bufferingRecoveryJob: Job? = null
    private var stallRecoveryJob: Job? = null
    
    private var retryCount = 0
    private var lastObservedPositionMs: Long = 0L
    private var lastPositionRealtimeMs: Long = 0L
    private val audioManager: AudioManager by lazy {
        getSystemService(AudioManager::class.java)
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main)
    private var stations: List<RadioStation> = emptyList()
    private var browseMediaItems: List<MediaItem> = emptyList()
    private val stationsLoaded = CompletableDeferred<Unit>()

    // Cache local asset artwork as byte arrays so Android Auto can display them
    private val artworkCache = mutableMapOf<String, ByteArray?>()

    companion object {
        private const val ROOT_ID = "root"
        private const val STATIONS_ID = "stations"
        private const val TAG = "AudioService"
    }

    private val playerListener = object : Player.Listener {
        override fun onMetadata(metadata: Metadata) {
            val exoPlayer = player ?: return
            serviceScope.launch(Dispatchers.Default) {
                val icyTitle = extractIcyTitle(metadata) ?: return@launch
                val parsedMetadata = buildTrackMetadataFromIcy(icyTitle)
                
                withContext(Dispatchers.Main) {
                    if (trackMetadataEquivalent(exoPlayer.playlistMetadata, parsedMetadata)) return@withContext
                    Log.d(TAG, "Svc ICY metadata raw='$icyTitle' title='${parsedMetadata.title}' artist='${parsedMetadata.artist}'")
                    
                    // Update global playlist metadata for the phone UI
                    exoPlayer.setPlaylistMetadata(parsedMetadata)
                    
                    // Update the currently playing MediaItem to force a UI refresh on Android Auto
                    val currentItem = exoPlayer.currentMediaItem
                    if (currentItem != null) {
                        val baseStationMeta = currentItem.mediaMetadata
                        val newMetadataBuilder = baseStationMeta.buildUpon()
                            .setTitle(parsedMetadata.title ?: baseStationMeta.title)
                        
                        if (parsedMetadata.artist != null) {
                            newMetadataBuilder.setArtist(parsedMetadata.artist)
                        }

                        if (baseStationMeta.artworkUri != null) {
                            newMetadataBuilder.setArtworkUri(baseStationMeta.artworkUri)
                        }
                        if (baseStationMeta.artworkData != null) {
                            newMetadataBuilder.setArtworkData(baseStationMeta.artworkData, baseStationMeta.artworkDataType)
                        }
                        
                        val newItem = currentItem.buildUpon()
                            .setMediaMetadata(newMetadataBuilder.build())
                            .build()
                            
                        exoPlayer.replaceMediaItem(exoPlayer.currentMediaItemIndex, newItem)
                    }
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val exoPlayer = player ?: return
            // Ignore transitions caused purely by metadata updates (replaceMediaItem)
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return

            Log.d(TAG, "Svc media item transition reason=$reason uri='${mediaItem?.localConfiguration?.uri}'")
            cancelRetry()
            retryCount = 0
            if (!isEmptyTrackMetadata(exoPlayer.playlistMetadata)) {
                exoPlayer.setPlaylistMetadata(MediaMetadata.Builder().build())
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Svc player error: ${error.errorCodeName} ${error.message}", error)
            retryCurrentItem("player_error")
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val stateName = when (playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN"
            }
            val exoPlayer = player
            Log.d(TAG, "Svc playback state=$stateName playWhenReady=${exoPlayer?.playWhenReady} isPlaying=${exoPlayer?.isPlaying}")
            
            when (playbackState) {
                Player.STATE_READY -> {
                    retryCount = 0
                    cancelBufferingRecovery()
                    startStallRecovery()
                }
                Player.STATE_BUFFERING -> scheduleBufferingRecovery()
                Player.STATE_ENDED -> {
                    cancelStallRecovery()
                    retryCurrentItem("state_ended")
                }
                Player.STATE_IDLE -> {
                    cancelBufferingRecovery()
                    cancelStallRecovery()
                    if (exoPlayer?.playWhenReady == true) {
                        retryCurrentItem("state_idle")
                    }
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val exoPlayer = player
            if (!isPlaying &&
                exoPlayer?.playWhenReady == true &&
                exoPlayer.playbackState == Player.STATE_READY &&
                exoPlayer.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
            ) {
                retryCurrentItem("ready_not_playing")
                return
            }
            if (!isPlaying) {
                startStallRecovery()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) startStallRecovery() else cancelStallRecovery()
        }
    }

    private val libraryCallback = object : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .setTitle("Swift Radio")
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (parentId != ROOT_ID && parentId != STATIONS_ID) {
                return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            }
            return Futures.immediateFuture(LibraryResult.ofItemList(browseMediaItems, params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = browseMediaItems.firstOrNull { it.mediaId == mediaId }
                ?: return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            return Futures.immediateFuture(LibraryResult.ofItem(item, null))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            val allHaveUri = mediaItems.all { it.localConfiguration?.uri != null }
            if (allHaveUri) {
                return Futures.immediateFuture(mediaItems)
            }

            val future = SettableFuture.create<List<MediaItem>>()
            serviceScope.launch {
                try {
                    stationsLoaded.await()
                    future.set(resolveAutoMediaItems(mediaItems))
                } catch (e: Exception) {
                    future.setException(e)
                }
            }
            return future
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Use a standard web browser User-Agent to bypass 403 Forbidden on servers like Live365
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(mapOf("Icy-MetaData" to "1"))

        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        exoPlayer.setAudioAttributes(audioAttributes, true)
        exoPlayer.volume = 1f
        exoPlayer.repeatMode = Player.REPEAT_MODE_ALL
        exoPlayer.addListener(playerListener)
        player = exoPlayer
        
        mediaSession = MediaLibrarySession.Builder(this, exoPlayer, libraryCallback).build()

        loadStationsForBrowse()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaSession
    }

    override fun onDestroy() {
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        player?.removeListener(playerListener)
        cancelRetry()
        cancelBufferingRecovery()
        cancelStallRecovery()
        player = null
        mediaSession = null
        super.onDestroy()
    }

    private fun loadStationsForBrowse() {
        val app = application as SwiftRadioApplication
        val repository = app.stationsRepository

        serviceScope.launch {
            try {
                val remoteUrl = if (Config.useLocalStations) null else Config.stationsURL
                stations = repository.loadStations(remoteUrl)
                browseMediaItems = stations.mapIndexed { index, station ->
                    val metadataBuilder = MediaMetadata.Builder()
                        .setTitle(station.name)
                        .setArtist(station.desc)
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        
                    applyArtworkToMetadata(station.resolvedImageUrl, metadataBuilder)
                        
                    MediaItem.Builder()
                        .setMediaId("station_$index")
                        .setMediaMetadata(metadataBuilder.build())
                        .build()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load stations for browse", e)
            }
            stationsLoaded.complete(Unit)
            mediaSession?.let { session ->
                session.connectedControllers.forEach { controller ->
                    session.notifyChildrenChanged(controller, ROOT_ID, browseMediaItems.size, null)
                }
            }
        }
    }

    private fun resolveAutoMediaItems(mediaItems: List<MediaItem>): List<MediaItem> {
        val requestedId = mediaItems.firstOrNull()?.mediaId
        val selectedIndex = browseMediaItems.indexOfFirst { it.mediaId == requestedId }
            .takeIf { it >= 0 } ?: 0

        val playableItems = stations.mapIndexed { index, station ->
            val metadataBuilder = MediaMetadata.Builder()
                .setTitle(station.name)
                .setArtist(station.desc)
                .setIsPlayable(true)
                
            applyArtworkToMetadata(station.resolvedImageUrl, metadataBuilder)

            MediaItem.Builder()
                .setMediaId("station_$index")
                .setUri(station.streamURL)
                .setMediaMetadata(metadataBuilder.build())
                .build()
        }

        return playableItems.subList(selectedIndex, playableItems.size) +
            playableItems.subList(0, selectedIndex)
    }

    /**
     * Android Auto cannot read "file:///android_asset/". 
     * To fix this, we load the asset as a Bitmap byte array and embed it directly in the metadata.
     */
    private fun applyArtworkToMetadata(imageUrl: String, builder: MediaMetadata.Builder) {
        if (imageUrl.startsWith("http")) {
            // Android Auto can download HTTP URLs
            builder.setArtworkUri(Uri.parse(imageUrl))
            builder.setArtworkData(null, null)
        } else if (imageUrl.startsWith("file:///android_asset/")) {
            // Extract file name and load bitmap into memory
            val assetName = imageUrl.replace("file:///android_asset/", "")
            
            val bytes = artworkCache.getOrPut(assetName) {
                try {
                    val inputStream = assets.open(assetName)
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    val outputStream = ByteArrayOutputStream()
                    // Compress to reduce IPC transaction size (critical for Android Auto)
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                    outputStream.toByteArray()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load asset artwork for Android Auto: $assetName", e)
                    null
                }
            }
            
            if (bytes != null) {
                builder.setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                builder.setArtworkUri(null)
            }
        }
    }

    private fun extractIcyTitle(metadata: Metadata): String? {
        for (index in 0 until metadata.length()) {
            val entry = metadata[index]
            if (entry is IcyInfo) {
                val title = entry.title?.trim()
                if (!title.isNullOrBlank()) return title
            }
        }
        return null
    }

    private fun buildTrackMetadataFromIcy(icyTitle: String): MediaMetadata {
        val (artist, title) = parseArtistAndTitle(icyTitle)
        val normalizedTitle = title.ifBlank { icyTitle.trim() }
        return MediaMetadata.Builder()
            .setTitle(normalizedTitle.ifBlank { null })
            .setArtist(artist.ifBlank { null })
            .build()
    }

    private fun parseArtistAndTitle(value: String): Pair<String, String> {
        val separators = listOf(" - ", " – ", " — ")
        separators.forEach { separator ->
            val separatorIndex = value.indexOf(separator)
            if (separatorIndex > 0 && separatorIndex < value.length - separator.length) {
                val artist = value.substring(0, separatorIndex).trim()
                val title = value.substring(separatorIndex + separator.length).trim()
                if (artist.isNotBlank() && title.isNotBlank()) {
                    return artist to title
                }
            }
        }
        return "" to value.trim()
    }

    private fun trackMetadataEquivalent(left: MediaMetadata, right: MediaMetadata): Boolean {
        return left.title?.toString().orEmpty() == right.title?.toString().orEmpty() &&
            left.artist?.toString().orEmpty() == right.artist?.toString().orEmpty()
    }

    private fun isEmptyTrackMetadata(metadata: MediaMetadata): Boolean {
        return metadata.title.isNullOrBlank() && metadata.artist.isNullOrBlank()
    }

    private fun retryCurrentItem(reason: String) {
        val exoPlayer = player ?: return
        if (exoPlayer.mediaItemCount <= 0) return

        retryCount += 1
        val delayMs = when {
            retryCount <= 2 -> 0L
            retryCount <= 5 -> 1000L
            else -> 3000L
        }

        cancelRetry()
        retryJob = serviceScope.launch {
            delay(delayMs)
            val activePlayer = player ?: return@launch
            if (activePlayer.mediaItemCount <= 0) return@launch
            val index = activePlayer.currentMediaItemIndex.coerceIn(0, activePlayer.mediaItemCount - 1)
            activePlayer.stop()
            activePlayer.seekTo(index, 0)
            activePlayer.prepare()
            activePlayer.play()
        }
    }

    private fun cancelRetry() {
        retryJob?.cancel()
        retryJob = null
    }

    private fun scheduleBufferingRecovery() {
        cancelBufferingRecovery()
        bufferingRecoveryJob = serviceScope.launch {
            delay(15_000L)
            val exoPlayer = player ?: return@launch
            if (exoPlayer.playbackState == Player.STATE_BUFFERING && exoPlayer.playWhenReady) {
                retryCurrentItem("buffer_timeout")
            }
        }
    }

    private fun startStallRecovery() {
        val exoPlayer = player ?: return
        lastObservedPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
        lastPositionRealtimeMs = SystemClock.elapsedRealtime()
        scheduleStallCheck()
    }

    private fun scheduleStallCheck() {
        cancelStallRecovery()
        stallRecoveryJob = serviceScope.launch {
            delay(5_000L)
            val exoPlayer = player ?: return@launch
            if (!exoPlayer.playWhenReady || exoPlayer.playbackState != Player.STATE_READY) return@launch

            val nowRealtime = SystemClock.elapsedRealtime()
            val currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
            if (currentPosition > lastObservedPositionMs + 250L) {
                lastObservedPositionMs = currentPosition
                lastPositionRealtimeMs = nowRealtime
                scheduleStallCheck()
                return@launch
            }

            if (nowRealtime - lastPositionRealtimeMs >= 15_000L) {
                retryCurrentItem("stall_ready")
                return@launch
            }
            scheduleStallCheck()
        }
    }

    private fun cancelBufferingRecovery() {
        bufferingRecoveryJob?.cancel()
        bufferingRecoveryJob = null
    }

    private fun cancelStallRecovery() {
        stallRecoveryJob?.cancel()
        stallRecoveryJob = null
    }

    private fun logAudioOutputState(tag: String) {
        val exoPlayer = player ?: return
        val musicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val musicMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        Log.d(TAG, "Svc audio $tag playerVolume=${exoPlayer.volume} deviceMusicVolume=$musicVolume/$musicMax")
    }
}
