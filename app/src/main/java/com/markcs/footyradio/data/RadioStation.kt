package com.markcs.footyradio.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class RadioStation(
    val id: String = "",
    val name: String,
    val streamURL: String,
    val imageURL: String,
    val desc: String,
    val longDesc: String = "",
    val website: String = ""
) {
    @Transient
    var resolvedImageUrl: String = ""
}

@Serializable
data class StationsResponse(
    val station: List<RadioStation>
)
