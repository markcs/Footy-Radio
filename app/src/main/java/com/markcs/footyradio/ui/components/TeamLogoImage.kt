package com.markcs.footyradio.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade

private const val TEAM_LOGO_BASE_URL =
    "https://markcs.github.io/Footy-Radio/team-logos"

@Composable
fun TeamLogoImage(
    artworkUrl: String?,          // song artwork only
    stationArtworkUrl: String?,   // station image fallback
    hTeam: String?,
    aTeam: String?,
    showLogos: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val hUrl = remember(hTeam) {
        hTeam?.let { "$TEAM_LOGO_BASE_URL/${it.replace(" ", "%20")}.png" }
    }
    val aUrl = remember(aTeam) {
        aTeam?.let { "$TEAM_LOGO_BASE_URL/${it.replace(" ", "%20")}.png" }
    }

    when {
        // Priority 1: team logos when live scores active
        showLogos && hUrl != null && aUrl != null -> {
            Row(
                modifier = modifier,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(hUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = hTeam,
                    contentScale = contentScale,
                    modifier = Modifier.weight(1f).fillMaxSize()
                )
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(aUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = aTeam,
                    contentScale = contentScale,
                    modifier = Modifier.weight(1f).fillMaxSize()
                )
            }
        }
        // Priority 2: song artwork
        artworkUrl != null -> {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(artworkUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = modifier
            )
        }
        // Priority 3: station artwork
        else -> {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(stationArtworkUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = modifier
            )
        }
    }
}
