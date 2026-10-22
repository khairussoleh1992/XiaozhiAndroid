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
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        inst = null
        return super.onUnbind(intent)
    }

    fun tapXY(x: Float, y: Float, done: (Boolean) -> Unit) {
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 60))
            .build()
        dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gesture: GestureDescription?) { done(true) }
            override fun onCancelled(gesture: GestureDescription?) { done(false) }
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

    // Fungsi pintar: Pergi ke Home -> Scan layar -> Jika tidak ada, otomatis swipe/scroll -> Klik jika ketemu
    fun openAppSmart(targetName: String, onResult: (Int, String) -> Unit) {
        performGlobalAction(GLOBAL_ACTION_HOME)
        
        android.os.Handler(mainLooper).postDelayed({
            tryFindingApp(targetName, 0, onResult)
        }, 800)
    }

    private fun tryFindingApp(targetName: String, attempt: Int, onResult: (Int, String) -> Unit) {
        val root = rootInActiveWindow
        if (root == null) {
            onResult(0, "Layar tidak terdeteksi")
            return
        }

        val matches = mutableListOf<AccessibilityNodeInfo>()
        findAllNodesByText(root, targetName, matches)

        val uniqueNames = matches.mapNotNull { it.text?.toString() ?: it.contentDescription?.toString() }
            .distinct()

        if (uniqueNames.size == 1 || matches.size == 1) {
            // Kecocokan pasti -> Langsung klik dan buka tanpa konfirmasi
            val targetNode = matches[0]
            if (targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                onResult(1, uniqueNames.firstOrNull() ?: targetName)
            } else {
                val rect = Rect()
                targetNode.getBoundsInScreen(rect)
                tapXY(rect.centerX().toFloat(), rect.centerY().toFloat()) { success ->
                    if (success) onResult(1, targetName) else onResult(0, "Gagal klik")
                }
            }
        } else if (uniqueNames.size > 1) {
            // Ragu / ada banyak pilihan -> Baru minta konfirmasi
            onResult(2, uniqueNames.joinToString(", "))
        } else {
            // Tidak ketemu di halaman ini, coba swipe/scroll ke halaman berikutnya (maksimal 2 kali percobaan)
            if (attempt < 2) {
                val metrics = resources.displayMetrics
                val startX = metrics.widthPixels * 0.8f
                val endX = metrics.widthPixels * 0.2f
                val centerY = metrics.heightPixels * 0.5f

                swipeXY(startX, centerY, endX, centerY, 300) { success ->
                    if (success) {
                        android.os.Handler(mainLooper).postDelayed({
                            tryFindingApp(targetName, attempt + 1, onResult)
                        }, 600)
                    } else {
                        onResult(0, targetName)
                    }
                }
            } else {
                onResult(0, targetName)
            }
        }
    }

    private fun findAllNodesByText(node: AccessibilityNodeInfo, target: String, results: MutableList<AccessibilityNodeInfo>) {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val targetLower = target.lowercase()

        if ((text.contains(targetLower) || desc.contains(targetLower)) && node.isClickable) {
            results.add(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findAllNodesByText(child, target, results)
        }
    }
}
