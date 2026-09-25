# OTR Dial 1.2 preview

This preview adds original offline studio artwork with genre colours, a recording
library (play in an installed audio app, share, rename and confirmed deletion),
on-demand stream connectivity checks with timestamps, headphone-disconnect pause
and audio-focus handling. Light mode remains white, with teal and burgundy accents.

Recording now selects MP3/AAC/Ogg filenames from the returned format, keeps partial
audio after network failures, discards empty files, and prevents a new recording
from starting before the previous writer has finished.

Existing features: live playback, favourites, recent stations, search and genre
filters, full-screen player, dark mode, stream metadata, station website/schedule
links and station sharing.

## Precisely what is not implemented

This is not the complete earlier 1.2 roadmap. There is no integrated broadcast
schedule, automatic episode identification, archive download manager, Android Auto
browser, Cast integration, home-screen widget, or automatic catalogue update.
Schedule buttons open broadcaster websites. Artwork is original studio artwork,
not official station logos. The catalogue is unchanged from 0.9; no fresh station
audit is claimed. Stream checks test responses, not English-language content or
programme availability.

Recording still uses an in-process worker, not a dedicated foreground recording
service. Keep playback running while recording; process termination can interrupt
a recording. Device testing remains required for recording and audio interruptions.

## Build

GitHub Actions uses JDK 17 and Gradle 8.9 to run assembleDebug. Download the
OTR-Dial-debug-apk artifact after a successful run. Install over the previous
version only if Android accepts the signing certificate; retain the old app and
recordings if installation reports a signature mismatch.
