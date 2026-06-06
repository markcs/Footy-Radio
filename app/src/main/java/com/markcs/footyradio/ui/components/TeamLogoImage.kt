package com.markcs.footyradio.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade

private const val TEAM_LOGO_BASE_URL =
    "https://raw.githubusercontent.com/markcs/Footy-Radio/main/team-logos"

@Composable
fun TeamLogoImage(
    artworkUrl: String?,
    hTeam: String?,
    aTeam: String?,
    liveScore: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    if (liveScore != null && !hTeam.isNullOrBlank() && !aTeam.isNullOrBlank()) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data("$TEAM_LOGO_BASE_URL/$hTeam.png")
                    .crossfade(true)
                    .build(),
                contentDescription = hTeam,
                contentScale = contentScale,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
            )
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data("$TEAM_LOGO_BASE_URL/$aTeam.png")
                    .crossfade(true)
                    .build(),
                contentDescription = aTeam,
                contentScale = contentScale,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
            )
        }
    } else {
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
}
