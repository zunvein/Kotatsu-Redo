# Kotatsu Redo — fork zunvein

Perubahan dibanding app official (upstream Kotatsu-Redo):

## Fitur Fix — migrasi otomatis manga sumber mati

- **Fix massal di Favourites**: pilih banyak manga sekaligus → menu ⋮ → **Fix** — semua dicariin alternatif otomatis, bisa campur source apa pun.
- Nyari di **semua source**: yang aktif maupun yang nonaktif, tanpa batasan bahasa.
- Ambil **hasil pencarian teratas** (peringkat komunitas) tanpa ngecek details dulu — source aggregator yang search-nya jalan tapi details-nya rusak tetap ke-hit.
- **Retry otomatis pakai judul alternatif** kalau judul utama gak ketemu.
- **Filter NSFW di-bypass** saat auto-fix — manga mati lebih buruk daripada source 18+. Layar alternatif manual tetap ngikutin filter.
- Budget pencarian: 90 detik judul utama + 30 detik per retry.
- **Laporan Fix**: notif ringkasan (Fixed X, failed Y, skipped Z) → layar laporan ber-thema app dengan tombol Copy & Share.
- Notif progres per-manga (k/N).

## Update & rilis

- **Update check in-app** dari repo ini sendiri: Settings → About → ketuk versi, atau badge pas app dibuka. Changelog = catatan rilis ini.
- **APK per-ABI**: `arm64-v8a` (HP modern), `armeabi-v7a` (HP lama), `universal` (jalan di mana aja — dipakai buat update in-app).
- **Signature tetap** antar build → update-over-update mulus tanpa konflik signature.
- Bisa **coexist dengan app official Kotatsu** (applicationId beda: `.nightly`).

## Lain-lain

- Build minified (R8) — APK jauh lebih kecil daripada build debug.
- Update check ke repo upstream/official dimatikan total (hemat kuota & privacy).
- Notif spam StrictMode (disk-write violation) di Android 16 diperbaiki.
