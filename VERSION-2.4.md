# OTR Dial 2.4 preview

This is a combined implementation following the 2.2–2.4 proposals, built on 2.1. It retains the 95 stations and 962 bundled episode entries. It is not a claim that every item in the exploratory roadmap is finished.

## Installation and data

The application ID remains `com.example.otrdial.library21preview`; version code is 24. The workflow reuses the 2.1 signing-key cache. Verify the APK certificate before distributing it as an in-place update. Export a backup before updating; do not uninstall 2.1. Versions before 2.1 have different app IDs and need backup import.

Version 3 backups include custom sources, playlists, timestamp bookmarks, programme follows, catalogue, listening progress/history, source follows, queue, radio favourites, recent stations and theme. Versions 1 and 2 remain importable. Lists merge; matching imported state replaces matching local state. Downloaded audio, download jobs and background-notification preferences are device-specific and excluded.

## Included

- More visual home with cover cards, daily picks, compact heading, icon-labelled navigation and mini-player play/pause. Light and dark themes retain system-bar spacing.
- Programme pages based on explicit title matches or source labels, follow controls, listening status, title/publication-date/duration/unplayed sorting, and conservative alternative-recording matching. Mixed-programme podcasts remain intact.
- Mood browsing using source-supplied genre tags; short listens require known durations under 20 minutes. Suggestions use local programme follows and require no account.
- Playlists with creation, rename, ordering, removal and playback; separate from the transient queue.
- Timestamp bookmarks with optional notes; listening history with a clear-history control.
- Episode downloads managed by Android, with progress, cancel/remove, retry, used-storage display, Wi-Fi-only preference for new downloads and local playback when successfully downloaded. Downloads are per episode; no live recording was added.
- Recent searches, source result links, existing station/episode search, and custom sources integrated into catalogue search.
- User-supplied RSS feeds and public Internet Archive audio items: preview titles before import, manual refresh, source follow/removal and refresh status. Archive collection containers without audio files are not recursively imported.
- Optional approximately daily followed-podcast refresh and update notification on an unmetered network. Android controls scheduling. No automatic downloads.
- Feed-supplied artwork when present in supported RSS image tags, with bounded disk caching and existing credited illustrations as fallback. Feed artwork is attributed to the publisher; it is not labelled public domain.
- Episode details and original-provider links; fallback recovery from 2.1 retained.
- OTRCAT and RadioEchoes website links in Sources. These open the browser; they are not native catalogue integrations and are excluded from app search.

## Deliberate limits

No native OTRCAT three-day parser, RadioEchoes catalogue importer, YouTube playback, automatic download deletion, strict storage quota, cloud account/sync or audio restoration. RSS imports must be checked by the user for English-language OTR content. Arbitrary external feeds are not automatically classified or verified.

Programme identification is conservative and incomplete. Publication dates are not converted into unverified original broadcast dates. Alternative recording groups use exact normalised title and series matches and never delete files. Artwork can remain illustrative where publishers supply none.

Signing-key reuse currently depends on the GitHub Actions cache; long-term production signing should move to a securely retained release key. No signing key is committed to the public repository.

## Validation

Android 15 instrumentation includes the original navigation, playback fallback, bounded repeated failure, pause/seek progress and queue tests; new tests cover collection backup merging and invalid-import rejection, legacy backups, programme matching, RSS artwork parsing, both-theme collection screens and local download playback after a fixture server closes.

Real-device checks are still required for Samsung battery management, long screen-off playback, Bluetooth/calls, Wi-Fi/mobile transitions, actual daily scheduling and live source availability. The app does not claim every listed provider is reachable at all times.
