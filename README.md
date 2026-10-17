# Unfog

Unfog is an Android travel journal. Turn on travel mode, and it records your route in the background, gathers the photos you take along the way, and clears the fog from every place you've been.

## Features

- **Travel mode.** A foreground location service records the trip. You can start and stop it from the app, the notification or a Quick Settings tile. Points are written straight to SQLite, so the track survives if the UI is killed.
- **Trip photos.** Use any camera app. Unfog matches photos to a trip by the time they were taken and places each one using its EXIF GPS, or the track position at that moment when the photo has none. Originals stay in your gallery.
- **Map modes**
  - **Explored**: fog of war over the whole map, with holes wherever you've been, and the total area uncovered.
  - **Frequency**: hexagons coloured by how many distinct days you've visited each place.
  - **Routes**: every track, coloured by inferred transport mode (walk, bike, road, train, flight).
  - **Photos**: clustered photo pins; tap one to open its trip.
- **Trip replay.** An animated playback of a route, with photos popping up where they were taken.
- **Places.** Save home, work or anywhere else you return to. Unfog offers to start a trip when you leave one of them, and ends the trip when you arrive at one.

## Install

1. On your Android phone (Android 10 or newer), open the [latest release](https://github.com/MJTheGreat3/Unfog/releases/latest) and download the `.apk`.
2. Open the file. If Android asks, allow your browser or file manager to install unknown apps.
3. If Play Protect warns that the app is unrecognised, tap **More details → Install anyway**.

For automatic updates, add this repository's URL to [Obtainium](https://github.com/ImranR98/Obtainium).

## Stack

Kotlin, Jetpack Compose, MapLibre Native with OpenFreeMap tiles (no API key needed), and Google Play Services fused location.

## Building

Requires JDK 17+ and the Android SDK (compileSdk 37).

```sh
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Release builds are signed with a key configured outside the repo, in `~/.gradle/gradle.properties`:

```properties
UNFOG_STORE_FILE=/path/to/unfog-release.jks
UNFOG_STORE_PASSWORD=...
UNFOG_KEY_ALIAS=unfog
UNFOG_KEY_PASSWORD=...
```

Without these settings, `./gradlew assembleRelease` falls back to the debug key.

The minimum supported version is Android 10 (API 29).

## Permissions

| Permission | Why |
| --- | --- |
| Precise location | Record the route while travel mode is on |
| Notifications | Show the ongoing trip notification |
| Photos | Find the photos taken during a trip |
| Media location | Read GPS from photo EXIF data |
| Location "Allow all the time" (optional) | Notice you leaving a saved place while Unfog is closed |

Unfog has no account or server, and it sends no analytics. Map tiles are the only network traffic.
