# Footy Radio

An open-source Android radio app tailored for Australian football fans. Stream radio stations and catch live AFL scores — all in one place - with Android Auto support.

Forked from [Swift Radio Android](https://github.com/fethica/Swift-Radio-Android) by Fethi, built with Kotlin, Jetpack Compose, and Media3.

<p align="center">
    <img alt="Station list" src="screenshots/stations.png" width="300">
    <img alt="Now playing" src="screenshots/player.png" width="300">
</p>

## Features

- Stream  radio stations with background playback
- Preloaded with a few Melbourne FM stations
- Live AFL scores on the Now Playing screen, powered by the [Squiggle API](https://squiggle.com.au/) via real-time SSE updates
- Team logos for all 18 AFL clubs displayed alongside live scores
- Multiple stream URL fallbacks per station for improved reliability
- Album art and track metadata from streams and the iTunes API
- Android Auto integration with browse tree, playback controls, and artwork
- Lock screen and notification controls with artwork
- Search and filter stations by name or description
- Material You dynamic color support on Android 12+
- Localization-ready with all strings extracted to resources

## Requirements

- Android 7.0+ (API 24)
- Android Studio

## Getting Started

1. Open the project in [Android Studio](https://developer.android.com/studio)
2. Edit `Config.kt` to set your stations URL, contact info, and feature flags
3. Replace `stations/stations.json` with your own stations (hosted or bundled)
4. Build and run

### Station Format

Stations are loaded from a remote JSON file hosted at  `https://markcs.github.io/Footy-Radio/stations/stations.json` and user configurable within the app at `Config.stationsURL`. Each station supports the following fields:

```json
{
  "station": [
    {
      "name": "Station Name",
      "streamURLs": [
        "https://example.com/stream.aac",
        "https://example.com/stream.mp3"
      ],
      "imageURL": "https://example.com/station-image.png",
      "desc": "Short description",
      "longDesc": "Detailed description shown in the station info sheet.",
      "website": "https://example.com"
    }
  ]
}
```

| Field        | Required | Description                                                            |
|--------------|----------|------------------------------------------------------------------------|
| `name`       | Yes      | Station name displayed in the list and player                          |
| `streamURLs` | Yes      | Ordered list of stream URLs — the app tries each in turn               |
| `imageURL`   | Yes      | Asset filename (without extension) or a full URL                       |
| `desc`       | Yes      | Short subtitle shown below the station name                            |
| `longDesc`   | No       | Longer description shown in the station info sheet                     |
| `website`    | No       | Station website URL shown in the info sheet                            |

> **Note:** `streamURLs` replaces the original single `streamURL` field. The app falls back through the list automatically if a stream fails to connect. The legacy `streamURL` field is still accepted for compatibility.

### Configuration

All app-wide settings live in `Config.kt`:

```kotlin
object Config {
    val gradientColor: Color = Color.White       // Diagonal gradient overlay color
    const val stationsURL = "https://..."        // Remote stations JSON URL
    const val hideNextPreviousButtons = false    // Hide skip controls on the player
    const val enableSearch = true               // Show/hide search bar on station list
    const val email = "contact@example.com"
    const val feedbackURL = "https://..."
    const val licenseURL = "https://..."
    const val shareText = "Check out Footy Radio!"
}
```

### Live AFL Scores

Footy Radio integrates with the [Squiggle API](https://squiggle.com.au/) to display live AFL scores on the Now Playing screen. Scores update in real time via a Server-Sent Events (SSE) connection that opens automatically around game time and closes when all games for the day are complete.

Team logos for all 18 AFL clubs are bundled in the `team-logos/` directory and are displayed alongside the score ticker.

### Station Icons

Station artwork is hosted on GitHub Pages at `https://markcs.github.io/Footy-Radio/stations/icons/` and referenced directly from the stations JSON. Icons for the included Melbourne stations are provided in the `stations/icons/` directory.

### Customizing Text and Translation

All user-facing strings are in `app/src/main/res/values/strings.xml`. To add a new language:

1. Create a new directory: `app/src/main/res/values-XX/` (e.g., `values-fr` for French)
2. Copy `strings.xml` into the new directory
3. Translate the string values

Android will automatically use the correct language based on the user's device settings.

## Dependencies

| Library | Purpose |
|---------|---------|
| [AndroidX Media3](https://developer.android.com/media/media3) | Audio playback, media session, Android Auto |
| [Jetpack Compose](https://developer.android.com/compose) | UI framework with Material 3 |
| [Coil](https://github.com/coil-kt/coil) | Image loading and caching |
| [Ktor](https://github.com/ktorio/ktor) | HTTP client for remote stations and SSE |
| [Kotlinx Serialization](https://github.com/Kotlin/kotlinx.serialization) | JSON parsing |

## Credits

- Forked from [Swift Radio Android](https://github.com/fethica/Swift-Radio-Android) by [Fethi](https://fethica.com)
- Live AFL scores via [Squiggle](https://squiggle.com.au/)

## License

Footy Radio is open source and available under the [MIT License](LICENSE).
