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

## Stack

Kotlin, Jetpack Compose, MapLibre Native with OpenFreeMap tiles (no API key needed), and Google Play Services fused location.

## Building

Requires JDK 17+ and the Android SDK (compileSdk 37).

```sh
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

The minimum supported version is Android 10 (API 29).

## Permissions

| Permission | Why |
| --- | --- |
| Precise location | Record the route while travel mode is on |
| Notifications | Show the ongoing trip notification |
| Photos | Find the photos taken during a trip |
| Media location | Read GPS from photo EXIF data |

Unfog has no account or server, and it sends no analytics. Map tiles are the only network traffic.
