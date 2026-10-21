# v11: self-view kamera (seperti video call) + foto AI dari kamera depan

1. Tombol kamera kini menampilkan preview kamera depan kamu di
   pojok kanan atas (PiP, mirip video call 1-on-1), bukan cuma
   ikon. Ketuk lagi untuk mematikan.
2. WebView kini mengizinkan getUserMedia (onPermissionRequest
   di WebChromeClient) selama izin kamera runtime sudah
   diberikan.
3. Saat AI memakai tool kamera (lihat/cek sesuatu), foto diambil
   dari KAMERA DEPAN supaya konsisten dengan preview yang
   tampil di layar.

Catatan: server Xiaozhi tetap tidak mendukung streaming video
2 arah sungguhan - yang dikirim tetap audio + foto sesekali.
Self-view ini hanya tampilan lokal di layarmu.

Yang berubah: videocall.html, MainActivity.kt, XiaozhiDevice.kt
CARA PASANG:
  unzip xiaozhi_v11_selfcam.zip -o -d .
  git add -A ; git commit -m "v11 selfcam" ; git push
