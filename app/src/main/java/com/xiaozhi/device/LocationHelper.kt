package com.xiaozhi.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Lokasi GPS untuk AI: ambil posisi terakhir yang diketahui (cepat), atau
 * minta satu fix baru kalau data lama sudah basi (max ~15 detik).
 * Hasil JSON: latitude, longitude, accuracy_m, provider, age_s, address.
 */
object LocationHelper {

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun getLocation(ctx: Context, timeoutMs: Long = 12000): String {
        if (!hasPermission(ctx)) {
            return "error: location permission not granted. Ask the user to allow Location access for the app, then retry."
        }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            try {
                val l = lm.getLastKnownLocation(p)
                if (l != null && (best == null || l.time > best.time)) best = l
            } catch (_: SecurityException) {
                return "error: location permission not granted"
            } catch (_: Exception) { }
        }
        val fresh = best != null && System.currentTimeMillis() - best.time < 120000

        var received: Location? = best
        if (!fresh) {
            val latch = CountDownLatch(1)
            val listener = object : LocationListener {
                override fun onLocationChanged(l: Location) {
                    received = l
                    latch.countDown()
                }
            }
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener, Looper.getMainLooper())
            } catch (_: Exception) { }
            try {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f, listener, Looper.getMainLooper())
            } catch (_: Exception) { }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            try { lm.removeUpdates(listener) } catch (_: Exception) { }
        }

        val loc = received ?: best
        if (loc == null) {
            return "error: no location available. Ask the user to enable Location (GPS) and try again."
        }
        val lat = loc.latitude
        val lon = loc.longitude
        var addr = ""
        try {
            if (Geocoder.isPresent()) {
                val list = Geocoder(ctx, Locale.getDefault()).getFromLocation(lat, lon, 1)
                val a = list?.firstOrNull()
                if (a != null) {
                    val parts = listOf(a.getAddressLine(0), a.locality, a.adminArea, a.countryName)
                        .filter { !it.isNullOrEmpty() }
                    addr = parts.joinToString(", ")
                }
            }
        } catch (_: Exception) { }

        return JSONObject().apply {
            put("latitude", (lat * 1e6).toLong() / 1e6)
            put("longitude", (lon * 1e6).toLong() / 1e6)
            put("accuracy_m", loc.accuracy.toDouble().toLong())
            put("provider", loc.provider ?: "-")
            put("age_s", (System.currentTimeMillis() - loc.time) / 1000)
            if (addr.isNotEmpty()) put("address", addr)
        }.toString()
    }
