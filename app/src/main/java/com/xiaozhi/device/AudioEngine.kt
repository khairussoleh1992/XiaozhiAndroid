package com.xiaozhi.device

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
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

    @SuppressLint("MissingPermission")
    fun startMic() {
        if (micRunning) return
        micRunning = true
        micThread = thread(name = "xiaozhi-mic") {
            val enc = OpusEncoder(MIC_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP)
            try { enc.bitrate = 32000 } catch (_: Exception) { }
            encoder = enc
            val minBuf = AudioRecord.getMinBufferSize(
                MIC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = maxOf(minBuf * 2, FRAME_SAMPLES * 4)
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MIC_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufSize)
            record = rec
            rec.startRecording()
            val pcm = ShortArray(FRAME_SAMPLES)
            val out = ByteArray(1024)
            while (micRunning) {
                var got = 0
                while (got < FRAME_SAMPLES && micRunning) {
                    val n = rec.read(pcm, got, FRAME_SAMPLES - got)
                    if (n <= 0) break
                    got += n
                }
                if (got < FRAME_SAMPLES) break
                try {
                    onMicLevel(rms(pcm, FRAME_SAMPLES))
                    val len = enc.encode(pcm, 0, FRAME_SAMPLES, out, 0, out.size)
                    if (len > 0) onMicFrame(out.copyOf(len))
                } catch (_: Exception) { }
            }
            try { rec.stop() } catch (_: Exception) { }
            rec.release()
            record = null
            encoder = null
            micThread = null
        }
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
}
