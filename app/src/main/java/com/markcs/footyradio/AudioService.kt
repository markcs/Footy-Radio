package com.markcs.footyradio

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
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.markcs.footyradio.data.RadioStation
import com.markcs.footyradio.data.StationsRepository
import com.markcs.footyradio.data.SquiggleService
import com.markcs.footyradio.data.LiveScoreState
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
import java.util.Locale

@OptIn(UnstableApi::class)
class AudioService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private var player: Player? = null
    private var basePlayer: ExoPlayer? = null
    
    private var retryJob: Job? = null
    private var bufferingRecoveryJob: Job? = null
    private var stallRecoveryJob: Job? = null
    
    private var currentLiveScore: LiveScoreState? = null
    private var currentIcyTitle: String? = null
    private var currentManifestMetadata: MediaMetadata? = null
    
    private var squiggleJob: Job? = null
    private var artworkFetchJob: Job? = null
    private var lastArtworkLookupTerm: String? = null
    private var currentSongArtworkUrl: String? = null
    private var lastAppliedArtworkKey: String? = null
    
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
    private val teamLogoCache = mutableMapOf<String, ByteArray?>() 

    companion object {
        private const val ROOT_ID = "/"
        private const val TAG = "AudioService"
    }

    private val playerListener = object : Player.Listener {
        override fun onMetadata(metadata: Metadata) {
            serviceScope.launch(Dispatchers.Default) {
                var updated = false
                
                // 1. Check for ICY
                val icyTitle = extractIcyTitle(metadata)
                if (icyTitle != null) {
                    currentIcyTitle = icyTitle
                    updated = true
                }
                
                // 2. Check for ID3 or HLS Timed Metadata
                val timedMeta = parseTimedMetadata(metadata)
                if (timedMeta != null) {
                    // We store this as currentManifestMetadata for the unified priority logic
                    // since it's "more fresh" than the actual manifest
                    currentManifestMetadata = timedMeta
                    updated = true
                }
                
                if (updated) {
                    updateDisplayMetadata()
                }
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
            Log.d(TAG, "Svc media item transition reason=$reason uri='${mediaItem?.localConfiguration?.uri}'")
            cancelRetry()
            retryCount = 0
            currentIcyTitle = null
            currentManifestMetadata = null
            lastArtworkLookupTerm = null
            currentSongArtworkUrl = null
            lastAppliedArtworkKey = null
            artworkFetchJob?.cancel()
            
            val wrappedPlayer = player as? MetadataForwardingPlayer
            wrappedPlayer?.setOverrideMetadata(null)
            
            player?.setPlaylistMetadata(MediaMetadata.Builder().build())
            updateDisplayMetadata()
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
                val uri = mediaItem.localConfiguration?.uri ?: Uri.EMPTY
                val type = androidx.media3.common.util.Util.inferContentType(uri)
                return if (type == C.CONTENT_TYPE_HLS || mediaItem.localConfiguration?.mimeType == androidx.media3.common.MimeTypes.APPLICATION_M3U8) {
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
        serviceScope.launch {
            val icyTitle = currentIcyTitle
            val liveScore = currentLiveScore
            val liveScoreText = currentLiveScore?.scoreText
            val manifestMeta = currentManifestMetadata
            val currentItem = basePlayer?.currentMediaItem ?: activePlayer.currentMediaItem ?: return@launch
            val stationName = getCurrentStationName()
            
            Log.d(TAG, "Updating Metadata: ICY='$icyTitle', ManifestTitle='${manifestMeta?.title}'")

            // Priority: 1. ICY Metadata, 2. Manifest Metadata
            var displayTitle: String? = null
            var displayArtist: String? = null
            var isJunk = false

            if (!icyTitle.isNullOrBlank()) {
                if (isJunkMetadata(icyTitle)) {
                    Log.d(TAG, "Detected ICY Junk: $icyTitle")
                    isJunk = true
                } else {
                    val parsed = buildTrackMetadataFromIcy(icyTitle)
                    displayTitle = parsed.title?.toString()
                    displayArtist = parsed.artist?.toString()
                }
            }

            if (!isJunk && displayTitle.isNullOrBlank() && manifestMeta != null) {
                // Check if the manifest metadata itself indicates an ad
                val cueType = manifestMeta.extras?.getString("cue_type")?.lowercase()
                
                if (isJunkMetadata(manifestMeta.title) || cueType == "ad") {
                    Log.d(TAG, "Detected Manifest Junk: ${manifestMeta.title} (cue=$cueType)")
                    isJunk = true
                } else {
                    displayTitle = manifestMeta.title?.toString()
                    displayArtist = manifestMeta.artist?.toString()
                }
            }

            // Fallback to base stream metadata if we have nothing yet and haven't hit junk
            if (!isJunk && displayTitle.isNullOrBlank()) {
                val baseMeta = basePlayer?.currentMediaItem?.mediaMetadata
                if (baseMeta != null) {
                    if (isJunkMetadata(baseMeta.title)) {
                        Log.d(TAG, "Detected Base Junk: ${baseMeta.title}")
                        isJunk = true
                    } else {
                        displayTitle = baseMeta.title?.toString()
                        displayArtist = baseMeta.artist?.toString()
                    }
                }
            }

            // Final fallback: station name if junk or no metadata
            if (isJunk || displayTitle.isNullOrBlank()) {
                displayTitle = stationName
                displayArtist = ""
            }

            Log.d(TAG, "Final Metadata: Title='$displayTitle', Artist='$displayArtist', isJunk=$isJunk")

            // Fetch artwork if it's a song and not just station name
            val isSong = !displayTitle.isNullOrBlank() && displayTitle != stationName
            if (isSong) {
                val lookupTerm = if (displayArtist.isNullOrBlank()) displayTitle else "$displayArtist $displayTitle"
                if (lookupTerm != lastArtworkLookupTerm) {
                    lastArtworkLookupTerm = lookupTerm
                    artworkFetchJob?.cancel()
                    artworkFetchJob = serviceScope.launch {
                        val app = application as SwiftRadioApplication
                        val url = app.artworkService.fetchArtworkUrl(lookupTerm)
                        if (url != currentSongArtworkUrl) {
                            currentSongArtworkUrl = url
                            updateDisplayMetadata()
                        }
                    }
                }
            } else {
                if (lastArtworkLookupTerm != null || currentSongArtworkUrl != null) {
                    lastArtworkLookupTerm = null
                    currentSongArtworkUrl = null
                    artworkFetchJob?.cancel()
                    // Re-run to apply fallbacks immediately
                    updateDisplayMetadata()
                    return@launch
                }
            }

            // Overlay live score: Title = Score, Artist = Song Info or Station Name
            if (liveScore != null) {
                val songInfo = if (!displayTitle.isNullOrBlank() && displayTitle != stationName) {
                    if (displayArtist.isNullOrBlank()) displayTitle else "$displayArtist - $displayTitle"
                } else {
                    stationName
                }
                displayTitle = liveScoreText
                displayArtist = songInfo
            }

            
            // Update global playlist metadata for external controllers and Android Auto
            // Determine what artwork URI/key should be shown, without fetching yet
            val songArtworkUrl = if (isSong) currentSongArtworkUrl else null
            val artworkKey: String? = when {
                !songArtworkUrl.isNullOrBlank() -> songArtworkUrl
                currentLiveScore != null -> {
                    val h = currentLiveScore!!.hTeam
                    val a = currentLiveScore!!.aTeam
                    if (h.isNotBlank() && a.isNotBlank()) "teamlogo:$h|$a" else null
                }
                else -> currentItem.mediaMetadata.artworkUri?.toString()
            }

            val existingMeta = activePlayer.playlistMetadata
            val textChanged = existingMeta.title?.toString().orEmpty() != displayTitle.orEmpty() ||
                existingMeta.artist?.toString().orEmpty() != displayArtist.orEmpty()
            val artworkChanged = artworkKey != lastAppliedArtworkKey

            // Skip entirely if nothing changed
            if (!textChanged && !artworkChanged) return@launch

            val metaBuilder = MediaMetadata.Builder()
                .setTitle(displayTitle)
                .setArtist(displayArtist)

            if (artworkChanged) {
                // Artwork has changed — resolve and apply it
                var artworkApplied = false

                // Priority 1: song artwork
                if (!songArtworkUrl.isNullOrBlank()) {
                    artworkApplied = applyArtworkToMetadata(songArtworkUrl, metaBuilder)
                }

                // Priority 2: team logos when live score active
                if (!artworkApplied && currentLiveScore != null) {
                    val hTeam = currentLiveScore!!.hTeam
                    val aTeam = currentLiveScore!!.aTeam
                    if (hTeam.isNotBlank() && aTeam.isNotBlank()) {
                        artworkApplied = applyTeamLogoArtwork(hTeam, aTeam, metaBuilder)
                    }
                }

                // Priority 3: station artwork
                if (!artworkApplied) {
                    val stationArtworkUrl = currentItem.mediaMetadata.artworkUri?.toString()
                    if (!stationArtworkUrl.isNullOrBlank()) {
                        artworkApplied = applyArtworkToMetadata(stationArtworkUrl, metaBuilder)
                    }
                }

                lastAppliedArtworkKey = artworkKey
            } else {
                // Artwork unchanged — reuse existing artwork data to avoid Android Auto flash
                existingMeta.artworkData?.let {
                    metaBuilder.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                }
                existingMeta.artworkUri?.let {
                    metaBuilder.setArtworkUri(it)
                }
            }

            val playlistMeta = metaBuilder.build()
            activePlayer.setPlaylistMetadata(playlistMeta)

            // Use the ForwardingPlayer to update metadata without playlist churn
            val wrappedPlayer = player as? MetadataForwardingPlayer
            if (wrappedPlayer != null) {
                if (displayTitle != null || displayArtist != null) {
                    wrappedPlayer.setOverrideMetadata(playlistMeta)
                } else {
                    wrappedPlayer.setOverrideMetadata(null)
                }
            }
        }
    }

    private fun isJunkMetadata(text: CharSequence?): Boolean {
        if (text == null) return false
        val s = text.toString().lowercase()
        return s.contains("asset spot") || s.contains("asset link") || s.contains("asset stop") || 
               s.contains("asset start") || s.contains("linearad") || s.equals("ad")
    }

    private fun getCurrentStationName(): String? {
        val mediaId = basePlayer?.currentMediaItem?.mediaId ?: return null
        if (mediaId.startsWith("station_")) {
            val index = mediaId.substringAfter("station_").toIntOrNull()
            if (index != null && index in stations.indices) {
                return stations[index].name
            }
        }
        return null
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
                stations = repository.loadStations()
                
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
    private suspend fun applyArtworkToMetadata(imageUrl: String, builder: MediaMetadata.Builder): Boolean {
        if (imageUrl.isBlank()) return false

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
            // Always set URI as well for controllers that prefer it or as fallback
            builder.setArtworkUri(Uri.parse(imageUrl))
            return true
        }
        return false
    }

    private suspend fun applyTeamLogoArtwork(
        hTeam: String,
        aTeam: String,
        builder: MediaMetadata.Builder
    ): Boolean {
        val cacheKey = "$hTeam|$aTeam"
        val cached = teamLogoCache[cacheKey]
        if (cached != null) {
            builder.setArtworkData(cached, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            builder.setArtworkUri(Uri.parse("footyradio://teamlogo?h=${hTeam.replace(" ", "%20")}&a=${aTeam.replace(" ", "%20")}"))
            return true
        }

        val baseUrl = "https://markcs.github.io/Footy-Radio/team-logos"
        val hUrl = "$baseUrl/${hTeam.replace(" ", "%20")}.png"
        val aUrl = "$baseUrl/${aTeam.replace(" ", "%20")}.png"

        val bytes = withContext(Dispatchers.IO) {
            try {
                val app = application as SwiftRadioApplication
                val client = app.sharedOkHttpClient

                fun fetchBitmap(url: String): Bitmap? {
                    val request = okhttp3.Request.Builder().url(url).build()
                    return client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            BitmapFactory.decodeStream(response.body.byteStream())
                        } else null
                    }
                }

                val hBitmap = fetchBitmap(hUrl)
                val aBitmap = fetchBitmap(aUrl)

                if (hBitmap != null && aBitmap != null) {
                    val size = 400 // each logo square size in pixels
                    val composite = Bitmap.createBitmap(size * 2, size, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(composite)
                    canvas.drawBitmap(
                        Bitmap.createScaledBitmap(hBitmap, size, size, true), 0f, 0f, null
                    )
                    canvas.drawBitmap(
                        Bitmap.createScaledBitmap(aBitmap, size, size, true), size.toFloat(), 0f, null
                    )
                    val outputStream = ByteArrayOutputStream()
                    composite.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                    outputStream.toByteArray()
                } else null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to build team logo composite: $hTeam vs $aTeam", e)
                null
            }
        }

        if (bytes != null) {
            teamLogoCache[cacheKey] = bytes
            builder.setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            builder.setArtworkUri(Uri.parse("footyradio://teamlogo?h=${hTeam.replace(" ", "%20")}&a=${aTeam.replace(" ", "%20")}"))
            return true
        }
        return false
    }

    private fun parseTimedMetadata(metadata: Metadata): MediaMetadata? {
        var title: String? = null
        var artist: String? = null
        val extras = android.os.Bundle()

        for (i in 0 until metadata.length()) {
            when (val entry = metadata[i]) {
                is TextInformationFrame -> {
                    when (entry.id) {
                        "TIT2" -> title = entry.value
                        "TPE1" -> artist = entry.value
                        "TXXX" -> {
                            // Triton and others use TXXX for custom fields
                            when (entry.description?.lowercase()) {
                                "cue_type" -> extras.putString("cue_type", entry.value)
                                "title" -> title = if (title == null) entry.value else title
                                "artist" -> artist = if (artist == null) entry.value else artist
                            }
                        }
                    }
                }
            }
        }

        if (title == null && artist == null && extras.isEmpty) return null

        return MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setExtras(extras)
            .build()
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
            val currentItem = basePlayer?.currentMediaItem ?: activePlayer.currentMediaItem ?: return@launch

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
            
            val builder = baseMetadata.buildUpon()
            
            // Apply overrides if they are set in our custom metadata
            override.title?.let { builder.setTitle(it) }
            override.artist?.let { builder.setArtist(it) }
            
            // Special handling for artwork to ensure we don't leak base artwork 
            // when we have an override (crucial for Android Auto)
            if (override.artworkData != null) {
                builder.setArtworkData(override.artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                builder.setArtworkUri(override.artworkUri) // Use the override URI (might be null)
            } else if (override.artworkUri != null) {
                builder.setArtworkUri(override.artworkUri)
                builder.setArtworkData(null, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            }
            
            return builder.build()
        }

        override fun getCurrentMediaItem(): MediaItem? {
            val item = super.getCurrentMediaItem() ?: return null
            val override = overrideMetadata ?: return item
            
            val newMetadata = getMediaMetadata()
                
            return item.buildUpon()
                .setMediaMetadata(newMetadata)
                .build()
        }
    }

    private fun parseHlsManifestMetadata(manifest: HlsManifest): MediaMetadata? {
        val segment = manifest.mediaPlaylist.segments.firstOrNull() ?: return null
        val titleAttr = segment.title
        
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
