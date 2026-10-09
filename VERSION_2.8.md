# OTR Dial 2.8 preview

Built from the tested 2.7 source. Application ID remains `com.example.otrdial.library21preview`, version code 28. The existing signing key is required by CI; a replacement key is never generated.

## Changes

- Downloads use a separate temporary file, check its expected size and audio track, then atomically replace the stable offline file. Failed replacements retain the previous copy.
- Episode catalogue migrates from preferences into SQLite in one transaction. Original episode IDs and personal-state preferences are retained. Indexed source queries and full-text search serve catalogue pages.
- Backup version 4 supports large catalogues. Export and import share the same 256 MB UTF-8 limit; the old 10,000-entry limits are removed. Versions 1–3 remain accepted. Validation precedes writes. Audio files and recordings are excluded.
- Radio opens with search, Continue Listening and favourite stations. The five destinations are Radio, Archives, Podcasts, YouTube and Library.
- Compact episode and station rows, smaller source headers, quieter utility actions and artwork that fits without cropping faces. Main catalogue and collection episode lists create rows as needed.
- Shared live/episode player layout; persistent collection mini-player; remembered browsing routes, searches and scroll positions.
- Search includes directory entries before episodes are loaded. Native catalogue sources and external websites/YouTube have distinct actions. Added native sources are omitted from duplicate directory cards.
- Curated cross-source programme entries initially cover Gunsmoke, X Minus One, Our Miss Brooks, Jack Benny and Suspense. Recording IDs and provider attribution stay separate. Existing broader programme-title browsing remains available; it does not merge recordings.
- Source errors retain cached episodes and offer retry; successful refresh times are displayed only when recorded. Obsolete artwork requests are cancelled as rows leave the screen.

## Validation and limits

See `checks-2.8.md` for automated results and the outstanding physical-device checklist. This remains a preview until real-phone checks are completed. No new sources were added.

Some personal-library filters and programme aggregation still read catalogue metadata into memory. The shared backup size ceiling prevents export/import disagreement, but large JSON backups still need temporary memory; streaming backup processing is a future hardening task. The measured emulator results do not establish mid-range phone performance.
