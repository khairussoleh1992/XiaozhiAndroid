# Update v2: Karakter lokal + tombol bisa dipencet berulang

Penyebab karakter tidak muncul: model & pustaka Live2D dimuat dari
internet (CDN) saat app dibuka, jadi butuh jaringan sempurna dan
kors file://. Sekarang SEMUANYA dibundel di dalam APK (folder assets),
tanpa internet UI tetap tampil.

File yang berubah:
1. BARU (folder)  app/src/main/assets/live2d/
                   - pixi.min.js, live2d-display.min.js, live2dcubismcore.min.js
                   - haru/ (model Haru lengkap: moc3, tekstur, fisik, pose, motion)
2. GANTI          app/src/main/assets/videocall.html
3. GANTI          app/src/main/java/com/xiaozhi/device/MainActivity.kt
4. GANTI          app/src/main/java/com/xiaozhi/device/XiaozhiDevice.kt

Tidak berubah: AudioEngine.kt, layout, gradle (sama seperti v1).

Cara teraman di Codespaces (biar folder baru ikut):
  cd $CODESPACE_NAME  (root repo)
  rm -rf app/src/main/assets
  unzip <paket ini> -d .
  git add . ; git commit -m "v2 local live2d" ; git push

Perbaikan lain:
- Tombol "Mulai Panggilan" kini bisa dipencet berulang kali. Sebelumnya
  setelah sekali tekan, loop koneksi lama masih hidup diam-diam sehingga
  tekanan kedua diabaikan. Kini tiap tekan memulai ulang loop koneksi.
- Ada teks status kecil di bawah tombol ("Memuat karakter..." -> "Karakter siap").
  Kalau gagal, tulisan ERROR merah muncul di situ - screenshot saja.
- Log WebView kini terlihat di Logcat dengan tag "XZWeb" (untuk debugging).
- Saat karakter siap, layar "Mulai Panggilan" jadi transparan sehingga
  karakter terlihat walau sedang menghubungkan.
- Pustaka (Ganti model lain tetap bisa lewat menu Pengaturan, via internet).
