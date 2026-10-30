# Kotatsu Redo — fork by zunvein

Changes compared to the official app (upstream Kotatsu-Redo):

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
- Can be **installed alongside the official Kotatsu app** (different applicationId: `.nightly`).

## Other

- Minified (R8) build — much smaller than a debug APK.
- Update checks against upstream/official repositories are fully disabled.
- Fixed the StrictMode disk-write violation notification spam on Android 16.
