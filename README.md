# OTR Dial v0.8

A small native Android radio app for continuous Old Time Radio and classic-radio streams.

## Included

- 49 stations across Vintage ROKiT, Pumpkin FM, Conyers, Yesterday USA, WOTR, America's OTR and independently verified catalogue feeds.
- Native Media3/ExoPlayer playback with background playback, lock-screen controls and stream metadata when supplied.
- Search, genre filtering, Favourites and Recently played collections.
- Colour-coded station tiles, clearer home-screen branding and a full-screen player with a clear play/pause icon, dark mode, connection status and bounded automatic reconnect.
- Station detail cards with network, genre, verification notes and links to the broadcaster's website or schedule.
- Current station information accessible directly from both the station list and full-screen player.
- Share a station's name and stream link through Android's share menu.
- A clearer Now Playing panel identifies whether programme information came from the live stream or the station schedule, without inventing missing episode details.
- Home-style quick navigation for All stations, Favourites and Recently played, with a daily OTR discovery heading.
- Optional recording for direct MP3/Icecast-style streams, saved under `Music/OTR Dial`.

The additional catalogue stations were selected from the supplied APK's extracted stream list and retained only when an audit received a non-empty audio response. A response check is not a guarantee that a broadcaster will remain online or that every Android network will reach it.

## Limitations

Station URLs can change. Some broadcasters publish HTTP feeds, so cleartext traffic remains enabled for compatibility. Artwork and programme-level episode descriptions are not yet bundled. Recording depends on the stream format and the broadcaster's terms.

## Build

The GitHub Actions workflow builds `assembleDebug` with Gradle 8.9 and publishes the APK as the `OTR-Dial-debug-apk` workflow artifact. Android Studio with JDK 17 can also build the project locally.
