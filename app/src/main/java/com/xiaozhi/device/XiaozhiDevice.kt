package com.xiaozhi.device

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.dnsoverhttps.DnsOverHttps
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Inti aplikasi: meniru firmware xiaozhi-esp32.
 *
 * Alur lengkap (sama seperti ESP32 asli):
 * 1. OTA check   : POST {otaUrl} dengan Device-Id/Client-Id -> server balas
 *                  config websocket + kode aktivasi 6 digit (kalau belum terikat).
 * 2. Aktivasi    : kode 6 digit ditampilkan di layar, user memasukkannya di
 *                  https://xiaozhi.me (console -> tambah perangkat). App polling
 *                  POST {otaUrl}activate sampai server jawab 200.
 * 3. WebSocket   : konek ke websocket.url dengan header Authorization,
 *                  Protocol-Version, Device-Id, Client-Id.
 * 4. Hello       : {"type":"hello", ... audio_params opus 16000 mono 60ms}
 *                  -> server balas session_id + sample rate TTS.
 * 5. Bicara      : {"type":"listen","state":"start","mode":"manual"} lalu frame
 *                  Opus mentah; selesai -> {"type":"listen","state":"stop"}.
 * 6. Jawaban AI  : tts start -> frame opus -> tts stop, plus stt/llm/system.
 *
 * DNS: seluruh trafik memakai DNS-over-HTTPS (dns.google, bootstrap IP 8.8.8.8)
 * sehingga tidak tergantung DNS operator maupun setting Private DNS HP.
 */
class XiaozhiDevice(
    context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onStatus(text: String)
        fun onActivationCode(code: String, message: String)
        fun onActivationDone()
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onChat(role: String, text: String)
        fun onSpeaking(speaking: Boolean)
        fun onThinking(active: Boolean) {}
        fun onMicLevel(level: Float) {}
        fun onTtsLevel(level: Float) {}
    }

    private val appContext = context.applicationContext
    private val mac = DeviceIdentity.mac(context)
    private val uuid = DeviceIdentity.uuid(context)
    private val ui = Handler(Looper.getMainLooper())

    /** DNS-over-HTTPS: lewati DNS operator, langsung tanya dns.google lewat IP */
    private val doh: Dns = try {
        DnsOverHttps.Builder()
            .url("https://dns.google/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("8.8.4.4"))
            .build()
    } catch (e: Exception) {
        Log.w("XiaozhiDevice", "DoH unavailable, pakai DNS sistem", e)
        Dns.SYSTEM
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .dns(doh)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Tanpa pingInterval: server xiaozhi tidak menjawab ping,
    // ping membuat OkHttp memutus koneksi sendiri (ping, pong 30000ms).
    private val wsClient: OkHttpClient = OkHttpClient.Builder()
        .dns(doh)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var running = false
    @Volatile private var generation = 0
    @Volatile private var sessionId: String? = null
    @Volatile private var speaking = false
    @Volatile private var thinking = false
    private var ws: WebSocket? = null
    private var audio: AudioEngine? = null

    fun deviceInfo(): String = "Device-Id: $mac\nClient-Id: $uuid"

    /** Bisa dipanggil berulang: tiap panggilan membatalkan loop lama
     *  dan memulai loop koneksi baru (generation counter). */
    fun start() {
        generation++
        running = true
        val g = generation
        exec.execute { runLoop(g) }
    }

    fun stop() {
        generation++
        running = false
        sessionId = null
        audio?.stopAll()
        try { ws?.close(1000, "bye") } catch (_: Exception) { }
        ws = null
        uiStatus("Disconnected")
    }

    /** Tombol mic ditekan: mulai bicara */
    fun startTalking() {
        if (sessionId == null) return
        val w = ws ?: return
        if (speaking) {
            // barge-in: potong pembicaraan AI seperti wake word
            sendJson(w, JSONObject().apply {
                put("session_id", sessionId)
                put("type", "abort")
                put("reason", "wake_word_detected")
            })
            audio?.stopPlayback()
            speaking = false
            uiSpeaking(false)
        }
        thinking = false
        uiThinking(false)
        sendJson(w, JSONObject().apply {
            put("session_id", sessionId)
            put("type", "listen")
            put("state", "start")
            put("mode", "manual")
        })
        audio?.startMic()
        uiStatus("Sedang mendengarkan...")
    }

    /** Tombol mic dilepas: selesai bicara */
    fun stopTalking() {
        if (sessionId == null) return
        audio?.stopMic()
        thinking = true
        uiThinking(true)
        uiStatus("Sedang berpikir...")
        val w = ws ?: return
        sendJson(w, JSONObject().apply {
            put("session_id", sessionId)
            put("type", "listen")
            put("state", "stop")
        })
    }

    // ---------------- loop utama ----------------

    private fun runLoop(g: Int) {
        while (running && g == generation) {
            try {
                uiStatus("Cek server (OTA)...")
                val ota = otaCheck()
                Log.i("XiaozhiDevice", "OTA resp: $ota")
                val wsCfg = ota.optJSONObject("websocket")
                val wsUrl = wsCfg?.optString("url")?.takeIf { it.isNotEmpty() }
                    ?: "wss://api.tenclass.net/xiaozhi/v1/"
                var token = wsCfg?.optString("token")?.takeIf { it.isNotEmpty() } ?: "test"
                if (!token.contains(" ")) token = "Bearer $token"
                val protoVer = wsCfg?.optInt("version", 1) ?: 1

                // Kode aktivasi 6 digit untuk pairing di console xiaozhi.me
                val activation = ota.optJSONObject("activation")
                if (activation != null && activation.has("code")) {
                    val code = activation.optString("code", "")
                    val msg = activation.optString("message", "")
                    ui.post { listener.onActivationCode(code, msg) }
                    uiStatus("Aktivasi: masukkan kode di console xiaozhi.me")
                    activateLoop()
                    ui.post { listener.onActivationDone() }
                    if (!running) break
                }

                if (!running || g != generation) return
                uiStatus("Menghubungkan websocket...")
                connectWs(wsUrl, token, protoVer, g)
                return   // connectWs menangani selanjutnya lewat listener
            } catch (e: Exception) {
                Log.e("XiaozhiDevice", "loop error", e)
                uiStatus("Error: ${e.message ?: "unknown"}")
                if (!running || g != generation) break
                try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun otaCheck(): JSONObject {
        val body = JSONObject().apply {
            put("version", 2)
            put("mac_address", mac)
            put("uuid", uuid)
            put("chip_model_name", "esp32-s3")
            put("board", JSONObject()
                .put("name", "bread-compact-wifi")
                .put("sdk", JSONObject().put("name", "xiaozhi-android")))
            put("application", JSONObject()
                .put("name", "xiaozhi-android")
                .put("version", BuildConfig.VERSION_NAME)
                .put("compile_time", "Oct 21 2026")
                .put("ota_address", BuildConfig.OTA_URL))
        }
        val req = Request.Builder()
            .url(BuildConfig.OTA_URL)
            .header("Activation-Version", "1")
            .header("Device-Id", mac)
            .header("Client-Id", uuid)
            .header("User-Agent", "bread-compact-wifi/1.7.0")
            .header("Accept-Language", "zh-CN")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("OTA HTTP ${resp.code}")
            return JSONObject(resp.body?.string() ?: "{}")
        }
    }

    /** Polling sampai user selesai memasukkan kode 6 digit di console */
    private fun activateLoop() {
        var tries = 0
        while (running && tries < 40) {
            tries++
            try {
                val req = Request.Builder()
                    .url(BuildConfig.OTA_URL.trimEnd('/') + "/activate")
                    .header("Activation-Version", "1")
                    .header("Device-Id", mac)
                    .header("Client-Id", uuid)
                    .header("User-Agent", "bread-compact-wifi/1.7.0")
                    .post("{}".toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                http.newCall(req).execute().use { resp ->
                    when (resp.code) {
                        200 -> { uiStatus("Perangkat aktif!"); return }
                        202 -> uiStatus("Menunggu kode dimasukkan di console...")
                        else -> Log.w("XiaozhiDevice", "activate HTTP ${resp.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e("XiaozhiDevice", "activate error", e)
            }
            try { Thread.sleep(3000) } catch (_: InterruptedException) { return }
        }
    }

    // ---------------- websocket ----------------

    private fun connectWs(url: String, token: String, protoVer: Int, g: Int) {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", token)
            .header("Protocol-Version", protoVer.toString())
            .header("Device-Id", mac)
            .header("Client-Id", uuid)
            .build()

        val eng = AudioEngine(
            onMicFrame = { frame ->
                // frame opus mentah dari mic -> server
                try { ws?.send(frame.toByteString()) } catch (_: Exception) { }
            },
            onMicLevel = { lvl -> ui.post { listener.onMicLevel(lvl) } },
            onTtsLevel = { lvl -> ui.post { listener.onTtsLevel(lvl) } }
        )
        audio = eng

        if (!running || g != generation) return
        ws = wsClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val hello = JSONObject().apply {
                    put("type", "hello")
                    put("version", protoVer)
                    put("features", JSONObject())
                    put("transport", "websocket")
                    put("audio_params", JSONObject()
                        .put("format", "opus")
                        .put("sample_rate", AudioEngine.MIC_RATE)
                        .put("channels", 1)
                        .put("frame_duration", AudioEngine.FRAME_MS))
                }
                webSocket.send(hello.toString())
                uiStatus("Terhubung, menunggu hello server...")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("XiaozhiDevice", "WS in: $text")
                handleText(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // opus mentah dari server -> speaker
                audio?.feed(bytes.toByteArray())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("XiaozhiDevice", "WS fail: ${t.message} resp=${response?.code}")
                audio?.stopAll()
                ui.post { listener.onDisconnected(t.message ?: "connection failed") }
                scheduleReconnect(g)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i("XiaozhiDevice", "WS closed: $code $reason")
                audio?.stopAll()
                ui.post { listener.onDisconnected("closed: $reason") }
                scheduleReconnect(g)
            }
        })
    }

    private fun handleText(text: String) {
        try {
            val root = JSONObject(text)
            when (root.optString("type")) {
                "hello" -> {
                    sessionId = root.optString("session_id")
                    val audioParams = root.optJSONObject("audio_params")
                    if (audioParams != null) {
                        audio?.serverRate = audioParams.optInt("sample_rate", 24000)
                    }
                    ui.post {
                        listener.onConnected()
                        listener.onStatus("Siap! Tahan tombol tengah untuk bicara")
                    }
                }
                "tts" -> when (root.optString("state")) {
                    "start" -> {
                        speaking = true
                        thinking = false
                        uiThinking(false)
                        audio?.startPlayback()
                        uiSpeaking(true)
                    }
                    "stop" -> {
                        speaking = false
                        thinking = false
                        audio?.stopPlayback()
                        uiThinking(false)
                        uiSpeaking(false)
                    }
                    "sentence_start" -> if (root.has("text")) {
                        uiChat("AI", root.optString("text"))
                    }
                }
                "stt" -> if (root.has("text")) {
                    uiChat("Anda", root.optString("text"))
                }
                "llm" -> { /* emosi AI, diabaikan */ }
                "system" -> { /* perintah sistem (reboot dll), diabaikan */ }
                "mcp" -> { /* MCP belum diimplementasi di versi ini */ }
            }
        } catch (e: Exception) {
            Log.w("XiaozhiDevice", "parse: $text", e)
        }
    }

    private fun sendJson(w: WebSocket, obj: JSONObject) {
        try { w.send(obj.toString()) } catch (_: Exception) { }
    }

    private fun scheduleReconnect(g: Int) {
        if (!running || g != generation) return
        ws = null
        sessionId = null
        exec.execute {
            try { Thread.sleep(3000) } catch (_: InterruptedException) { }
            if (running && g == generation) runLoop(g)
        }
    }

    // ---------------- util UI thread ----------------

    private fun uiStatus(t: String) = ui.post { listener.onStatus(t) }
    private fun uiSpeaking(s: Boolean) = ui.post { listener.onSpeaking(s) }
    private fun uiThinking(s: Boolean) = ui.post { listener.onThinking(s) }
    private fun uiChat(role: String, t: String) = ui.post { listener.onChat(role, t) }

}
