# OTR Dial v0.1

A small Android radio app focused only on continuous Old Time Radio / classic radio streams.

## What is in this prototype

- 43 initial stations in `app/src/main/assets/stations.json`
- Native Media3 / ExoPlayer streaming (not a WebView)
- Background playback through `MediaSessionService`
- Lock-screen / notification playback controls via Android's media session
- Search and genre filtering
- Favourites stored locally
- Sleep timer (15/30/60/90 minutes)
- Stream recording for direct MP3-style streams; recordings are saved under `Music/OTR Dial`
- Media metadata listener for ICY / stream metadata when the broadcaster supplies it
- Station source / verification notes in `station_catalogue.csv`

## Networks represented in v0.1

- Vintage ROKiT Radio
- Pumpkin FM
- Conyers Old Time Radio
- Yesterday USA
- WOTR Radio Network
- America's OTR / Live365

## Important v0.1 limitations

1. Station-specific artwork is not yet bundled. The list uses clean initials tiles. The data model is intentionally easy to extend with artwork URLs later.
2. Episode/show artwork lookup is not yet implemented. This should be a second enrichment layer after stream reliability is proven.
3. Recording is deliberately simple: it opens a second connection to the stream and saves the raw audio response. It is best suited to MP3/Icecast/Shoutcast-style streams and should be disabled for any provider whose terms do not permit recording.
4. A few ROKiT/Pumpkin endpoints are HTTP because that is what the broadcaster publishes. `usesCleartextTraffic=true` is therefore enabled. Prefer HTTPS endpoints whenever broadcasters publish them.
5. Stream URLs can change. The CSV keeps provenance notes so dead feeds can be replaced rather than guessed.
6. The WOTR catalogue currently includes the three stable core feeds whose direct endpoints were readily identifiable. Nightmare Radio USA should be added once its current direct endpoint is confirmed.
7. The app source is prepared for Android Studio/Gradle but this environment does not contain an Android SDK, so an APK was not compiled here.

## Suggested next pass

- Test each of the 43 feeds on a real Android device and flag `working / metadata / recordable`.
- Add the 1640 Radio family, OTRNow's four streams, Relic Radio, Antioch, Hank's OTR, WRCW and other independents after direct-stream confirmation.
- Add station artwork.
- Add programme-level artwork and descriptions using parsed now-playing titles plus a small local programme catalogue.
- Add a station detail sheet with homepage and schedule buttons.
- Move recording to its own foreground service so it survives the UI being dismissed.

## Build

Open the root folder in Android Studio, allow Gradle dependencies to sync, then run on Android 10+ (API 29+). A recent Android Studio with JDK 17 is recommended.
