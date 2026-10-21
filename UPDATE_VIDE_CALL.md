# Update: Panggilan Video Live2D (Xiaozhi)

File yang berubah (paste di Codespaces, lokasi path harus sama):

1. BARU   app/src/main/assets/videocall.html
2. GANTI  app/src/main/java/com/xiaozhi/device/MainActivity.kt
3. GANTI  app/src/main/java/com/xiaozhi/device/XiaozhiDevice.kt
4. GANTI  app/src/main/java/com/xiaozhi/device/AudioEngine.kt
5. GANTI  app/src/main/res/layout/activity_main.xml
6. GANTI  app/build.gradle.kts   (menambah okhttp-dnsoverhttps)

Setelah paste semua: git add . ; git commit -m "video call UI + DoH" ; git push
GitHub Actions akan build APK baru (artifact).

Yang baru:
- Tampilan panggilan video 1-on-1: karakter anime Live2D half-body
  (default model Haru), latar kamar cozy blur, header "Live Video Call"
  dengan dot hijau berdenyut + tombol End Call merah, bottom sheet
  glassmorphism (status, waveform neon pink-cyan, tombol mic/kamera/ekspresi).
- Tahan tombol tengah (pink) untuk bicara, lepas untuk kirim.
- Lip-sync mulut karakter mengikuti level audio TTS sungguhan.
- Waveform bereaksi ke suara mic (pink-cyan).
- Status: mendengarkan / berpikir (partikel glowing) / bicara.
- Mata & kepala mengikuti sentuhan layar.
- Riwayat percakapan: tombol buku; Pengaturan: roda gigi
  (ganti URL model Live2D, zoom & posisi karakter, tersimpan permanen).
- Kode aktivasi 6 digit tampil sebagai overlay besar.
- Fix koneksi: DNS-over-HTTPS bawaan app (dns.google, bootstrap 8.8.8.8)
  sehingga tidak lagi tergantung DNS operator / Private DNS.
- Fix ping-pong: pingInterval dihapus (tidak ada "ping, pong 30000ms").

Model karakter bisa diganti dari Pengaturan: isi URL model3.json
misalnya dari repo GitHub kamu (raw.githubusercontent.com atau jsdelivr).
