package com.xiaozhi.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log

class XiaozhiAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var inst: XiaozhiAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        inst = this
        Log.d("XZAccess", "Accessibility Service Terhubung")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        inst = null
        Log.d("XZAccess", "Accessibility Service Terputus")
        return super.onUnbind(intent)
    }

    // Klik presisi menggunakan koordinat (paling ampuh untuk ikon aplikasi)
    fun tapXY(x: Float, y: Float, done: (Boolean) -> Unit) {
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 60))
            .build()
        dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gesture: GestureDescription?) { 
                Log.d("XZAccess", "Berhasil klik di koordinat ($x, $y)")
                done(true) 
            }
            override fun onCancelled(gesture: GestureDescription?) { 
                Log.d("XZAccess", "Gagal klik / dibatalkan")
                done(false) 
            }
        }, null)
    }

    // Geser layar (Swipe / Scroll)
    fun swipeXY(x1: Float, y1: Float, x2: Float, y2: Float, durMs: Long, done: (Boolean) -> Unit) {
        val p = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, durMs))
            .build()
        dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gesture: GestureDescription?) { done(true) }
            override fun onCancelled(gesture: GestureDescription?) { done(false) }
        }, null)
    }

    fun globalAction(action: Int): Boolean = performGlobalAction(action)

    // Membaca seluruh teks di layar untuk analisis AI
    fun dumpScreenText(maxLen: Int = 3000): String {
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || sb.length > maxLen) return
            val txt = n.text?.toString()?.trim()
            val dsc = n.contentDescription?.toString()?.trim()
            if (!txt.isNullOrEmpty()) {
                val r = Rect()
                n.getBoundsInScreen(r)
                sb.append(txt.replace('\n', ' ')).append(" [")
                    .append(r.left).append(',').append(r.top).append("]\n")
            } else if (!dsc.isNullOrEmpty() && dsc.length < 50) {
                sb.append('(').append(dsc).append(")\n")
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(rootInActiveWindow)
        return sb.toString()
    }

    // --- FUNGSI PINTAR: Buka Aplikasi dengan Inisiatif Swipe & Klik Akurat ---
    fun openAppSmart(targetName: String, attempt: Int = 0, onResult: (Int, String) -> Unit) {
        Log.d("XZAccess", "Mencari aplikasi: $targetName (Percobaan ke-${attempt + 1})")
        
        if (attempt == 0) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }

        android.os.Handler(mainLooper).postDelayed({
            val root = rootInActiveWindow
            if (root == null) {
                onResult(0, "Layar tidak terdeteksi")
                return@postDelayed
            }

            // Cari node aplikasi berdasarkan teks/deskripsi
            val targetNode = findNodeByTextRecursive(root, targetName)

            if (targetNode != null) {
                // Ketemu! Ambil koordinat tengahnya lalu klik langsung
                val rect = Rect()
                targetNode.getBoundsInScreen(rect)
                val cx = rect.centerX().toFloat()
                val cy = rect.centerY().toFloat()

                Log.d("XZAccess", "Aplikasi $targetName ditemukan di koordinat ($cx, $cy). Mengeksekusi klik...")
                
                // Coba klik node dulu, jika gagal langsung tembak pakai koordinat tapXY
                if (targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    onResult(1, targetName)
                } else {
                    tapXY(cx, cy) { success ->
                        if (success) onResult(1, targetName) else onResult(0, "Gagal klik")
                    }
                }
            } else if (attempt < 2) {
                // Tidak ketemu di halaman ini? Geser layar (swipe ke halaman berikutnya) lalu cari lagi!
                Log.d("XZAccess", "Tidak ketemu di halaman ini, melakukan swipe untuk mencari...")
                swipeXY(900f, 1000f, 100f, 1000f, 300) { _ ->
                    android.os.Handler(mainLooper).postDelayed({
                        openAppSmart(targetName, attempt + 1, onResult)
                    }, 600)
                }
            } else {
                // Sudah dicari beberapa halaman tetap tidak ketemu
                onResult(0, targetName)
            }
        }, 800)
    }

    private fun findNodeByTextRecursive(node: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val targetLower = target.lowercase()

        if (text.contains(targetLower) || desc.contains(targetLower)) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByTextRecursive(child, target)
            if (result != null) return result
        }
        return null
    }
}
