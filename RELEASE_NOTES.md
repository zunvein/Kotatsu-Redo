# Kotatsu Redo — fork by zunvein

Changes compared to the official app (upstream Kotatsu-Redo):

## 9.9.1 — LikeManga works again

- **LikeManga fixed.** The source moved from `likemanga.ink` to `mgread.io` and switched to a new
  platform, which broke every selector and made the app throw
  `ParseException: Cannot find "#summary_shortened"`. The parser is ported to the new site:
  details, chapter list, listing, filters, search — and the chapter reader, which now unlocks the
  site's encrypted image payload before loading the pages.
- Existing library entries keep working: manga and chapter URLs use the same
  `/manga/<slug>/chapter-N/` shape as before, so nothing has to be migrated.

## Fix feature — auto-migrate manga from dead sources

- **Batch Fix in Favourites**: select multiple manga at once → ⋮ menu → **Fix** — every entry gets a replacement source automatically, mixed sources in one batch.
- Searches **all sources** — enabled and disabled, no language gate.
- Takes the **top-ranked search hit** (community ranking) without probing details first — aggregators with a working search but broken details still get matched.
- **Automatic retry with alternative titles** when the primary title finds nothing.
- **NSFW filter is bypassed** during auto-fix — a dead entry is worse than an 18+ source. The manual alternatives screen still follows the filter.
- Search budget: 90 seconds for the primary title + 30 seconds per retry.
- **Fix report**: summary notification (Fixed X, failed Y, skipped Z) → themed report screen with Copy & Share buttons.
- Per-manga progress notification (k/N).

## Updates & releases

- **In-app update check** against this very repo: Settings → About → tap the version, or the badge shown when the app opens. The changelog is these release notes.
- **Per-ABI APKs**: `arm64-v8a` (modern phones), `armeabi-v7a` (older devices), `universal` (works everywhere — used for in-app updates).
- **Stable signing key** across builds → updates install cleanly over previous versions.
- The release APK is the **stable app with the clean applicationId** — a drop-in replacement for the official/community app. If that one is installed, uninstall it first (our builds use a different signing key) and restore your data via Kotatsu's backup/restore.
- A separate **nightly test build** (applicationId `.nightly`, shown as "Kotatsu Nightly") ships via CI artifacts for testing upcoming features and can sit alongside the stable app.

## Other

- Minified (R8) build — much smaller than a debug APK.
- Update checks against upstream/official repositories are fully disabled.
- Fixed the StrictMode disk-write violation notification spam on Android 16.
