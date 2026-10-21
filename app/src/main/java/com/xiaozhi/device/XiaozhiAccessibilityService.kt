package com.xiaozhi.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log // <-- Ini yang tadi kurang

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

    // Fungsi pintar: Pergi ke Home -> Baca layar -> Cari ikon aplikasi -> Klik otomatis
    fun openAppByNameOnScreen(targetName: String, done: (Boolean) -> Unit) {
        Log.d("XZAccess", "Mencoba membuka aplikasi: $targetName")
        performGlobalAction(GLOBAL_ACTION_HOME)
        
        android.os.Handler(mainLooper).postDelayed({
            val root = rootInActiveWindow
            if (root == null) {
                Log.e("XZAccess", "Gagal: rootInActiveWindow bernilai NULL (Aksesibilitas belum aktif)")
                done(false)
                return@postDelayed
            }

            val allText = dumpScreenText()
            Log.d("XZAccess", "Teks terbaca di layar:\n$allText")

            val targetNode = findNodeByText(root, targetName)
            if (targetNode != null) {
                Log.d("XZAccess", "Aplikasi $targetName ditemukan! Mencoba klik...")
                if (targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    done(true)
                } else {
                    val rect = Rect()
                    targetNode.getBoundsInScreen(rect)
                    Log.d("XZAccess", "Klik node gagal, mencoba koordinat: ${rect.centerX()}, ${rect.centerY()}")
                    tapXY(rect.centerX().toFloat(), rect.centerY().toFloat(), done)
                }
            } else {
                Log.e("XZAccess", "Aplikasi $targetName tidak ditemukan di layar.")
                done(false)
            }
        }, 1000)
    }

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
}
