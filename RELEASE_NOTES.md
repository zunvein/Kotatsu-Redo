# Kotatsu Redo — fork by zunvein

Changes compared to the official app (upstream Kotatsu-Redo):

## 9.9.3 — synced with upstream 9.8.6

- **Everything upstream shipped in 9.8.6 is in this build**: the erase gesture in the reader (rub over
  an ad or logo and it stays gone, in every chapter and series), the hidden images screen under Reader
  settings with backup support, the rewritten Explore screen (For You / Top), reorganised settings with
  a much better search, and the old Explore screen renamed to Sources.
- **The batch Fix this fork adds is untouched** by the sync — same behaviour as before.
- **Parser dependency switched to the upstream build.** Upstream has rewritten LikeManga (now MGRead)
  for the new site itself, so the fork's own parser patch is no longer carried. The practical
  difference: encrypted chapters on that source open in the site's own reader instead of being
  decrypted inside the app.
- One local change: the new hidden images database ships as version 1. Upstream does not keep its
  Room schema files in git, so its 1 → 7 auto-migration chain cannot be generated from a clean
  checkout. The database is new in this release, so there is nothing to migrate.
- Sources removed by upstream (Comix, Kaliscan) are gone; library entries on them show as an unknown
  source — that is what the Fix feature is for.

## Fix feature — auto-migrate manga from dead sources

- **Batch Fix in Favourites**: select multiple manga at once → ⋮ menu → **Fix** — every entry gets a replacement source automatically, mixed sources in one batch.
- Searches **all sources** — enabled and disabled, no language gate.
- Takes the **top-ranked search hit** (community ranking) without probing details first — aggregators with a working search but broken details still get matched.
- **Automatic retry with alternative titles** when the primary title finds nothing.
- **NSFW filter is bypassed** during auto-fix — a dead entry is worse than an 18+ source. The manual alternatives screen still follows the filter.
- Search budget: 90 seconds for the primary title + 30 seconds per retry.
- **Fix report**: summary notification (Fixed X, failed Y, skipped Z) → themed report screen with covers, per-item reasons, tap-through to the new entry, Copy & Share buttons.
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

### Which APK?

- `arm64-v8a` — almost every phone from the last several years.
- `armeabi-v7a` — older 32-bit devices.
- `universal` — if you are not sure, take this one; it also works for the in-app updater.