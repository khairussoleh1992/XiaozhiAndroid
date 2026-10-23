package com.xiaozhi.device

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import kotlin.math.sqrt
import org.concentus.OpusApplication
import org.concentus.OpusDecoder
import org.concentus.OpusEncoder
import kotlin.concurrent.thread

/**
 * Mesin audio: mic (16 kHz mono PCM -> Opus 60 ms) dan speaker
 * (Opus dari server -> PCM -> AudioTrack).
 *
 * Level RMS mic & TTS dipakai untuk waveform dan lip-sync karakter
 * di tampilan panggilan video.
 */
class AudioEngine(
    private val onMicFrame: (ByteArray) -> Unit,
    private val onMicLevel: (Float) -> Unit = {},
    private val onTtsLevel: (Float) -> Unit = {}
) {

    companion object {
        const val MIC_RATE = 16000
        const val FRAME_MS = 60
        const val FRAME_SAMPLES = MIC_RATE * FRAME_MS / 1000   // 960
    }

    @Volatile var serverRate: Int = 24000
        set(value) { field = value }

    private var encoder: OpusEncoder? = null
    private var decoder: OpusDecoder? = null
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    @Volatile private var micRunning = false
    private var micThread: Thread? = null
    @Volatile private var lastAudioAt = 0L

    @SuppressLint("MissingPermission")
    fun startMic() {
        if (micRunning) return
        micRunning = true
        micThread = thread(name = "xiaozhi-mic") {
            val enc = OpusEncoder(MIC_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP)
            try { enc.bitrate = 32000 } catch (_: Exception) { }
            encoder = enc
            // Loop luar: kalau AudioRecord mati diam-diam (audio focus hilang,
            // headset dicabut, dsb.) mic dibuat ulang otomatis, bukan berhenti
            // selamanya. Ini salah satu penyebab app "tidak merespon" dulu.
            while (micRunning) {
                val rec = try { createRecorder() } catch (_: Exception) { null }
                if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                    try { rec?.release() } catch (_: Exception) { }
                    record = null
                    Thread.sleep(1000)
                    continue
                }
                record = rec
                try { rec.startRecording() } catch (_: Exception) {
                    try { rec.release() } catch (_: Exception) { }
                    record = null
                    Thread.sleep(1000)
                    continue
                }
                val pcm = ShortArray(FRAME_SAMPLES)
                val out = ByteArray(1024)
                var badFrames = 0
                while (micRunning) {
                    var got = 0
                    var emptyReads = 0
                    while (got < FRAME_SAMPLES && micRunning) {
                        val n = rec.read(pcm, got, FRAME_SAMPLES - got)
                        if (n > 0) { got += n; emptyReads = 0 }
                        else {
                            emptyReads++
                            if (emptyReads > 30) break
                            Thread.sleep(10)
                        }
                    }
                    if (!micRunning) break
                    if (got < FRAME_SAMPLES) {
                        // read mentok: frame ini diulang; kalau terus kosong
                        // 3x berturut-turut, AudioRecord dianggap mati.
                        badFrames++
                        if (badFrames >= 3) break
                        continue
                    }
                    badFrames = 0
                    try {
                        onMicLevel(rms(pcm, FRAME_SAMPLES))
                        val len = enc.encode(pcm, 0, FRAME_SAMPLES, out, 0, out.size)
                        if (len > 0) onMicFrame(out.copyOf(len))
                    } catch (_: Exception) { }
                }
                try { rec.stop() } catch (_: Exception) { }
                rec.release()
                record = null
                if (micRunning) Thread.sleep(700)
            }
            encoder = null
            micThread = null
        }
    }

    private fun createRecorder(): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(
            MIC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf * 2, FRAME_SAMPLES * 4)
        return AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MIC_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, bufSize)
    }

    fun stopMic() {
        micRunning = false
    }

    /** Dipanggil saat pesan {"type":"tts","state":"start"} */
    fun startPlayback() {
        if (track != null) return
        val dec = OpusDecoder(serverRate, 1)
        decoder = dec
        val minBuf = AudioTrack.getMinBufferSize(
            serverRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val tr = AudioTrack(
            AudioManager.STREAM_MUSIC, serverRate,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 2, serverRate), AudioTrack.MODE_STREAM)
        tr.play()
        lastAudioAt = SystemClock.elapsedRealtime()
        track = tr
    }

    /** Frame Opus mentah dari server -> speaker */
    fun feed(bytes: ByteArray) {
        val tr = track ?: return
        val dec = decoder ?: return
        try {
            val frameSize = serverRate * FRAME_MS / 1000
            val out = ShortArray(frameSize * 2)
            val n = dec.decode(bytes, 0, bytes.size, out, 0, frameSize, false)
            if (n > 0) {
                lastAudioAt = SystemClock.elapsedRealtime()
                onTtsLevel(rms(out, n))
                tr.write(out, 0, n)
            }
        } catch (_: Exception) { }
    }

    /** Dipanggil saat {"type":"tts","state":"stop"} */
    fun stopPlayback() {
        try {
            onTtsLevel(0f)
            track?.pause()
            track?.flush()
            track?.release()
        } catch (_: Exception) { }
        track = null
        decoder = null
    }

    fun stopAll() {
        stopMic()
        stopPlayback()
    }

    /** Sudah berapa ms sejak frame TTS terakhir sampai ke speaker.
     *  Dipakai watchdog XiaozhiDevice untuk mendeteksi TTS yang macet. */
    fun msSinceLastAudio(): Long {
        if (track == null) return 999999L
        return SystemClock.elapsedRealtime() - lastAudioAt
    }

    /** Level RMS 0..1 dari buffer PCM 16-bit */
    private fun rms(buf: ShortArray, n: Int): Float {
        if (n <= 0) return 0f
        var sum = 0.0
        for (i in 0 until n) {
            val v = buf[i].toDouble()
            sum += v * v
        }
        val r = sqrt(sum / n) / 32768.0
        return (r * 3.2).coerceIn(0.0, 1.0).toFloat()
    }
