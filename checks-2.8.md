# 2.8 release checks

Status: validation in progress. No APK should be labelled tested until its exact workflow run passes.

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
