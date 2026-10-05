# OTR Dial 2.1 preview

## Install

2.1 has its own package (`com.example.otrdial.library21preview`), so it can coexist with the earlier preview and original radio app. The previous preview build did not retain its debug signing key in the failed workflow. Do not uninstall it to install this version.

To transfer your episode library, use the earlier preview's menu → Export library backup, then this version's menu → Import library backup. Earlier backups exclude radio favourites. New 2.1 backups include radio favourites, recent stations, theme, saved episodes, followed shows, listening progress and queue. Audio files/recordings are not part of either backup.

## Features

- Unified home with Continue listening, Favourite stations, Followed shows and recent podcast entries sorted by publisher date.
- The existing 95 live stations and 962 bundled episode entries across four Relic Radio podcasts and three OTRR/Internet Archive collections.
- Show pages with an illustrative cover, provider description, follow controls, played progress and credits. A podcast entry containing several programmes stays under its podcast feed.
- Search across station names/networks/genres and episode titles/series/descriptions, with source-type and supplied-genre filters.
- Saved, In progress, Played, Unplayed and All episodes views. Save creates a bookmark; audio still requires a connection.
- Play next, Add to end, remove and move-up/down queue controls.
- A full episode player with seek, skip, next-episode and failure actions.
- Bounded recovery: use a supplied fallback link where possible, retry within a limited budget, then stop with Retry/Next/Source choices. Pause cancels pending recovery.
- Versioned backup import supports the earlier format and validates incoming data before changing saved state.

Existing bundled public-domain genre illustrations are reused with their source credits. They are not presented as official show logos. No new audio is hosted or bundled.

## Validation

The Android 15 instrumentation suite covers navigation and screenshots in both themes, catalogue parsers, backup validation and legacy import, radio-favourite round-trip, queue ordering, local HTTP fallback playback, saved seek/pause positions, automatic queue advancement and bounded repeated failure. It starts playback with a visible activity, as a user would.

Physical Samsung battery management, long screen-off sessions, Bluetooth/headset and phone-call interruption behaviour need real-device checks. Live provider availability can change independently of these tests. Offline downloads, playlists, OTRCAT, RadioEchoes and YouTube remain outside this release.
