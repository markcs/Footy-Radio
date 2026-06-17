package com.markcs.footyradio

import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaQueueItem
import org.json.JSONObject

/**
 * Custom [MediaItemConverter] for CastPlayer.
 *
 * Solves two problems with the default [androidx.media3.cast.DefaultMediaItemConverter]:
 *
 * 1. **STREAM_TYPE_LIVE for HLS** — The default converter always sends STREAM_TYPE_NONE,
 *    which causes the Default Media Receiver (MPL) to treat live HLS streams as VOD. MPL
 *    then attempts to seek to position 0 on a rolling window and buffers indefinitely.
 *    This converter sets STREAM_TYPE_LIVE for any HLS (application/x-mpegURL) item.
 *
 * 2. **NullPointerException in toMediaItem** — The default converter calls
 *    `Assertions.checkNotNull(mediaInfo.getCustomData())` and crashes when the queue item
 *    was loaded by our own RemoteMediaClient.load() call without the Media3 customData blob.
 *    This converter reconstructs the MediaItem directly from the MediaInfo fields instead,
 *    with no dependency on customData.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class RadioMediaItemConverter : MediaItemConverter {

    companion object {
        // Keys used by DefaultMediaItemConverter — we write the same structure so
        // that if CastPlayer ever reads back a queue item we populated, it can parse it.
        private const val KEY_MEDIA_ITEM = "mediaItem"
        private const val KEY_MEDIA_ID = "mediaId"
        private const val KEY_URI = "uri"
        private const val KEY_MIME_TYPE = "mimeType"
        private const val KEY_TITLE = "title"
    }

    // -----------------------------------------------------------------------------------------
    // MediaItem -> MediaQueueItem  (sent to the Cast receiver)
    // -----------------------------------------------------------------------------------------

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val uri = mediaItem.localConfiguration?.uri?.toString() ?: ""
        val mimeType = mediaItem.localConfiguration?.mimeType ?: ""
        val title = mediaItem.mediaMetadata.title?.toString() ?: ""

        val isHls = mimeType == MimeTypes.APPLICATION_M3U8
        val streamType = if (isHls) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED

        val castMetadata = com.google.android.gms.cast.MediaMetadata(
            com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MUSIC_TRACK
        ).apply {
            if (title.isNotEmpty()) {
                putString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE, title)
            }
        }

        // Embed the Media3 fields as customData so toMediaItem() can reconstruct the
        // MediaItem when CastPlayer processes the receiver's queue status update.
        val customData = JSONObject().apply {
            put(KEY_MEDIA_ITEM, JSONObject().apply {
                put(KEY_MEDIA_ID, mediaItem.mediaId)
                put(KEY_URI, uri)
                put(KEY_MIME_TYPE, mimeType)
                put(KEY_TITLE, title)
            })
        }

        val mediaInfo = MediaInfo.Builder(uri)
            .setStreamType(streamType)
            .setContentType(mimeType)
            .setMetadata(castMetadata)
            .setCustomData(customData)
            .build()

        return MediaQueueItem.Builder(mediaInfo).build()
    }

    // -----------------------------------------------------------------------------------------
    // MediaQueueItem -> MediaItem  (received from the Cast receiver's queue status)
    // -----------------------------------------------------------------------------------------

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        val mediaInfo = mediaQueueItem.media
            ?: return MediaItem.EMPTY

        // Try to reconstruct from our own customData first (the happy path).
        val customData = mediaInfo.customData
        if (customData != null && customData.has(KEY_MEDIA_ITEM)) {
            try {
                val itemJson = customData.getJSONObject(KEY_MEDIA_ITEM)
                val mediaId = itemJson.optString(KEY_MEDIA_ID, "")
                val uri = itemJson.optString(KEY_URI, mediaInfo.contentId ?: "")
                val mimeType = itemJson.optString(KEY_MIME_TYPE, mediaInfo.contentType ?: "")
                val title = itemJson.optString(KEY_TITLE, "")

                return MediaItem.Builder()
                    .setMediaId(mediaId)
                    .setUri(uri)
                    .setMimeType(mimeType)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(title.ifEmpty { null })
                            .build()
                    )
                    .build()
            } catch (_: Exception) {
                // Fall through to best-effort reconstruction below.
            }
        }

        // Fallback: reconstruct from MediaInfo directly (no customData — e.g. loaded by
        // another Cast sender, or our RemoteMediaClient.load() path). This prevents the
        // NullPointerException that DefaultMediaItemConverter throws in this case.
        val uri = mediaInfo.contentUrl ?: mediaInfo.contentId ?: ""
        val mimeType = mediaInfo.contentType ?: ""
        val title = mediaInfo.metadata
            ?.getString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE) ?: ""

        return MediaItem.Builder()
            .setUri(uri)
            .setMimeType(mimeType)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title.ifEmpty { null })
                    .build()
            )
            .build()
    }
}