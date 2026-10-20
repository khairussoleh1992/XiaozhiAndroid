```
package com.xiaozhi.device

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), XiaozhiDevice.Listener {

    private var device: XiaozhiDevice? = null
    private var connected = false
    private var talking = false
    private var chatLog = StringBuilder()

    private lateinit var tvStatus: TextView
    private lateinit var tvDeviceId: TextView
    private lateinit var tvCode: TextView
    private lateinit var tvActivationTitle: TextView
    private lateinit var tvChat: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnTalk: Button

    private val permCode = 77

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
tvStatus = findViewById(R.id.tvStatus)
        tvDeviceId = findViewById(R.id.tvDeviceId)
        tvCode = findViewById(R.id.tvCode)
        tvActivationTitle = findViewById(R.id.tvActivationTitle)
        tvChat = findViewById(R.id.tvChat)
        btnConnect = findViewById(R.id.btnConnect)
        btnTalk = findViewById(R.id.btnTalk)

        device = XiaozhiDevice(this, this)
        tvDeviceId.text = device?.deviceInfo() ?: ""

        btnConnect.setOnClickListener {
            if (connected) {
                device?.stop()
            } else {
                if (!hasMicPermission()) {
                    ActivityCompat.requestPermissions(
                        this, arrayOf(Manifest.permission.RECORD_AUDIO), permCode)
                    return@setOnClickListener
                }
                device?.start()
            }
        }

        // Push to talk: tahan = bicara, lepas = kirim
        btnTalk.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (connected && !talking) {
                        talking = true
                        device?.startTalking()
                        v.performClick()
                        true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (talking) {
                        talking = false
                        device?.stopTalking()
                        true
                    } else false
                }
                else -> false
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
            Toast.makeText(this, "Izin mic wajib untuk bicara", Toast.LENGTH_LONG).show()
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // ---------------- callback dari XiaozhiDevice ----------------

    override fun onStatus(text: String) {
        tvStatus.text = text
    }

    override fun onActivationCode(code: String, message: String) {
        tvActivationTitle.visibility = TextView.VISIBLE
        tvCode.visibility = TextView.VISIBLE
        tvCode.text = code
        if (message.isNotEmpty()) tvStatus.text = message
    }

    override fun onActivationDone() {
        tvActivationTitle.visibility = TextView.GONE
        tvCode.visibility = TextView.GONE
    }

    override fun onConnected() {
        connected = true
        btnConnect.text = "Disconnect"
        btnTalk.isEnabled = true
        Toast.makeText(this, "Terhubung ke Xiaozhi", Toast.LENGTH_SHORT).show()
    }

    override fun onDisconnected(reason: String) {
        connected = false
        talking = false
        btnConnect.text = "Connect"
        btnTalk.isEnabled = false
        tvStatus.text = "Terputus: $reason"
    }

    override fun onChat(role: String, text: String) {
        chatLog.append("$role: $text\n")
        tvChat.text = chatLog.toString()
    }

    override fun onSpeaking(speaking: Boolean) {
        btnTalk.text = if (speaking) "AI bicara... (tahan untuk potong)" else "Tahan untuk bicara"
    }

    override fun onDestroy() {
        device?.stop()
        super.onDestroy()
    }
}
```
