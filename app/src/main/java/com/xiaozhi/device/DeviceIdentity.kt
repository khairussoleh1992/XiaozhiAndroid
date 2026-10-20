package com.xiaozhi.device

import android.content.Context
import java.security.SecureRandom
import java.util.UUID

/**
 * Identitas perangkat: pengganti MAC address + UUID board milik ESP32.
 * Dibuat sekali lalu disimpan, supaya server xiaozhi.me mengenali
 * HP ini sebagai perangkat yang sama setiap kali connect.
 */
object DeviceIdentity {

    fun mac(context: Context): String {
        val prefs = context.getSharedPreferences("xiaozhi_device", Context.MODE_PRIVATE)
        var mac = prefs.getString("mac", null)
        if (mac == null) {
            val b = ByteArray(6)
            SecureRandom().nextBytes(b)
            mac = b.joinToString(":") { String.format("%02X", it) }
            prefs.edit().putString("mac", mac).apply()
        }
        return mac
    }

    fun uuid(context: Context): String {
        val prefs = context.getSharedPreferences("xiaozhi_device", Context.MODE_PRIVATE)
        var uuid = prefs.getString("uuid", null)
        if (uuid == null) {
            uuid = UUID.randomUUID().toString()
            prefs.edit().putString("uuid", uuid).apply()
        }
        return uuid
    }
}
