# Update v4: diagnostik + fallback otomatis karakter

Kabar baik: koneksi sudah TERSAMBUNG ("Tersambung, menunggu info
server..." berarti DoH fix bekerja). Yang error tinggal karakter.

Error "model gagal dimuat (Cannot read properties of undefined
(reading 'from'))" = pustaka live2d-display di folder assets
kemungkinan terpotong saat upload. Versi ini:
- Cek tiap komponen (PIXI, Cubism core, pustaka live2d) satu per satu.
- Kalau pustaka lokal rusak/kurang, OTOMATIS dimuat ulang dari CDN.
- Kalau model lokal gagal, OTOMATIS coba model dari internet.
- Semua error ditampilkan sekaligus (maks 3) di bawah tombol, jadi
  kalau masih gagal screenshot-nya langsung menunjukkan penyebab.

Yang berubah sejak v3: HANYA app/src/main/assets/videocall.html.

Cara pasang:
  unzip xiaozhi_v4_diag.zip -d .
  git add . ; git commit -m "v4 diag" ; git push

Sekalian cek ukuran file pustaka di repo (harus sama persis):
  ls -l app/src/main/assets/live2d/
  pixi.min.js                 = 460333 bytes
  live2d-display.min.js       = 126855 bytes
  live2dcubismcore.min.js     = 207155 bytes
  haru/haru_greeter_t03.moc3  = 384704 bytes
  haru/haru_greeter_t03.2048/texture_00.png = 1526786 bytes
  haru/haru_greeter_t03.2048/texture_01.png = 1188664 bytes
Kalau ada yang beda ukurannya, upload ulang file itu (dari zip ini).
