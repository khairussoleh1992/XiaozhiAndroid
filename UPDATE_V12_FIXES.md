# v12: stabilitas panggilan + layar menyala + GPS + kontrol WhatsApp/Outlook

## Bug: sering tidak merespon setelah beberapa percakapan
Penyebab di kode: (1) jika pesan `tts stop` dari server hilang, flag
`speaking` macet `true` selamanya -> mic tidak pernah mengirim lagi;
(2) `AudioRecord.read()` yang balas 0 membuat thread mic keluar diam-diam.
Perbaikan:
- Watchdog TTS di `XiaozhiDevice.kt`: kalau AI dianggap bicara tapi 25 detik
  tanpa audio, sesi otomatis direset ke mode dengar (log: "watchdog").
- Flag `speaking/thinking` direset saat websocket gagal/tertutup.
- Mic di `AudioEngine.kt` kini tahan banting: read kosong di-retry, dan
  `AudioRecord` dibuat ulang otomatis kalau benar-benar mati.

## Layar tetap menyala selama videocall
`MainActivity.kt` menahan `SCREEN_BRIGHT_WAKE_LOCK` selama panggilan aktif,
jadi layar tidak mati meski pindah ke home/aplikasi lain, dan kembali
normal setelah End Call.

## Buka aplikasi lebih pintar (tanpa layar uninstall)
- `open_app` (MCP) dan `openAppSmart` (aksesibilitas) sekarang cari dulu di
  PackageManager dengan pencocokan lunak ("wa" -> WhatsApp), langsung buka,
  tidak ada lagi layar pilihan/info app/uninstall.
- Kalau tidak ketemu, layar discan otomatis: halaman home, app drawer,
  sampai 6 halaman.

## Tool baru untuk AI (MCP)
- `self.phone.get_location` : lokasi GPS aktual (lat/lon, akurasi, alamat
  bila tersedia). Perlu izin Lokasi (diminta saat mulai panggilan).
- `self.phone.whatsapp_read` : buka WhatsApp, bisa langsung buka chat
  tertentu via pencarian, lalu baca isi percakapan.
- `self.phone.whatsapp_reply`: buka chat, ketik pesan, tekan Send.
- `self.phone.outlook_read` : baca email terbaru di inbox Outlook.
- `self.phone.outlook_reply` : balas email yang sedang terbuka.

File baru: `LocationHelper.kt`.
Perubahan: `XiaozhiDevice.kt`, `XiaozhiAccessibilityService.kt`,
`MainActivity.kt`, `AudioEngine.kt`, `AndroidManifest.xml`.

## Tambahan (v12.1): buka aplikasi terverifikasi
Masalah: klik ikon "berhasil" tapi aplikasi belum terbuka (mis. yang tereklik
adalah preview folder). Perbaikan:
- Setelah klik, dicek benar-benar ada aplikasi terbuka (window berpindah dari
  launcher). Kalau tidak, kemungkinan folder kebuka -> isi folder discan
  ulang dan ikon diklik lagi (maks 2 tingkat).
- Ikon besar diprioritaskan dibanding ikon kecil di preview folder.
- Pencocokan label lebih lunak: "google maps" cocok dengan aplikasi "Maps".
- Deskripsi tool open_app kini memerintahkan AI memakai open_app, bukan tap
  manual.
