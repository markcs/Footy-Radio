package com.markcs.footyradio.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.markcs.footyradio.R
import com.markcs.footyradio.ui.theme.SubtitleGray

@Composable
fun MiniPlayer(
    stationName: String,
    trackTitle: String,
    artistName: String,
    artworkUrl: String?,
    stationArtworkUrl: String?, 
    hTeam: String?,
    aTeam: String?,
    liveScore: String? = null,
    isPlaying: Boolean,
    isLive: Boolean = true,
    isCasting: Boolean = false,
    castDeviceName: String? = null,
    onPlayPauseClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val normalizedTitle = trackTitle.trim()
    val normalizedArtist = artistName.trim()
    val hasTrackMetadata = normalizedTitle.isNotBlank() && !normalizedTitle.equals(stationName, ignoreCase = true)

    val songMetadata = if (normalizedArtist.isNotBlank()) {
        "$normalizedTitle — $normalizedArtist"
    } else {
        normalizedTitle
    }

    val displayTitle = if (liveScore != null) {
        liveScore
    } else if (hasTrackMetadata) {
        songMetadata
    } else {
        stationName
    }

    val displaySubtitle = when {
        isCasting -> if (!castDeviceName.isNullOrBlank()) "Casting to $castDeviceName" else "Casting to TV"
        liveScore != null && hasTrackMetadata -> songMetadata
        else -> stationName
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnimatedContent(
            targetState = artworkUrl ?: if (liveScore != null) "logos:$hTeam|$aTeam" else "station",
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "miniArtwork"
        ) { _ ->
            TeamLogoImage(
                artworkUrl = artworkUrl,
                stationArtworkUrl = stationArtworkUrl,
                hTeam = hTeam,
                aTeam = aTeam,
                showLogos = liveScore != null,
                contentDescription = stationName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayTitle,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                modifier = Modifier.basicMarquee()
            )
            Text(
                text = displaySubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = SubtitleGray,
                maxLines = 1,
                modifier = Modifier.basicMarquee()
            )
        }

        IconButton(onClick = onPlayPauseClick) {
            if (isPlaying && isLive) {
                Icon(
                    imageVector = Icons.Filled.Stop,
                    contentDescription = stringResource(R.string.cd_stop),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            } else {
                Icon(
                    painter = painterResource(
                        if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                    ),
                    contentDescription = stringResource(if (isPlaying) R.string.cd_pause else R.string.cd_play),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}