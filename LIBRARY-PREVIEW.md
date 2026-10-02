# OTR Dial 2.0 preview

This preview installs beside OTR Dial 1.3 (`com.example.otrdial.librarypreview`). It does not replace the original app or transfer its recordings and favourites. Keep the original app installed.

## Included

- All 95 existing live radio stations, with their light/dark interface.
- A listening library: saved episode bookmarks, followed sources, queue ordering, progress/resume, full-screen player, seeking and skip controls.
- Four official Relic Radio RSS feeds: The Relic Radio Show, Relic Radio Science Fiction, Case Closed and A Legacy of Laughs.
- Three Internet Archive/OTRR collections: Gunsmoke, X Minus One and Our Miss Brooks.
- A bundled browse-only catalogue of 962 entries: 40 recent entries per podcast and 802 archive audio files. Counts include introductions and different recordings where present.
- Manual refresh, preserving saved/queued/partly played older entries and retaining the catalogue if a source fails. Followed podcast refresh is available in Discover.
- JSON library export/import through Android's file picker. Saved entries/follows/queue merge; imported progress replaces progress for matching episodes. The backup excludes audio, recordings and live-radio favourites.

## Use

From the radio home screen, tap **Your listening library**. Browse a source, **Follow** it, then play or save an episode. **More** contains queue, restart, description and source-page actions. **My library** contains resume entries, followed sources and saved episodes. **Queue** offers playback, remove and move-up controls. The overflow menu offers theme and backup controls.

Audio comes from the original provider; saved episodes are bookmarks, not offline downloads. RSS episode descriptions are supplied by publishers. Archive entries may have limited metadata. Artwork is illustrative, from the existing bundled credited collection. No scraped premium feeds, RadioEchoes, OTRCAT or YouTube integration is included.

## Build and validation

GitHub Actions builds the APK with JDK 17 and Gradle 8.9 and runs Android 15 instrumentation tests for light/dark navigation, parsing, backup validation, seek/pause progress, and automatic queue advancement using local test audio. Download `OTR-Dial-debug-apk` from the successful Actions run and extract `app-debug.apk`. This is a testing build, not a Play Store release. The workflow caches the preview debug key to help successive preview installs update; cache deletion can require a new installation, so export a backup before updating.

`scripts/seed-episodes.py` refreshes bundled metadata from the curated public sources. At runtime, the app reads RSS audio enclosures and the Internet Archive metadata API; it does not copy hosted audio into the app.
