package com.xiaozhi.device

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
/**
 * Inti aplikasi: meniru firmware xiaozhi-esp32.
 *
 * Alur lengkap (sama seperti ESP32 asli):
 * 1. OTA check   : POST {otaUrl} dengan Device-Id/Client-Id -> server balas
 *                  config websocket + kode aktivasi 6 digit (kalau belum terikat).
 * 2. Aktivasi    : kode 6 digit ditampilkan di layar, user memasukkannya di
 *                  xiaozhi.me (console -> tambah perangkat). App polling
 *                  POST {otaUrl}activate sampai server jawab 200.
 * 3. WebSocket   : konek ke websocket.url dengan header Authorization,
 *                  Protocol-Version, Device-Id, Client-Id.
 * 4. Hello       : {"type":"hello", ... audio_params opus 16000 mono 60ms}
 *                  -> server balas session_id + sample rate TTS.
 * 5. Bicara      : {"type":"listen","state":"start","mode":"manual"} lalu frame
 *                  Opus mentah; selesai -> {"type":"listen","state":"stop"}.
 * 6. Jawaban AI  : tts start -> frame opus -> tts stop, plus stt/llm/system.
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
    }

    private val appContext = context.applicationContext
    private val mac = DeviceIdentity.mac(context)
    private val uuid = DeviceIdentity.uuid(context)
    private val ui = Handler(Looper.getMainLooper())

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val wsClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var running = false
    @Volatile private var sessionId: String? = null
    @Volatile private var speaking = false
    private var ws: WebSocket? = null
    private var audio: AudioEngine? = null

    fun deviceInfo(): String = "Device-Id: $mac\nClient-Id: $uuid"

    fun start() {
        if (running) return
        running = true
        exec.execute { runLoop() }
    }

    fun stop() {
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
        sendJson(w, JSONObject().apply {
            put("session_id", sessionId)
            put("type", "listen")
            put("state", "start")
            put("mode", "manual")
        })
        audio?.startMic()
        uiStatus("Mendengarkan... (lepaskan jika selesai)")
    }

    /** Tombol mic dilepas: selesai bicara */
    fun stopTalking() {
        if (sessionId == null) return
        audio?.stopMic()
        val w = ws ?: return
        sendJson(w, JSONObject().apply {
            put("session_id", sessionId)
            put("type", "listen")
            put("state", "stop")
        })
        uiStatus("Menunggu jawaban AI...")
    }

    // ---------------- loop utama ----------------
    private fun runLoop() {
        while (running) {
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

                uiStatus("Menghubungkan websocket...")
                connectWs(wsUrl, token, protoVer)
                return   // connectWs menangani selanjutnya lewat listener
            } catch (e: Exception) {
                Log.e("XiaozhiDevice", "loop error", e)
                uiStatus("Error: ${e.message ?: "unknown"}")
                if (!running) break
                try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun otaCheck(): JSONObject {
        val body = JSONObject().apply {
            put("version", 2)
            put("mac_address", mac)
            put("uuid", uuid)
            put("chip_model_name", "android")
            put("board", JSONObject().put("name", "android").put("sdk", "xiaozhi-android"))
            put("application", JSONObject()
                .put("name", "xiaozhi-android")
                .put("version", "1.0.0")
                .put("compile_time", "2026-10-20T00:00:00Z"))
        }
        val req = Request.Builder()
            .url(BuildConfig.OTA_URL)
            .header("Activation-Version", "1")
            .header("Device-Id", mac)
            .header("Client-Id", uuid)
            .header("User-Agent", "xiaozhi-android/1.0.0")
            .header("Accept-Language", "en-US")
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
                    .header("User-Agent", "xiaozhi-android/1.0.0")
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

    private fun connectWs(url: String, token: String, protoVer: Int) {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", token)
            .header("Protocol-Version", protoVer.toString())
            .header("Device-Id", mac)
            .header("Client-Id", uuid)
            .build()

        val eng = AudioEngine { frame ->
            // frame opus mentah dari mic -> server
            try { ws?.send(frame.toByteString()) } catch (_: Exception) { }
        }
        audio = eng

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
                handleText(text)
                Log.d("XiaozhiDevice", "WS in: $text")
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // opus mentah dari server -> speaker
                audio?.feed(bytes.toByteArray())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                audio?.stopAll()
                ui.post { listener.onDisconnected(t.message ?: "connection failed") }
                scheduleReconnect()
                Log.e("XiaozhiDevice", "WS fail: ${t.message} resp=${response}", t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                audio?.stopAll()
                ui.post { listener.onDisconnected("closed: $reason") }
                scheduleReconnect()
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
                        listener.onStatus("Siap! Tahan tombol untuk bicara")
                    }
                }
                "tts" -> when (root.optString("state")) {
                    "start" -> {
                        speaking = true
                        audio?.startPlayback()
                        uiSpeaking(true)
                    }
                    "stop" -> {
                        speaking = false
                        audio?.stopPlayback()
                        uiSpeaking(false)
                        uiStatus("Siap! Tahan tombol untuk bicara")
                    }
                    "sentence_start" -> if (root.has("text")) {
                        uiChat("AI", root.optString("text"))
                    }
                }
                "stt" -> if (root.has("text")) {
                    uiChat("Anda", root.optString("text"))
                }
                "llm" -> { /* emosi AI, diabaikan */ }
                "system" -> { /* perintah sistem, diabaikan */ }
                "mcp" -> { /* MCP belum diimplementasi */ }
            }
        } catch (e: Exception) {
            Log.w("XiaozhiDevice", "parse: $text", e)
        }
    }

    private fun sendJson(w: WebSocket, obj: JSONObject) {
        try { w.send(obj.toString()) } catch (_: Exception) { }
    }

    private fun scheduleReconnect() {
        if (!running) return
        ws = null
        sessionId = null
        exec.execute {
            try { Thread.sleep(3000) } catch (_: InterruptedException) { }
            if (running) runLoop()
        }
    }

    // ---------------- util UI thread ----------------

    private fun uiStatus(t: String) = ui.post { listener.onStatus(t) }
    private fun uiSpeaking(s: Boolean) = ui.post { listener.onSpeaking(s) }
    private fun uiChat(role: String, t: String) = ui.post { listener.onChat(role, t) }
}
