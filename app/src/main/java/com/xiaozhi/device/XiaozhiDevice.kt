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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.HandlerThread
import okhttp3.MultipartBody
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.Executors

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
        fun onDebugLog(line: String) {}
    }

    /** Log terlihat di layar (drawer LOG) supaya HP bisa cerita sendiri */
    fun xzlog(s: String) {
        Log.d("XiaozhiDevice", "[LOG] $s")
        ui.post { listener.onDebugLog(s) }
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

    // Mode bicara otomatis: mic selalu aktif setelah connect,
    // server yang mendetarkan suara kita (VAD). Tidak perlu tombol.
    private val autoMode = true
    @Volatile private var micMuted = false

    // ---- vision / kamera (MCP) ----
    @Volatile private var visionUrl: String? = null
    @Volatile private var visionToken = ""
    private val camExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "xiaozhi-cam").apply { isDaemon = true }
    }
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

    /** Tombol mic: dalam mode otomatis jadi tombol mute on/off */
    fun micToggle() {
        if (sessionId == null) return
        micMuted = !micMuted
        uiStatus(if (micMuted) "Mic dimatikan" else "Mic aktif - bicara saja")
        xzlog(if (micMuted) "mic OFF" else "mic ON")
        if (!micMuted && !speaking) reopenListen()
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
                val wsJ = ota.optJSONObject("websocket")
                val tkn = wsJ?.optString("token") ?: ""
                xzlog("OTA ok. ws_url=" + (wsJ?.optString("url") ?: "-")
                    + " proto=" + (wsJ?.optInt("version", 1) ?: 1)
                    + " token=" + (if (tkn.length > 10) tkn.take(10) + "..(len " + tkn.length + ")" else tkn)
                    + " activation=" + (if (ota.optJSONObject("activation")?.has("code") == true) "ADA" else "tidak"))
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
                xzlog("connectWs: " + wsUrl + " token=" + token.take(14) + "..")
                connectWs(wsUrl, token, protoVer, g)
                return   // connectWs menangani selanjutnya lewat listener
            } catch (e: Exception) {
                Log.e("XiaozhiDevice", "loop error", e)
                xzlog("loop error: " + (e.message ?: "unknown"))
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
                        200 -> { xzlog("aktivasi OK (perangkat aktif)"); uiStatus("Perangkat aktif!"); return }
                        202 -> { if (tries % 4 == 1) xzlog("aktivasi menunggu kode (HTTP 202)"); uiStatus("Menunggu kode dimasukkan di console...") }
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
                // frame opus mentah dari mic -> server.
                // Saat AI bicara (speaking) atau mic di-mute, frame dibuang
                // supaya suara AI tidak ikut masuk lagi (echo/loop).
                if (!speaking && !micMuted) {
                    try { ws?.send(frame.toByteString()) } catch (_: Exception) { }
                }
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
                    // iklankan MCP supaya server kirim initialize + vision URL
                    put("features", JSONObject().put("mcp", true))
                    put("transport", "websocket")
                    put("audio_params", JSONObject()
                        .put("format", "opus")
                        .put("sample_rate", AudioEngine.MIC_RATE)
                        .put("channels", 1)
                        .put("frame_duration", AudioEngine.FRAME_MS))
                }
                webSocket.send(hello.toString())
                xzlog("hello dikirim (proto " + protoVer + "), tunggu balasan server")
                uiStatus("Terhubung, menunggu hello server...")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("XiaozhiDevice", "WS in: $text")
                if (text.length < 200) xzlog("WS in: " + text)
                else xzlog("WS in: " + text.substring(0, 200) + "..")
                handleText(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // opus mentah dari server -> speaker
                audio?.feed(bytes.toByteArray())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("XiaozhiDevice", "WS fail: ${t.message} resp=${response?.code}")
                xzlog("WS GAGAL: ${t.message} resp=${response?.code}")
                audio?.stopAll()
                ui.post { listener.onDisconnected(t.message ?: "connection failed") }
                scheduleReconnect(g)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i("XiaozhiDevice", "WS closed: $code $reason")
                xzlog("WS closed: $code $reason")
                audio?.stopAll()
                ui.post { listener.onDisconnected("closed: $reason") }
                scheduleReconnect(g)
            }
        })
    }

    private val webSocketRef: WebSocket?
        get() = ws

    /** Buka kembali sesi listen mode auto (dipanggil tiap AI selesai bicara) */
    private fun reopenListen() {
        val w = ws ?: return
        val sid = sessionId ?: return
        sendJson(w, JSONObject().apply {
            put("session_id", sid)
            put("type", "listen")
            put("state", "start")
            put("mode", "auto")
        })
        audio?.startMic()
        xzlog("listen auto dibuka ulang")
        uiStatus("Mic aktif - bicara saja")
    }

    private fun handleMcp(payload: JSONObject?, w: WebSocket?) {
        if (payload == null || w == null) return
        val rawId = if (payload.has("id")) payload.opt("id") else null
        // pertahankan tipe asli id (angka tetap angka) agar cocok di server
        val id: Any? = when (rawId) {
            is Number -> if (rawId.toDouble() == rawId.toLong().toDouble()) rawId.toLong() else rawId
            else -> rawId
        }
        xzlog("MCP method=${payload.optString("method")} id=$id type=${id?.javaClass?.simpleName}")
        // id bisa berupa angka besar (mis. 130); kirim apa adanya agar cocok
        fun rootSessionId(payload: JSONObject?, fallback: String?): String? {
            val s = payload?.optString("session_id") ?: return fallback
            return if (s.isNullOrEmpty()) fallback else s
        }
        fun reply(resultObj: JSONObject) {
            if (id == null) return
            val sid = rootSessionId(payload, sessionId)
            xzlog("MCP reply id=$id session=${sid ?: "-"} len=${resultObj.toString().length}")
            xzlog("MCP out: " + resultObj.toString().take(180))
            sendJson(w, JSONObject().apply {
                if (sid != null) put("session_id", sid)
                put("type", "mcp")
                put("payload", JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("result", resultObj)
                })
            })
        }
        fun replyError(msg: String) {
            if (id == null) return
            val sid = rootSessionId(payload, sessionId)
            sendJson(w, JSONObject().apply {
                if (sid != null) put("session_id", sid)
                put("type", "mcp")
                put("payload", JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("error", JSONObject().put("code", -32603).put("message", msg))
                })
            })
        }
        when (payload.optString("method")) {
            "initialize" -> {
                val vision = payload.optJSONObject("params")
                    ?.optJSONObject("capabilities")?.optJSONObject("vision")
                if (vision != null) {
                    // server kadang memberi URL http:// -> app memblokir cleartext.
                    // Ubah ke https:// (api.xiaozhi.me mendukung TLS).
                    visionUrl = vision.optString("url").let {
                        if (it.startsWith("http://")) "https://" + it.substring(7) else it
                    }
                    xzlog("vision url: ${visionUrl ?: "-"}")
                    visionToken = vision.optString("token")
                    xzlog("MCP init: vision url diterima")
                } else {
                    xzlog("MCP init: tanpa vision")
                }
                reply(JSONObject().apply {
                    put("protocolVersion", "2024-11-05")
                    put("capabilities", JSONObject().put("tools", JSONObject()))
                    put("serverInfo", JSONObject().put("name", "xiaozhi-android").put("version", "1.0.0"))
                })
            }
            "tools/list" -> {
                val schema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("question", JSONObject().apply {
                            put("type", "string")
                            put("description", "The question that you want to ask about the photo.")
                        })
                        put("camera", JSONObject().apply {
                            put("type", "string")
                            put("enum", org.json.JSONArray().put("front").put("back"))
                            put("description", "Optional: which camera to use. Default front.")
                        })
                    })
                    put("required", org.json.JSONArray().put("question"))
                }
                // tool standar firmware (get_device_status, set_volume) disertakan
                // agar daftar tool terlihat seperti perangkat xiaozhi asli
                val emptySchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                }
                val volSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().put("volume", JSONObject().apply {
                        put("type", "integer"); put("minimum", 0); put("maximum", 100)
                    }))
                    put("required", org.json.JSONArray().put("volume"))
                }
                val toolsArr = org.json.JSONArray()
                toolsArr.put(JSONObject().apply {
                    put("name", "self.get_device_status")
                    put("description", "Provides the real-time information of the device.")
                    put("inputSchema", emptySchema)
                })
                toolsArr.put(JSONObject().apply {
                    put("name", "self.audio_speaker.set_volume")
                    put("description", "Set the volume of the audio speaker (0-100).")
                    put("inputSchema", volSchema)
                })
                toolsArr.put(JSONObject().apply {
                    put("name", "self.camera.take_photo")
                    put("description",
                        "FRONT (selfie) camera, the default. For the REAR/back camera pass " +
                        "camera: back in arguments or use take_photo_back. " +
                        "Args: `question`: The question that you want to ask about the photo. " +
                        "Return: A JSON object that provides the photo information.")
                    put("inputSchema", schema)
                })
                toolsArr.put(JSONObject().apply {
                    put("name", "self.camera.take_photo_back")
                    put("description",
                        "REAR (back) camera. Use when the user asks to see something around or " +
                        "behind the phone, an object they hold up, or says kamera belakang / " +
                        "back camera. Otherwise prefer take_photo (front). " +
                        "Args: `question`: The question that you want to ask about the photo. " +
                        "Return: A JSON object that provides the photo information.")
                    put("inputSchema", schema)
                })
                // JANGAN kirim nextCursor kosong: server mengira masih ada
                // halaman berikutnya lalu mengulang tools/list tanpa henti
                reply(JSONObject().apply {
                    put("tools", toolsArr)
                })
                xzlog("MCP tools/list dijawab: 4 tools, tanpa nextCursor")
            }
            "tools/call" -> {
                val name = payload.optJSONObject("params")?.optString("name") ?: ""
                val question = payload.optJSONObject("params")
                    ?.optJSONObject("arguments")?.optString("question") ?: ""
                if (name == "self.get_device_status") {
                    reply(JSONObject().apply {
                        put("content", org.json.JSONArray().put(JSONObject()
                            .put("type", "text").put("text",
                                "{\"audio_speaker\":{\"volume\":70},\"screen\":{},\"network\":{\"type\":\"wifi\"}}")))
                        put("isError", false)
                    })
                    return
                }
                if (name == "self.audio_speaker.set_volume") {
                    reply(JSONObject().apply {
                        put("content", org.json.JSONArray().put(JSONObject()
                            .put("type", "text").put("text", "true")))
                        put("isError", false)
                    })
                    return
                }
                val camBack = name.endsWith("back") ||
                    (payload.optJSONObject("params")?.optJSONObject("arguments")
                        ?.optString("camera") == "back")
                if (name != "self.camera.take_photo" && name != "self.camera.take_photo_back") {
                    replyError("Unknown tool: $name"); return
                }
                xzlog("AI minta foto (${if (camBack) "belakang" else "depan"}): $question")
                uiStatus(if (camBack) "Foto kamera belakang..." else "Mengambil foto untuk AI...")
                camExec.execute {
                    val answer = try { explainPhoto(question, useBack = camBack) }
                        catch (e: Exception) {
                            xzlog("kamera error: ${e.javaClass.simpleName}: ${e.message}")
                            "{\"success\":false,\"message\":\"camera error: ${e.javaClass.simpleName}\"}"
                        }
                    reply(JSONObject().apply {
                        put("content", org.json.JSONArray().put(JSONObject()
                            .put("type", "text").put("text", answer)))
                        put("isError", false)
                    })
                    uiStatus("Mic aktif - bicara saja")
                }
            }
            else -> if (id != null) replyError("Method not found: ${payload.optString("method")}")
        }
    }

    /** Ambil 1 foto JPEG (kamera belakang) maksimal ~800px sisi terpanjang */
    private fun captureJpeg(maxSide: Int = 800, useBack: Boolean = false): ByteArray? {
        val cm = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        // default kamera depan (selfie); useBack=true -> kamera belakang
        val wantFacing = if (useBack) CameraCharacteristics.LENS_FACING_BACK
                         else CameraCharacteristics.LENS_FACING_FRONT
        val frontId = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it)
                .get(CameraCharacteristics.LENS_FACING) == wantFacing
        }
        val camId = frontId ?: cm.cameraIdList.firstOrNull() ?: return null
        val jpegRot = if (frontId != null) (if (useBack) 90 else 270) else 90
        val chars = cm.getCameraCharacteristics(camId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val size = map.getOutputSizes(ImageFormat.JPEG).firstOrNull { it.width <= 1600 }
            ?: map.getOutputSizes(ImageFormat.JPEG).first()

        val ht = HandlerThread("xz-shot").also { it.start() }
        val h = Handler(ht.looper)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
        var jpeg: ByteArray? = null
        var err: String? = null
        val latch = CountDownLatch(1)
        cm.openCamera(camId, object : CameraDevice.StateCallback() {
            override fun onOpened(cam: CameraDevice) {
                try {
                    val outputs = java.util.Collections.singletonList(reader.surface)
                    cam.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(sess: CameraCaptureSession) {
                            try {
                                val reqB = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                    addTarget(reader.surface)
                                    set(CaptureRequest.JPEG_ORIENTATION, jpegRot)
                                }
                                reader.setOnImageAvailableListener({ r ->
                                    val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                                    val bb = img.planes[0].buffer
                                    val b = ByteArray(bb.remaining()); bb.get(b)
                                    img.close(); jpeg = b
                                    try { cam.close() } catch (_: Exception) { }
                                    latch.countDown()
                                }, h)
                                sess.capture(reqB.build(), null, h)
                            } catch (e: Exception) { err = e.message; try { cam.close() } catch (_: Exception) { }; latch.countDown() }
                        }
                        override fun onConfigureFailed(sess: CameraCaptureSession) {
                            err = "configure failed"
                            try { cam.close() } catch (_: Exception) { }
                            latch.countDown()
                        }
                    }, h)
                } catch (e: Exception) { err = e.message; latch.countDown() }
            }
            override fun onDisconnected(cam: CameraDevice) { err = "disconnected"; latch.countDown() }
            override fun onError(cam: CameraDevice, code: Int) { err = "camera error $code"; latch.countDown() }
        }, h)
        latch.await(10, TimeUnit.SECONDS)
        ht.quitSafely()
        reader.close()
        if (jpeg == null) { xzlog("capture gagal: ${err ?: "?"}"); return null }
        // perkecil supaya upload ringan
        return try {
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg!!.size)
            var w2 = bmp.width; var h2 = bmp.height
            if (maxOf(w2, h2) > maxSide) {
                val sc = maxSide.toFloat() / maxOf(w2, h2)
                w2 = (w2 * sc).toInt().coerceAtLeast(1); h2 = (h2 * sc).toInt().coerceAtLeast(1)
            }
            val small = Bitmap.createScaledBitmap(bmp, w2, h2, true)
            val out = java.io.ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, 70, out)
            out.toByteArray()
        } catch (_: Exception) { jpeg }
    }

    /** Upload foto + pertanyaan ke vision URL, kembalikan jawaban JSON server */
    private fun explainPhoto(question: String, useBack: Boolean = false): String {
        val url = visionUrl ?: return "{\"success\":false,\"message\":\"vision url not set\"}"
        val jpeg = captureJpeg(useBack = useBack) ?: return "{\"success\":false,\"message\":\"failed to capture photo (camera not found)\"}"
        xzlog("foto siap ${jpeg.size} bytes, upload ke vision")
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("question", question)
            .addFormDataPart("file", "camera.jpg",
                okhttp3.RequestBody.create("image/jpeg".toMediaType(), jpeg))
            .build()
        val reqB = Request.Builder().url(url)
            .header("Device-Id", mac)
            .header("Client-Id", uuid)
        if (visionToken.isNotEmpty()) reqB.header("Authorization", "Bearer " + visionToken)
        val req = reqB.post(body).build()
        http.newCall(req).execute().use { resp ->
            val txt = resp.body?.string() ?: ""
            xzlog("vision resp ${resp.code}: ${txt.take(120)}")
            return txt
        }
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
                        if (autoMode) {
                            // percakapan berkelanjutan: mic selalu mengirim,
                            // server yang mendeteksi suara kita (mode "auto")
                            val sock = ws
                            if (sock != null) sendJson(sock, JSONObject().apply {
                                put("session_id", sessionId)
                                put("type", "listen")
                                put("state", "start")
                                put("mode", "auto")
                            })
                            audio?.startMic()
                            xzlog("auto-listen aktif: mic streaming, mode auto")
                            listener.onStatus("Siap! Langsung bicara saja")
                        } else {
                            listener.onStatus("Siap! Tahan tombol tengah untuk bicara")
                        }
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
                        // percakapan berkelanjutan: buka lagi sesi listen
                        // supaya user tidak perlu menekan tombol lagi
                        if (autoMode && !micMuted) reopenListen()
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
                "mcp" -> {
                    root.optString("session_id").takeIf { it.isNotEmpty() }?.let { sessionId = it }
                    handleMcp(root.optJSONObject("payload"), webSocketRef)
                }
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
