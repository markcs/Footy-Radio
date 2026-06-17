package com.markcs.footyradio.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.SerialName

@Serializable
data class RadioStation(
    val id: String = "",
    val name: String,
    val streamURLs: List<String> = emptyList(),
    @SerialName("streamURL")
    val legacyStreamURL: String = "",
    val imageURL: String,
    val desc: String,
    val longDesc: String = "",
    val website: String = ""
) {
    @Transient
    var resolvedImageUrl: String = ""

    val streamURL: String
        get() = streamURLs.firstOrNull() ?: legacyStreamURL
}

@Serializable
data class StationsResponse(
    val station: List<RadioStation>
)
