package com.xiaozhi.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service: jembatan kontrol HP untuk AI.
 * Aktifkan manual: Settings > Accessibility > Xiaozhi Control.
 */
class XiaozhiAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var inst: XiaozhiAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        inst = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { }
    override fun onInterrupt() { }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        inst = null
        return super.onUnbind(intent)
    }

    fun tapXY(x: Float, y: Float, done: (Boolean) -> Unit) {
        val p = Path()
        p.moveTo(x, y)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 60))
            .build()
        dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gesture: GestureDescription?) { done(true) }
            override fun onCancelled(gesture: GestureDescription?) { done(false) }
        }, null)
    }

    fun swipeXY(x1: Float, y1: Float, x2: Float, y2: Float, durMs: Long, done: (Boolean) -> Unit) {
        val p = Path()
        p.moveTo(x1, y1)
        p.lineTo(x2, y2)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, durMs))
            .build()
        dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gesture: GestureDescription?) { done(true) }
            override fun onCancelled(gesture: GestureDescription?) { done(false) }
        }, null)
    }
    // Fungsi pintar: Ke Home -> Baca Layar -> Cari Nama Aplikasi -> Klik Otomatis
    fun openAppByNameOnScreen(targetName: String, done: (Boolean) -> Unit) {
        // 1. Pergi ke halaman utama dulu
        performGlobalAction(GLOBAL_ACTION_HOME)

        // 2. Beri jeda 800ms agar halaman utama terbuka sempurna
        android.os.Handler(mainLooper).postDelayed({
            val root = rootInActiveWindow
            if (root == null) {
                done(false)
                return@postDelayed
            }

            // Cari node/ikon aplikasi yang cocok dengan nama yang diminta
            val targetNode = findNodeByText(root, targetName)
            if (targetNode != null) {
                // Coba klik langsung node-nya
                if (targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    done(true)
                } else {
                    // Jika gagal klik node, klik berdasarkan koordinat tengah ikon tersebut
                    val rect = Rect()
                    targetNode.getBoundsInScreen(rect)
                    tapXY(rect.centerX().toFloat(), rect.centerY().toFloat(), done)
                }
            } else {
                done(false)
            }
        }, 800)
    }

    // Helper untuk rekursif mencari teks/deskripsi aplikasi di layar
    private fun findNodeByText(node: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val targetLower = target.lowercase()

        if (text.contains(targetLower) || desc.contains(targetLower)) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByText(child, target)
            if (result != null) return result
        }
        return null
    }
    
    fun globalAction(action: Int): Boolean = performGlobalAction(action)

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
}
