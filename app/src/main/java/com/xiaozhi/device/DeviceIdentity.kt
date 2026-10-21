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
            mac = b.joinToString(":") { String.format("%02x", it) }
            prefs.edit().putString("mac", mac).apply()
        }
        // Server xiaozhi menutup websocket tanpa balasan kalau MAC huruf BESAR.
        // Paksa huruf kecil, termasuk untuk MAC lama yang sudah tersimpan.
        val lower = mac.lowercase()
        if (lower != mac) prefs.edit().putString("mac", lower).apply()
        return lower
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
