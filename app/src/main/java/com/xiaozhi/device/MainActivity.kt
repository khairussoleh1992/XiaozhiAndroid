package com.xiaozhi.device

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * Tampilan panggilan video 1-on-1 (Live2D) via WebView assets/videocall.html.
 * Kotlin menangani jaringan + audio; UI, karakter, waveform, dan lip-sync
 * dirender di HTML/JS. Jembatan dua arah:
 *   AndroidBridge.XXX   : JS   -> Kotlin (connect, talkStart, ...)
 *   window.onXXX(...)   : Kotlin -> JS  (status, level audio, chat, ...)
 */
class MainActivity : AppCompatActivity(), XiaozhiDevice.Listener {

    private var device: XiaozhiDevice? = null
    private var connected = false
    private var talking = false

    private lateinit var webView: WebView
    private val permCode = 77

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            @SuppressWarnings("DEPRECATION")
            allowFileAccessFromFileURLs = true
            @SuppressWarnings("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
        }
        webView.setBackgroundColor(0xFF0D1117.toInt())
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(cm: ConsoleMessage): Boolean {
                Log.d("XZWeb", "[${cm.messageLineNumber()}] ${cm.message()}")
                return true
            }
        }
        webView.addJavascriptInterface(Bridge(), "AndroidBridge")
        webView.loadUrl("file:///android_asset/videocall.html")

        device = XiaozhiDevice(this, this)
        pushDeviceInfo()
    }

    /** Jembatan dipanggil dari JS */
    inner class Bridge {
        @JavascriptInterface
        fun connect() {
            runOnUiThread {
                if (connected) return@runOnUiThread
                if (!hasMicPermission()) {
                    ActivityCompat.requestPermissions(
                        this@MainActivity, arrayOf(Manifest.permission.RECORD_AUDIO), permCode)
                } else {
                    // start() kini selalu memulai ulang loop koneksi (generation),
                    // jadi tombol bisa dipencet berulang kali.
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
                js("window.onDisconnected('Panggilan diakhiri')")
            }
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
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permCode && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            device?.start()
        } else {
            Toast.makeText(this, "Izin mic wajib untuk panggilan", Toast.LENGTH_LONG).show()
            js("window.onStatus('Izin mic ditolak')")
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Eksekusi JS di UI thread (aman dipanggil dari thread mana pun) */
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

    // ---------------- callback dari XiaozhiDevice -> WebView ----------------

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
        js("window.onConnected()")
    }

    override fun onDisconnected(reason: String) {
        connected = false
        talking = false
        js("window.onDisconnected(${JSONObject.quote(reason)})")
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

    override fun onDestroy() {
        device?.stop()
        webView.destroy()
        super.onDestroy()
    }
}
