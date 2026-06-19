package com.markcs.footyradio

import androidx.compose.ui.graphics.Color

object Config {
    val gradientColor: Color = Color.White

    const val stationsURL = "https://markcs.github.io/Footy-Radio/stations/stations.json"

    const val hideNextPreviousButtons = false
    const val enableSearch = true // Toggle to show/hide search bar on the Stations Screen

    const val email = "contact@campbellsmith.me"
    const val feedbackURL = "https://github.com/markcs/Footy-Radio/issues"
    const val licenseURL = "https://github.com/markcs/Footy-Radio/blob/main/LICENSE"
    const val shareText = "Check out Footy Radio!"

    data class LibraryItem(val owner: String, val repo: String)

    val libraries = listOf(
        LibraryItem("fethica", "Swift-Radio-Android"),
        LibraryItem("coil-kt", "coil"),
        LibraryItem("ktorio", "ktor"),
    )
}
