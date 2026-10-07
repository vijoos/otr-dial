# OTR Dial 2.5 preview

The app now starts with Radio, Repo, Podcasts, YouTube and Favourites tabs.
Search remains in the header; queue, history, downloads, bookmarks and playlists
remain accessible. The native episode player has a Back button and hides bottom
tabs. Radio playback returns to the platform when opened from its station cards.
Theme switching is available in the header. Existing credited illustrations are
used on source cards; they are not claimed to be official station logos.

## Sources

The 100-entry user workbook is included as a source directory, plus OTRCAT's
daily selections website (101 entries). Forty-four radio entries map to existing
station IDs, preserving favourites and avoiding duplicate station rows. The live
station catalogue remains 95 stations; this is not 100 additional streams.

Five newly configured RSS feeds: The Horror, Strange Tales, Relic Radio Thrillers,
Orson Welles On The Air, and Choice Classic Radio. Six new individual Internet
Archive imports: Suspense, All Star Western Theatre, Hopalong Cassidy,
The Sealed Book, Inheritance and Broadway Is My Beat. Tap Browse episodes to fetch
an unimported catalogue; provider errors preserve existing entries.

Website-only sources retain original links and warnings. RadioEchoes, OTRR web
library, OTRCAT daily lists and the broader Archive collection do not yet have
native catalogue adapters. Five YouTube channels open YouTube/browser; no audio
extraction, video downloads, embedded player or background YouTube player is added.
Other apps and Reddit are optional external resource links.

RSS imports now accept up to 5,000 entries from the supplied feed within the
existing 8 MiB response limit. This does not recover episodes omitted by a feed.
Archive imports accept MP3, M4A, Ogg and FLAC; prefer a lossy derivative when several
formats refer to the same original. No audio files are bundled in the APK.
Saved source/channel links are included in existing version-3 backup state.

## Evidence and validation

Directory provenance: OTR_Source_Directory.xlsx, 6 October 2026. Feed addresses:
https://www.relicradio.com/otr/subscribe/ and https://choiceclassicradio.podbean.com/.
Old Time Retro Radio's YouTube link is published on https://www.oldtimeretroradio.com/.
Source-page reachability is not a guarantee of current audio availability.
All five new feeds and six new Archive items returned audio entries in the
pre-build catalogue check. Frank Race returned no compatible individual audio
files and remains website-only. These checks do not download/play every episode.

Android instrumentation covers five-tab navigation in both themes, source mapping,
source favourites backup, long RSS feeds and FLAC derivative selection, together
with the existing playback, offline download and collection suite.
Physical Samsung screen-off playback, Bluetooth and phone-call interruptions
still require device checks. Live provider playback depends on current network
and service availability.
