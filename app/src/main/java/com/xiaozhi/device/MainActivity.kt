package com.xiaozhi.device

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.view.WindowManager
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

class MainActivity : AppCompatActivity(), XiaozhiDevice.Listener {

    private var device: XiaozhiDevice? = null
    private var connected = false
    private var talking = false

    private lateinit var webView: WebView
    private var wakeLock: PowerManager.WakeLock? = null
    @Suppress("DEPRECATION")
    private var screenLock: PowerManager.WakeLock? = null
    private val permCode = 77

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Xiaozhi::BackgroundWakeLock"
        )
        @Suppress("DEPRECATION")
        screenLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "Xiaozhi::VideoCallScreenLock"
        )

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
        }
        webView.setBackgroundColor(0xFF0D1117.toInt())
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                pushDeviceInfo()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(cm: ConsoleMessage): Boolean {
                Log.d("XZWeb", "[${cm.lineNumber()}] ${cm.message()}")
                return true
            }

            override fun onPermissionRequest(req: PermissionRequest) {
                runOnUiThread {
                    val wantsVideo = req.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                    val granted = ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                    if (wantsVideo && granted) req.grant(req.resources) else req.deny()
                }
            }
        }
        webView.addJavascriptInterface(Bridge(), "AndroidBridge")
        webView.loadUrl("file:///android_asset/videocall.html")

        device = XiaozhiDevice(this, this)
        pushDeviceInfo()
    }

    inner class Bridge {
        @JavascriptInterface
        fun connect() {
            runOnUiThread {
                if (!hasMicPermission()) {
                    ActivityCompat.requestPermissions(
                        this@MainActivity, arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA,
                            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), permCode)
                } else {
                    device?.stop()
                    connected = false
                    talking = false
                    stopCallService()
                    device?.start()
                }
            }
        }

        @JavascriptInterface
        fun disconnect() {
            runOnUiThread {
                talking = false
                connected = false
                device?.stop()
                stopCallService()
                js("window.onDisconnected('Panggilan diakhiri')")
            }
        }

        @JavascriptInterface
        fun micToggle() {
            runOnUiThread { device?.micToggle() }
        }

        @JavascriptInterface
        fun talkStart() {
            runOnUiThread {
                if (connected && !talking) {
                    talking = true
                    device?.startTalking()
                }
            }
        }

        @JavascriptInterface
        fun talkStop() {
            runOnUiThread {
                if (talking) {
                    talking = false
                    device?.stopTalking()
                }
            }
        }

        // Fungsi pintar buka aplikasi via inisiatif swipe & klik otomatis
        @JavascriptInterface
        fun openApp(appName: String) {
            runOnUiThread {
                val service = XiaozhiAccessibilityService.inst
                if (service != null) {
                    service.openAppSmart(appName) { status, detail ->
                        when (status) {
                            1 -> {
                                js("window.onChat('assistant', 'Membuka $detail')")
                            }
                            2 -> {
                                js("window.onChat('assistant', 'Ditemukan beberapa pilihan untuk $appName: $detail. Mau buka yang mana?')")
                            }
                            else -> {
                                js("window.onChat('assistant', 'Aplikasi $appName tidak ditemukan di layar.')")
                            }
                        }
                    }
                } else {
                    js("window.onChat('assistant', 'Layanan Aksesibilitas belum aktif di pengaturan HP.')")
                }
            }
        }

        @JavascriptInterface
        fun clickScreen(x: Float, y: Float) {
            runOnUiThread {
                val service = XiaozhiAccessibilityService.inst
                if (service != null) {
                    service.tapXY(x, y) { success ->
                        if (!success) {
                            js("window.onChat('assistant', 'Gagal melakukan klik di layar.')")
                        }
                    }
                } else {
                    js("window.onChat('assistant', 'Layanan Aksesibilitas belum aktif.')")
                }
            }
        }

        @JavascriptInterface
        fun swipeScreen(x1: Float, y1: Float, x2: Float, y2: Float, durMs: Long) {
            runOnUiThread {
                XiaozhiAccessibilityService.inst?.swipeXY(x1, y1, x2, y2, durMs) { _ -> }
            }
        }

        @JavascriptInterface
        fun goHome() {
            runOnUiThread {
                XiaozhiAccessibilityService.inst?.globalAction(4) // GLOBAL_ACTION_HOME
            }
        }

        @JavascriptInterface
        fun goBack() {
            runOnUiThread {
                XiaozhiAccessibilityService.inst?.globalAction(1) // GLOBAL_ACTION_BACK
            }
        }

        @JavascriptInterface
        fun readScreen() {
            runOnUiThread {
                val service = XiaozhiAccessibilityService.inst
                if (service != null) {
                    val screenText = service.dumpScreenText()
                    js("window.onScreenText(${JSONObject.quote(screenText)})")
                } else {
                    js("window.onChat('assistant', 'Layanan Aksesibilitas tidak aktif.')")
                }
            }
        }
    }

    private fun startCallService() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        wakeLock?.let { if (!it.isHeld) it.acquire() }
        // Layar tetap menyala selama panggilan aktif, MESKI user pindah ke
        // home / aplikasi lain. Dilepas saat end call (stopCallService).
        screenLock?.let { if (!it.isHeld) it.acquire() }

        val serviceIntent = Intent(this, CallForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopCallService() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        wakeLock?.let { if (it.isHeld) it.release() }
        screenLock?.let { if (it.isHeld) it.release() }
        stopService(Intent(this, CallForegroundService::class.java))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permCode && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            device?.stop()
            connected = false
            talking = false
            device?.start()
        } else {
            Toast.makeText(this, "Izin mic wajib untuk panggilan", Toast.LENGTH_LONG).show()
            js("window.onStatus('Izin mic ditolak')")
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun js(code: String) {
        runOnUiThread {
            if (webView.visibility == View.VISIBLE) {
                webView.evaluateJavascript(code, null)
            }
        }
    }

    private fun pushDeviceInfo() {
        val info = device?.deviceInfo() ?: ""
        js("window.onDeviceInfo(${JSONObject.quote(info)})")
    }

    override fun onStatus(text: String) {
        js("window.onStatus(${JSONObject.quote(text)})")
    }

    override fun onActivationCode(code: String, message: String) {
        js("window.onActivation(${JSONObject.quote(code)}, ${JSONObject.quote(message)})")
    }

    override fun onActivationDone() {
        js("window.onActivationDone()")
    }

    override fun onConnected() {
        connected = true
        startCallService()
        js("window.onConnected()")
    }

    override fun onDisconnected(reason: String) {
        connected = false
        talking = false
        stopCallService()
        js("window.onDisconnected(${JSONObject.quote(reason)})")
    }

    override fun onDebugLog(line: String) {
        js("window.onXzLog(${JSONObject.quote(line)})")
    }

    override fun onChat(role: String, text: String) {
        js("window.onChat(${JSONObject.quote(role)}, ${JSONObject.quote(text)})")
    }

    override fun onSpeaking(speaking: Boolean) {
        js("window.onSpeaking($speaking)")
    }

    override fun onThinking(active: Boolean) {
        js("window.onThinking($active)")
    }

    override fun onMicLevel(level: Float) {
        js("window.onMicLevel($level)")
    }

    override fun onTtsLevel(level: Float) {
        js("window.onTtsLevel($level)")
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        stopCallService()
        device?.stop()
        webView.destroy()
        super.onDestroy()
    }
