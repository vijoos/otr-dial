# 2.8 release checks

Status: final APK passed 14 Android 15 instrumentation tests plus a separate 2.7 upgrade test in run 38017896047. Tested source commit: 8a0304a8c48236231ef996fe74a16df63dd23c81. Physical-device checks remain pending.

## Automated gates

- Build application and instrumentation APKs.
- Validate packaging and APK signatures.
- Compare certificate against the exact tested 2.7 APK.
- Install 2.7, seed its original storage schema, install 2.8 with `adb install -r`, verify collection preservation without uninstalling.
- Exercise a 25,000-episode catalogue: migration, indexed search, backup over 12 MB, restoration, browse and refresh timings.
- Reject malformed backup data before changing the catalogue or personal state.
- Preserve an offline copy during replacement with networking disabled; reject invalid replacement audio.
- Recheck parsing, collections, local playback, retries, pause/seek/queue behaviour, both themes and navigation.
- Check search and Back restoration.
- Upload APK only after passing gates; preserve failure logs separately.

## Physical-device checks still required

Not performed in this environment: installation over the user's actual 2.7 collection; a representative mid-range phone with 25,000 episodes; TalkBack; large system fonts and narrow-screen layout; extended screen-off playback; wired/Bluetooth route changes; incoming calls; switching Wi-Fi and mobile data during playback; storage exhaustion during download/restore.

## Scope notes

Curated cross-provider mapping initially covers five named programmes. Artwork remains a mixture of publisher images, credited programme photographs and labelled illustrations. Upstream metadata and provider availability vary. Website-only providers and YouTube remain external links. No audio redistribution, website scraping or new catalogue expansion is introduced.

## Measured large-catalogue results

Android 15 x86_64 Pixel 2 emulator, 25,000 generated episodes, 24,692,409-byte backup (run 38017896047):

| Operation | Elapsed |
| --- | ---: |
| Preferences-to-SQLite migration | 6,282 ms |
| Indexed multi-term search | 1 ms |
| Backup export | 3,818 ms |
| Backup restore | 6,192 ms |
| Open source browsing screen | 1,565 ms |
| Refresh and remove unprotected old entries | 1,716 ms |

These are single-run emulator measurements, not phone benchmarks. The first migration can cause a noticeable first-launch delay for a large legacy catalogue; physical-device startup responsiveness still needs validation.

APK SHA-256: `1b31ac7fe472f83388f9c8c9824308b79701e874000e4b94a1db95bec83e7030`. Certificate SHA-256 matches 2.7: `47c3ceeec4605d48482f00a840cda0e218071084083defcaab66e3a4241e17aa`.
