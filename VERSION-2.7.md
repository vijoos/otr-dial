# OTR Dial 2.7 preview — source candidate

## Changes
- Cleaner episode player with larger artwork, centred titles, a compact action row and secondary actions under More.
- Mini-player thumbnail and accessible play/pause icons; hide duplicate app headers during full-screen playback.
- Fresh teal and blue accents with warm highlights, white light surfaces and navy dark surfaces.
- Existing five platform tabs and light/dark modes retained.
- Larger radio artwork, with smaller control margins for narrow phones.
- Three additional small historical photographs: Jack Benny radio cast, William Conrad for Gunsmoke, and a CBS studio microphone. Individual source and licence details are in artwork/credits.json and the app credits. The first two are listed by Wikimedia Commons as public domain in the US; the studio photo is CC0.
- Prevent stale artwork requests from replacing a newer item with no image.
- Carry forward the 2.6 catalogue additions without repacking an existing APK.
- CI rejects compressed/misaligned Android resources, verifies signatures and publishes the APK only after emulator installation/tests pass.
- CI requires the existing signing key rather than silently creating a different key when the cache expires. Restore the original key through OTR_PREVIEW_KEY_BASE64 if needed (existing Android debug alias/password).

## Validation status
Source candidate only: not yet compiled, installed or visually checked on Android. Local Android/Gradle tools are unavailable. Packaging check passes the original 2.5 APK and correctly rejects the repacked 2.6 APK with compressed resources. XML/JSON validity and whitespace checks passed. No APK is supplied as part of this source package.

## Build on GitHub
Replace the corresponding files in vijoos/otr-dial, including .github/workflows/build-apk.yml (or the matching workflow file in this project). Preserve the existing signing key. Run Build OTR Dial APK from Actions. Download OTR-Dial-2.7-debug-apk only after the whole run succeeds. Do not rename a source ZIP to APK or repack an older APK. If the signing key is unavailable, recover the key before attempting an in-place update; keep the installed app and its data.

## Next enhancements recommended
- Series-specific artwork coverage beyond the two initial programmes, with explicit source/licence mapping.
- Combined Continue Listening and recently played shelf on the main platform screen.
- Source-health labels with the last checked date and direct retry actions.
- Search by show, performer and genre; source grouping to reduce duplicate results.
