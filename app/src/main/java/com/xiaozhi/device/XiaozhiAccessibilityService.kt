package com.xiaozhi.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log

/**
 * Kontrol HP untuk AI: tap, swipe, baca layar, buka aplikasi (pintar),
 * serta otomasi WhatsApp (baca chat / kirim balasan) dan Outlook (baca /
 * balas email).
 */
class XiaozhiAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var inst: XiaozhiAccessibilityService? = null
            private set
    }

    private val h = Handler(Looper.getMainLooper())

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

    // ---------------- dasar ----------------

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

    // ---------------- util ----------------

    private fun later(ms: Long, r: () -> Unit) { h.postDelayed(r, ms) }

    private fun norm(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun label(n: AccessibilityNodeInfo): String =
        n.text?.toString()?.trim() ?: n.contentDescription?.toString()?.trim() ?: ""

    /** Jelajahi seluruh node layar (BFS) */
    private fun walk(root: AccessibilityNodeInfo?, fn: (AccessibilityNodeInfo) -> Unit) {
        if (root == null) return
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            fn(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
    }

    private fun findNode(pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        var found: AccessibilityNodeInfo? = null
        walk(root) { n -> if (found == null && pred(n)) found = n }
        return found
    }

    private fun clickNode(n: AccessibilityNodeInfo, done: (Boolean) -> Unit) {
        if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { done(true); return }
        val r = Rect()
        n.getBoundsInScreen(r)
        tapXY(r.exactCenterX(), r.exactCenterY()) { ok -> done(ok) }
    }

    /** Tulis teks ke node editable: SET_TEXT, atau fallback clipboard+paste */
    private fun typeText(n: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("xz", text))
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return n.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun launchApp(pkg: String): Boolean = try {
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) false else {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            true
        }
    } catch (_: Exception) { false }

    // ---------------- buka aplikasi ----------------

    /**
     * Cara pintar buka aplikasi:
     * 1. Cari label di PackageManager (paling cepat & pasti, tidak muncul
     *    layar pilihan / uninstall sama sekali) -> langsung buka.
     * 2. Kalau tidak ketemu, scan layar sendiri: home, swipe halaman,
     *    buka app drawer, scroll ke bawah, sampai 6 halaman.
     */
    fun openAppSmart(targetName: String, onResult: (Int, String) -> Unit) {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = try { pm.queryIntentActivities(launcher, 0) } catch (_: Exception) { emptyList() }
        val t = norm(targetName)
        val tWords = targetName.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }
        val cands = apps.mapNotNull { ri ->
            val lbl = try { ri.loadLabel(pm)?.toString() } catch (_: Exception) { null }
                ?: ri.activityInfo.packageName
            val nl = norm(lbl)
            val pn = ri.activityInfo.packageName.lowercase()
            val score = when {
                nl == t -> 100
                pn == t -> 95
                tWords.contains(nl) -> 88   // "google maps" -> label "Maps"
                nl.startsWith(t) -> 80
                pn.contains(t) -> 60
                nl.contains(t) -> 60
                (t.length >= 3 && t.contains(nl)) -> 55
                else -> 0
            }
            if (score > 0) Triple(score, lbl, ri) else null
        }.sortedWith(compareByDescending<Triple<Int, String, Any>> { it.first }.thenBy { it.second.length })

        val best = cands.firstOrNull()
        if (best != null) {
            val ri = best.third as android.content.pm.ResolveInfo
            try {
                startActivity(Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                onResult(1, best.second)
                return
            } catch (_: Exception) { }
        }
        // Fallback: scan layar (home -> halaman -> app drawer)
        performGlobalAction(GLOBAL_ACTION_HOME)
        later(700) { tryFindingApp(targetName, 0, onResult) }
    }

    private fun tryFindingApp(targetName: String, phase: Int, onResult: (Int, String) -> Unit) {
        val root = rootInActiveWindow
        if (root == null) { onResult(0, "Layar tidak terdeteksi"); return }

        val t = norm(targetName)
        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = 0
        var bestArea = 0
        walk(root) { n ->
            val l = label(n)
            if (l.isEmpty() || l.length > 40) return@walk
            val nl = norm(l)
            if (nl.isEmpty()) return@walk
            var sc = 0
            if (nl == t) sc = 100
            else if (nl.startsWith(t)) sc = 70
            else if (nl.contains(t)) sc = 50
            else if (t.length >= 3 && t.contains(nl)) sc = 45
            if (sc > 0) {
                val clickable = n.isClickable || n.parent?.isClickable == true
                if (clickable) {
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    val area = maxOf(1, r.width() * r.height())
                    // skor dulu, lalu node TERBESAR: ikon kecil di preview
                    // folder jangan diklik duluan (itu cuma membuka folder)
                    if (sc > bestScore || (sc == bestScore && area > bestArea)) {
                        bestScore = sc; bestArea = area; bestNode = n
                    }
                }
            }
        }
        val found = bestNode
        if (found != null) {
            val target = if (found.isClickable) found else found.parent
            if (target != null) {
                clickAndVerify(target, targetName, 0, onResult)
                return
            }
        }

        // Urutan scan halaman home (semua app user ada di home, sebagian di
        // folder; tidak memakai app drawer karena swipe-up = feed berita):
        // phase 0: halaman sekarang, lalu 1x geser maju
        // phase 1: halaman maju, lalu 2x geser mundur (sampai halaman di
        //          sebelah kiri halaman awal)
        // phase 2: coba buka folder yang terlihat, scan isinya
        // phase 3: scan isi folder
        when (phase) {
            0 -> swipePage(back = false) { later(650) { tryFindingApp(targetName, 1, onResult) } }
            1 -> swipePage(back = true) { _ ->
                later(450) { swipePage(back = true) { later(650) { tryFindingApp(targetName, 2, onResult) } } }
            }
            2 -> {
                val folder = findNode { n ->
                    val l = label(n).lowercase()
                    l.startsWith("folder") && (n.isClickable || n.parent?.isClickable == true)
                }
                if (folder != null) {
                    val tgt = if (folder.isClickable) folder else folder.parent
                    if (tgt != null) {
                        clickNode(tgt) { _ -> later(900) { tryFindingApp(targetName, 3, onResult) } }
                        return
                    }
                }
                onResult(0, targetName)
            }
            else -> onResult(0, targetName)
        }
    }

    /** Geser halaman home: back=false maju (jari ke kiri), back=true mundur */
    private fun swipePage(back: Boolean, done: (Boolean) -> Unit) {
        val m = resources.displayMetrics
        val w = m.widthPixels.toFloat()
        val h = m.heightPixels.toFloat()
        val x1 = if (back) w * 0.2f else w * 0.8f
        val x2 = if (back) w * 0.8f else w * 0.2f
        swipeXY(x1, h * 0.5f, x2, h * 0.5f, 280) { ok -> done(ok) }
    }

    /**
     * Klik ikon lalu PASTIKAN benar-benar ada aplikasi terbuka (bukan cuma
     * folder yang kebuka). Kalau masih di launcher, kemungkinan yang tereklik
     * tadi adalah folder -> isi foldernya kini terlihat -> scan ulang dan
     * klik lagi (maksimal 2 tingkat).
     */
    private fun clickAndVerify(node: AccessibilityNodeInfo, targetName: String, depth: Int, onResult: (Int, String) -> Unit) {
        val before = rootInActiveWindow?.packageName
        clickNode(node) { ok ->
            if (!ok) { onResult(0, "Gagal klik"); return@clickNode }
            later(1800) {
                val after = rootInActiveWindow?.packageName
                if (after != null && after != before) {
                    onResult(1, targetName)
                    return@later
                }
                if (depth >= 2) { onResult(0, targetName); return@later }
                val t = norm(targetName)
                val root = rootInActiveWindow
                var pick: AccessibilityNodeInfo? = null
                if (root != null) {
                    walk(root) { n ->
                        if (pick != null) return@walk
                        val l = label(n)
                        val nl = norm(l)
                        if (nl.isNotEmpty() && nl.length <= 40 &&
                            (nl == t || nl.startsWith(t) || nl.contains(t)) &&
                            (n.isClickable || n.parent?.isClickable == true)) {
                            pick = n
                        }
                    }
                }
                val node2 = pick
                if (node2 == null) { onResult(0, targetName); return@later }
                val tgt2 = if (node2.isClickable) node2 else node2.parent
                if (tgt2 == null) { onResult(0, targetName); return@later }
                clickAndVerify(tgt2, targetName, depth + 1, onResult)
            }
        }
    }

    // ---------------- WhatsApp ----------------

    private val WA = "com.whatsapp"

    /**
     * Baca WhatsApp. chat != null -> buka chat itu lewat pencarian.
     * Return: teks percakapan / daftar chat yang terlihat.
     */
    fun whatsappRead(chat: String?, onResult: (Int, String) -> Unit) {
        waOpen(chat) { ok, msg ->
            if (!ok) { onResult(0, msg); return@waOpen }
            later(1500) {
                val txt = dumpScreenText(1800)
                onResult(1, if (txt.isBlank()) "tidak ada teks terbaca di layar" else txt)
            }
        }
    }

    /** Kirim pesan WhatsApp ke chat (opsional) dengan teks tertentu */
    fun whatsappReply(chat: String?, text: String, onResult: (Int, String) -> Unit) {
        waOpen(chat) { ok, msg ->
            if (!ok) { onResult(0, msg); return@waOpen }
            later(1500) { waTypeAndSend(text, onResult) }
        }
    }

    private fun waOpen(chat: String?, done: (Boolean, String) -> Unit) {
        if (!launchApp(WA)) { done(false, "WhatsApp tidak terpasang"); return }
        if (chat == null || chat.trim().isEmpty()) {
            later(1800) { done(true, "chat_list") }
            return
        }
        val q = chat.trim()
        later(1800) {
            val search = findNode { n ->
                val l = label(n).lowercase()
                l == "search" || l == "telusuri" || l == "cari" ||
                l.startsWith("search") || l.startsWith("telusuri")
            }
            if (search == null) { done(true, "chat_list"); return@later }
            clickNode(search) { _ ->
                later(700) {
                    val edit = findNode { it.isEditable }
                    if (edit == null) { done(true, "chat_list"); return@later }
                    if (!typeText(edit, q)) { done(false, "gagal menulis nama chat"); return@later }
                    later(1400) { waPickResult(edit, q, done) }
                }
            }
        }
    }

    private fun waPickResult(searchBox: AccessibilityNodeInfo, q: String, done: (Boolean, String) -> Unit) {
        val root = rootInActiveWindow
        var pick: AccessibilityNodeInfo? = null
        if (root != null) {
            walk(root) { n ->
                if (pick == null && n.isClickable && !n.isEditable) {
                    val l = norm(label(n))
                    val d = norm(firstDescText(n))
                    val nq = norm(q)
                    if (l.contains(nq) || d.contains(nq)) pick = n
                }
            }
        }
        val node = pick
        if (node != null) {
            clickNode(node) { ok -> done(ok, q) }
        } else {
            // fallback: tap tepat di bawah kotak pencarian (hasil pertama)
            val r = Rect()
            searchBox.getBoundsInScreen(r)
            tapXY(r.exactCenterX(), r.bottom + 180f) { ok -> done(ok, q) }
        }
    }

    private fun waTypeAndSend(text: String, onResult: (Int, String) -> Unit) {
        val edit = findNode { it.isEditable }
        if (edit == null) {
            onResult(0, "kotak ketik tidak ditemukan (mungkin masih di daftar chat, sebutkan nama chat)")
            return
        }
        if (!typeText(edit, text)) {
            onResult(0, "gagal menulis pesan")
            return
        }
        later(500) {
            val send = findNode { n ->
                val l = label(n).lowercase()
                l == "send" || l == "kirim" || l.startsWith("send") || l.startsWith("kirim")
            }
            if (send == null) {
                onResult(0, "teks sudah tertulis tapi tombol kirim tidak ketemu")
                return@later
            }
            clickNode(send) { ok ->
                if (ok) onResult(1, "pesan WhatsApp terkirim: $text")
                else onResult(0, "gagal klik tombol kirim")
            }
        }
    }

    private fun firstDescText(n: AccessibilityNodeInfo): String {
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            val l = label(c)
            if (l.isNotEmpty()) return l
        }
        return ""
    }

    // ---------------- Outlook ----------------

    private val OL = "com.microsoft.office.outlook"

    fun outlookRead(onResult: (Int, String) -> Unit) {
        if (!launchApp(OL)) { onResult(0, "Outlook tidak terpasang"); return }
        later(3200) {
            val txt = dumpScreenText(2000)
            onResult(1, if (txt.isBlank()) "tidak ada teks terbaca di layar" else txt)
        }
    }

    fun outlookReply(text: String, onResult: (Int, String) -> Unit) {
        if (!launchApp(OL)) { onResult(0, "Outlook tidak terpasang"); return }
        later(3000) {
            val reply = findNode { n ->
                val l = label(n).lowercase()
                l == "reply" || l == "balas" || l.startsWith("reply") || l.startsWith("balas")
            }
            if (reply == null) {
                onResult(0, "tombol balas tidak ditemukan (buka emailnya dulu)")
                return@later
            }
            clickNode(reply) { _ ->
                later(1600) {
                    val edit = findNode { it.isEditable }
                    if (edit == null || !typeText(edit, text)) {
                        onResult(0, "gagal menulis balasan")
                        return@later
                    }
                    later(600) {
                        val send = findNode { n ->
                            val l = label(n).lowercase()
                            l == "send" || l == "kirim" || l.startsWith("send") || l.startsWith("kirim")
                        }
                        if (send == null) {
                            onResult(0, "balasan sudah tertulis tapi tombol kirim tidak ketemu")
                            return@later
                        }
                        clickNode(send) { ok ->
                            if (ok) onResult(1, "balasan email terkirim")
                            else onResult(0, "gagal klik kirim")
                        }
                    }
                }
            }
        }
    }
}
