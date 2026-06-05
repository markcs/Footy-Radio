package com.markcs.footyradio

import androidx.compose.ui.graphics.Color

object Config {
    val gradientColor: Color = Color.White

    const val useLocalStations = false
    const val stationsURL = "https://markcs.github.io/Footy-Radio/stations/stations.json"

    const val hideNextPreviousButtons = false
    const val enableSearch = true // Toggle to show/hide search bar on the Stations Screen

    const val email = "contact@campbellsmith.me"
    const val feedbackURL = "https://fethica.com/#contact"
    const val licenseURL = "https://raw.githubusercontent.com/fethica/Swift-Radio-Android/refs/heads/main/LICENSE"
    const val shareText = "Check out Footy Radio!"

    data class LibraryItem(val owner: String, val repo: String)

    val libraries = listOf(
        LibraryItem("fethica", "Swift-Radio-Android"),
        LibraryItem("coil-kt", "coil"),
        LibraryItem("ktorio", "ktor"),
    )
}
