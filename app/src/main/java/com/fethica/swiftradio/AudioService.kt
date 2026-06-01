package com.fethica.swiftradio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.SessionError
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.fethica.swiftradio.data.RadioStation
import com.fethica.swiftradio.data.StationsRepository
import com.fethica.swiftradio.data.SquiggleService
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import okhttp3.Request
import okhttp3.OkHttpClient
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(UnstableApi::class)
class AudioService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private var player: Player? = null
    private var basePlayer: ExoPlayer? = null
    
    private var retryJob: Job? = null
    private var bufferingRecoveryJob: Job? = null
    private var stallRecoveryJob: Job? = null
    
    private var currentLiveScore: String? = null
    private var currentIcyTitle: String? = null
    private var currentManifestMetadata: MediaMetadata? = null
    
    private var squiggleJob: Job? = null
    
    private var retryCount = 0
    private var lastObservedPositionMs: Long = 0L
    private var lastPositionRealtimeMs: Long = 0L
    private var lastObservedSequenceNumber: Long = -1L
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
        private const val ROOT_ID = "/"
        private const val TAG = "AudioService"
    }

    private val playerListener = object : Player.Listener {
        override fun onMetadata(metadata: Metadata) {
            serviceScope.launch(Dispatchers.Default) {
                val icyTitle = extractIcyTitle(metadata)
                if (icyTitle != null) {
                    currentIcyTitle = icyTitle
                }
                updateDisplayMetadata()
            }
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            // Trigger update when stream metadata (like ID3) changes
            updateDisplayMetadata()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            val manifest = basePlayer?.currentManifest
            if (manifest is HlsManifest) {
                val targetDurationUs = manifest.mediaPlaylist.targetDurationUs
                val targetDurationMs = targetDurationUs / 1000
                Log.d(TAG, "Svc HLS target duration: ${targetDurationMs}ms")
                
                // Parse SCA-style metadata from #EXTINF attributes
                val newManifestMeta = parseHlsManifestMetadata(manifest)
                if (newManifestMeta != null && newManifestMeta != currentManifestMetadata) {
                    currentManifestMetadata = newManifestMeta
                    updateDisplayMetadata()
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Ignore transitions caused purely by metadata updates (replaceMediaItem)
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return

            Log.d(TAG, "Svc media item transition reason=$reason uri='${mediaItem?.localConfiguration?.uri}'")
            cancelRetry()
            retryCount = 0
            currentIcyTitle = null
            currentManifestMetadata = null
            player?.setPlaylistMetadata(MediaMetadata.Builder().build())
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
            Log.d(TAG, "Svc playback state=$stateName playWhenReady=${player?.playWhenReady} isPlaying=${player?.isPlaying}")
            
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
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) {
                startStallRecovery()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) startStallRecovery() else cancelStallRecovery()
        }
    }

    private fun startSquigglePolling() {
        if (squiggleJob?.isActive == true) return
        squiggleJob = serviceScope.launch {
            val app = application as SwiftRadioApplication
            app.squiggleService.liveScore.collect { score ->
                if (currentLiveScore != score) {
                    currentLiveScore = score
                    updateDisplayMetadata()
                }
            }
        }
    }

    private fun stopSquigglePolling() {
        squiggleJob?.cancel()
        squiggleJob = null
    }

    private var controllerCount = 0

    private val libraryCallback = object : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            controllerCount++
            serviceScope.launch {
                (application as SwiftRadioApplication).squiggleService.incrementActiveScreens()
                startSquigglePolling()
            }
            // Allow all controllers and include library commands
            val connectionResult = super.onConnect(session, controller)
            val availableSessionCommands = connectionResult.availableSessionCommands.buildUpon()
            // Add library commands explicitly
            availableSessionCommands.add(androidx.media3.session.SessionCommand.COMMAND_CODE_LIBRARY_GET_CHILDREN)
            availableSessionCommands.add(androidx.media3.session.SessionCommand.COMMAND_CODE_LIBRARY_GET_ITEM)
            availableSessionCommands.add(androidx.media3.session.SessionCommand.COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT)
            availableSessionCommands.add(androidx.media3.session.SessionCommand.COMMAND_CODE_LIBRARY_SUBSCRIBE)
            availableSessionCommands.add(androidx.media3.session.SessionCommand.COMMAND_CODE_LIBRARY_UNSUBSCRIBE)
            
            return MediaSession.ConnectionResult.accept(
                availableSessionCommands.build(),
                connectionResult.availablePlayerCommands
            )
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            controllerCount = (controllerCount - 1).coerceAtLeast(0)
            serviceScope.launch {
                (application as SwiftRadioApplication).squiggleService.decrementActiveScreens()
                if (controllerCount == 0) {
                    stopSquigglePolling()
                }
            }
            super.onDisconnected(session, controller)
        }

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
            if (parentId != ROOT_ID) {
                return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            }

            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch {
                try {
                    stationsLoaded.await()
                    future.set(LibraryResult.ofItemList(ImmutableList.copyOf(browseMediaItems), params))
                } catch (e: Exception) {
                    future.setException(e)
                }
            }
            return future
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val future = SettableFuture.create<LibraryResult<MediaItem>>()
            serviceScope.launch {
                try {
                    stationsLoaded.await()
                    val item = browseMediaItems.firstOrNull { it.mediaId == mediaId }
                    if (item != null) {
                        future.set(LibraryResult.ofItem(item, null))
                    } else {
                        future.set(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
                    }
                } catch (e: Exception) {
                    future.setException(e)
                }
            }
            return future
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
        initializePlayer()
        loadStationsForBrowse()
    }

    private fun initializePlayer() {
        val app = application as SwiftRadioApplication
        
        // Use OkHttp for better connection pooling and HLS stability
        val httpDataSourceFactory = OkHttpDataSource.Factory(app.sharedOkHttpClient)
            .setUserAgent("SwiftRadioAndroid - contact@campbellsmith.me")

        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
        
        // Revert to default load control for dynamic buffering adjustment as requested.
        val loadControl = DefaultLoadControl.Builder().build()

        // Enable chunkless preparation for more stable HLS segment transitions
        val hlsMediaSourceFactory = HlsMediaSource.Factory(dataSourceFactory)
            .setAllowChunklessPreparation(true)
            .setMetadataType(HlsMediaSource.METADATA_TYPE_ID3)

        // Disable video to save system resources and avoid "resource: 6" errors
        val trackSelector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                .setDisabledTrackTypes(setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_TEXT))
                .build()
        }

        val defaultMediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)
            .setLiveTargetOffsetMs(C.TIME_UNSET) // Use manifest target duration (dynamic)

        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        val mediaSourceFactory = object : androidx.media3.exoplayer.source.MediaSource.Factory {
            override fun setDrmSessionManagerProvider(drmSessionManagerProvider: androidx.media3.exoplayer.drm.DrmSessionManagerProvider) = apply {
                defaultMediaSourceFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
                hlsMediaSourceFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
            }
            override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy) = apply {
                defaultMediaSourceFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                hlsMediaSourceFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            }
            override fun getSupportedTypes(): IntArray = defaultMediaSourceFactory.supportedTypes
            override fun createMediaSource(mediaItem: MediaItem): androidx.media3.exoplayer.source.MediaSource {
                val type = androidx.media3.common.util.Util.inferContentType(
                    mediaItem.localConfiguration?.uri ?: Uri.EMPTY,
                    mediaItem.localConfiguration?.mimeType
                )
                return if (type == C.CONTENT_TYPE_HLS) {
                    hlsMediaSourceFactory.createMediaSource(mediaItem)
                } else {
                    defaultMediaSourceFactory.createMediaSource(mediaItem)
                }
            }
        }

        val exoPlayer = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        exoPlayer.setAudioAttributes(audioAttributes, true)
        exoPlayer.volume = 1f
        exoPlayer.repeatMode = Player.REPEAT_MODE_ALL
        exoPlayer.addListener(playerListener)
        
        basePlayer = exoPlayer
        // Wrap ExoPlayer in our ForwardingPlayer to handle metadata overrides without codec churn
        val wrappedPlayer = MetadataForwardingPlayer(exoPlayer)
        player = wrappedPlayer
        
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = intent?.let {
            android.app.PendingIntent.getActivity(
                this, 0, it, android.app.PendingIntent.FLAG_IMMUTABLE
            )
        }

        // Create or update the session with the new player
        val session = mediaSession
        if (session == null) {
            mediaSession = MediaLibrarySession.Builder(this, wrappedPlayer, libraryCallback).apply {
                pendingIntent?.let { setSessionActivity(it) }
            }.build()
        } else {
            session.player = wrappedPlayer
        }
    }

    private fun updateDisplayMetadata() {
        val activePlayer = player ?: return
        serviceScope.launch(Dispatchers.Main) {
            val icyTitle = currentIcyTitle
            val liveScore = currentLiveScore
            val manifestMeta = currentManifestMetadata
            
            val currentItem = activePlayer.currentMediaItem ?: return@launch
            val streamMeta = currentItem.mediaMetadata
            
            // Filter out Triple M's useless HLS metadata that changes every 5 seconds.
            // This prevents Resource 6 errors caused by constant codec re-allocations.
            if (icyTitle == null && liveScore == null && manifestMeta == null && 
                streamMeta.title?.toString()?.startsWith("Asset Link") == true) {
                return@launch
            }

            val parsedIcyMeta = if (!icyTitle.isNullOrBlank()) {
                buildTrackMetadataFromIcy(icyTitle)
            } else {
                MediaMetadata.Builder().build()
            }
            
            // Priority: 1. Live Scores, 2. ICY Metadata, 3. Manifest Metadata, 4. Stream ID3 Metadata
            val displayTitle = liveScore 
                ?: parsedIcyMeta.title?.toString() 
                ?: manifestMeta?.title?.toString()
                
            val displayArtist = if (liveScore != null) {
                "" // Hide artist when showing scores
            } else {
                parsedIcyMeta.artist?.toString() ?: manifestMeta?.artist?.toString()
            }
            
            // Update global playlist metadata for the phone UI
            val playlistMeta = MediaMetadata.Builder()
                .setTitle(displayTitle)
                .setArtist(displayArtist)
                .build()
            activePlayer.setPlaylistMetadata(playlistMeta)
            
            // Use the ForwardingPlayer to update metadata without playlist churn
            val wrappedPlayer = player as? MetadataForwardingPlayer
            if (wrappedPlayer != null) {
                // Only override if we have something better than the base stream metadata
                if (displayTitle != null || displayArtist != null) {
                    wrappedPlayer.setOverrideMetadata(playlistMeta)
                } else {
                    wrappedPlayer.setOverrideMetadata(null)
                }
            }
        }
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
        stopSquigglePolling()
        player = null
        basePlayer = null
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
                
                val items = mutableListOf<MediaItem>()
                for ((index, station) in stations.withIndex()) {
                    val metadataBuilder = MediaMetadata.Builder()
                        .setTitle(station.name)
                        .setArtist(station.desc)
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        
                    applyArtworkToMetadata(station.resolvedImageUrl, metadataBuilder)
                        
                    items.add(MediaItem.Builder()
                        .setMediaId("station_$index")
                        .setMediaMetadata(metadataBuilder.build())
                        .build())
                }
                browseMediaItems = items
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

    private suspend fun resolveAutoMediaItems(mediaItems: List<MediaItem>): List<MediaItem> {
        val requestedId = mediaItems.firstOrNull()?.mediaId
        val selectedIndex = browseMediaItems.indexOfFirst { it.mediaId == requestedId }
            .takeIf { it >= 0 } ?: 0

        val playableItems = mutableListOf<MediaItem>()
        for ((index, station) in stations.withIndex()) {
            val metadataBuilder = MediaMetadata.Builder()
                .setTitle(station.name)
                .setArtist(station.desc)
                .setIsPlayable(true)
                
            applyArtworkToMetadata(station.resolvedImageUrl, metadataBuilder)

            playableItems.add(MediaItem.Builder()
                .setMediaId("station_$index")
                .setUri(station.streamURL)
                .setMediaMetadata(metadataBuilder.build())
                .build())
        }

        return playableItems.subList(selectedIndex, playableItems.size) +
            playableItems.subList(0, selectedIndex)
    }

    /**
     * Android Auto and some controllers cannot read "file:///android_asset/" or might fail with remote URIs.
     * To fix this, we load the artwork as a Bitmap byte array and embed it directly in the metadata.
     */
    private suspend fun applyArtworkToMetadata(imageUrl: String, builder: MediaMetadata.Builder) {
        if (imageUrl.isBlank()) return

        val bytes = artworkCache[imageUrl] ?: withContext(Dispatchers.IO) {
            if (imageUrl.startsWith("http")) {
                try {
                    val app = application as SwiftRadioApplication
                    val request = okhttp3.Request.Builder().url(imageUrl).build()
                    app.sharedOkHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            response.body.bytes()
                        } else null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load remote artwork: $imageUrl", e)
                    null
                }
            } else if (imageUrl.startsWith("file:///android_asset/")) {
                val assetName = imageUrl.replace("file:///android_asset/", "")
                try {
                    val inputStream = assets.open(assetName)
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    val outputStream = ByteArrayOutputStream()
                    // Compress to reduce IPC transaction size (critical for Android Auto)
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                    outputStream.toByteArray()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load asset artwork: $assetName", e)
                    null
                }
            } else null
        }

        if (bytes != null) {
            artworkCache[imageUrl] = bytes
            builder.setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }
        
        // Always set URI as well for controllers that prefer it or as fallback
        if (imageUrl.startsWith("http")) {
            builder.setArtworkUri(Uri.parse(imageUrl))
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
        retryCount += 1
        // Increase delay to give the system more time to release resources
        val delayMs = when {
            retryCount <= 1 -> 3000L 
            retryCount <= 2 -> 5000L
            else -> 10000L
        }

        Log.d(TAG, "Svc retrying current item reason=$reason count=$retryCount delay=${delayMs}ms")
        cancelRetry()
        retryJob = serviceScope.launch {
            val activePlayer = player ?: return@launch
            val currentItem = activePlayer.currentMediaItem ?: return@launch

            // Robust reset: Release player completely to clear hardware codec resources (Resource 6)
            try {
                player?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing player during retry", e)
            }
            player = null
            basePlayer = null
            
            delay(delayMs)
            
            // Re-initialize from scratch
            initializePlayer()
            val freshPlayer = player ?: return@launch
            freshPlayer.setMediaItem(currentItem)
            freshPlayer.prepare()
            freshPlayer.play()
        }
    }

    private fun cancelRetry() {
        retryJob?.cancel()
        retryJob = null
    }

    private fun scheduleBufferingRecovery() {
        cancelBufferingRecovery()
        bufferingRecoveryJob = serviceScope.launch {
            delay(20_000L) // Conservative timeout for initial buffering
            val activePlayer = player ?: return@launch
            if (activePlayer.playbackState == Player.STATE_BUFFERING && activePlayer.playWhenReady) {
                retryCurrentItem("buffer_timeout")
            }
        }
    }

    private fun startStallRecovery() {
        val activePlayer = player ?: return
        lastObservedPositionMs = activePlayer.currentPosition.coerceAtLeast(0L)
        lastPositionRealtimeMs = SystemClock.elapsedRealtime()
        
        val manifest = basePlayer?.currentManifest
        if (manifest is HlsManifest) {
            lastObservedSequenceNumber = manifest.mediaPlaylist.mediaSequence
        } else {
            lastObservedSequenceNumber = -1L
        }
        
        scheduleStallCheck()
    }

    private fun scheduleStallCheck() {
        cancelStallRecovery()
        stallRecoveryJob = serviceScope.launch {
            delay(5_000L)
            val activePlayer = player ?: return@launch
            if (!activePlayer.playWhenReady || 
                (activePlayer.playbackState != Player.STATE_READY && activePlayer.playbackState != Player.STATE_BUFFERING)) {
                return@launch
            }

            val nowRealtime = SystemClock.elapsedRealtime()
            val currentPosition = activePlayer.currentPosition.coerceAtLeast(0L)
            val bufferedPosition = activePlayer.bufferedPosition.coerceAtLeast(0L)
            
            val manifest = basePlayer?.currentManifest
            val currentSequence = if (manifest is HlsManifest) manifest.mediaPlaylist.mediaSequence else -1L
            val targetDurationMs = if (manifest is HlsManifest) manifest.mediaPlaylist.targetDurationUs / 1000 else 6000L
            
            // If HLS sequence has advanced, the playlist is updating. This is the strongest sign of health.
            if (currentSequence != -1L && currentSequence > lastObservedSequenceNumber) {
                lastObservedSequenceNumber = currentSequence
                lastPositionRealtimeMs = nowRealtime
                lastObservedPositionMs = currentPosition
                scheduleStallCheck()
                return@launch
            }

            // If position is moving significantly, we are good.
            if (currentPosition > lastObservedPositionMs + 200L) {
                lastObservedPositionMs = currentPosition
                lastPositionRealtimeMs = nowRealtime
                scheduleStallCheck()
                return@launch
            }
            
            // If we are in READY and position is not moving, but we are near the live edge, it might be normal.
            // We use a very conservative threshold (30s or 4x target duration) to avoid false positives.
            val stallThresholdMs = (targetDurationMs * 4).coerceAtLeast(30_000L)
            val stallDuration = nowRealtime - lastPositionRealtimeMs
            
            if (stallDuration >= stallThresholdMs) {
                Log.w(TAG, "Svc stall detected! duration=${stallDuration}ms position=$currentPosition buffer=$bufferedPosition seq=$currentSequence threshold=${stallThresholdMs}ms")
                retryCurrentItem("stall_ready")
                return@launch
            }
            
            if (stallDuration % 5000L < 500L) {
                Log.d(TAG, "Svc monitoring potential stall: duration=${stallDuration}ms pos=$currentPosition buffer=$bufferedPosition seq=$currentSequence")
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
        val activePlayer = player ?: return
        val musicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val musicMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        Log.d(TAG, "Svc audio $tag playerVolume=${activePlayer.volume} deviceMusicVolume=$musicVolume/$musicMax")
    }

    /**
     * A ForwardingPlayer that allows us to override the metadata of the current media item
     * without modifying the actual player playlist. This prevents codec re-allocations
     * and playback interruptions during frequent metadata updates (like song titles or scores).
     */
    private inner class MetadataForwardingPlayer(val basePlayer: Player) : androidx.media3.common.ForwardingPlayer(basePlayer) {
        private var overrideMetadata: MediaMetadata? = null
        private val listeners = mutableListOf<Player.Listener>()

        fun setOverrideMetadata(metadata: MediaMetadata?) {
            if (overrideMetadata == metadata) return
            overrideMetadata = metadata
            
            val currentMetadata = getMediaMetadata()
            listeners.forEach { 
                it.onMediaMetadataChanged(currentMetadata)
                it.onPlaylistMetadataChanged(currentMetadata)
            }
        }

        override fun addListener(listener: Player.Listener) {
            super.addListener(listener)
            listeners.add(listener)
        }

        override fun removeListener(listener: Player.Listener) {
            super.removeListener(listener)
            listeners.remove(listener)
        }

        override fun getMediaMetadata(): MediaMetadata {
            val baseMetadata = super.getMediaMetadata()
            val override = overrideMetadata ?: return baseMetadata
            
            return baseMetadata.buildUpon()
                .setTitle(override.title ?: baseMetadata.title)
                .setArtist(override.artist ?: baseMetadata.artist)
                .build()
        }

        override fun getCurrentMediaItem(): MediaItem? {
            val item = super.getCurrentMediaItem() ?: return null
            val override = overrideMetadata ?: return item
            
            val newMetadata = item.mediaMetadata.buildUpon()
                .setTitle(override.title ?: item.mediaMetadata.title)
                .setArtist(override.artist ?: item.mediaMetadata.artist)
                .build()
                
            return item.buildUpon()
                .setMediaMetadata(newMetadata)
                .build()
        }
    }

    private fun parseHlsManifestMetadata(manifest: HlsManifest): MediaMetadata? {
        val segment = manifest.mediaPlaylist.segments.firstOrNull() ?: return null
        val titleAttr = segment.title ?: return null
        
        // Example titleAttr: title="Blaze Of Glory",artist="Jon Bon Jovi",url="..."
        if (titleAttr.contains("title=") && titleAttr.contains("artist=")) {
            val title = extractAttribute(titleAttr, "title")
            val artist = extractAttribute(titleAttr, "artist")
            if (title != null || artist != null) {
                return MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .build()
            }
        }
        return null
    }

    private fun extractAttribute(source: String, attr: String): String? {
        val pattern = "$attr=\"([^\"]*)\"".toRegex()
        return pattern.find(source)?.groupValues?.get(1)
    }
}
