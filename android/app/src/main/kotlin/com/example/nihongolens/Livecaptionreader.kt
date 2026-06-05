package com.example.nihongolens

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * LiveCaptionReader — reads Live Captions → translates → OverlayService
 *
 * Architecture:
 *  - Events + watchdog feed into schedule() → debounce 300ms → enqueue()
 *  - enqueue() has 3-level dedup: exact / tail-80 / minor-grow-15ch
 *  - FIFO queue with sequence tokens — no stale translations after LC gone
 *  - Single force-job: fires MAX_WAIT_MS after last event, then self-cancels
 *  - Queue cap 3: keeps translations current, drops oldest when backlogged
 *  - Hindi output dedup: same result within 8s is skipped
 */
class LiveCaptionReader : AccessibilityService() {

    companion object {
        private const val TAG              = "LCReader"
        private const val TRANSLATE_URL    = "http://127.0.0.1:8765/translate_text"
        private const val CONNECT_TIMEOUT  = 3_000
        private const val READ_TIMEOUT     = 35_000
        private const val DEBOUNCE_MS      = 300L
        private const val MAX_WAIT_MS      = 2_000L
        private const val WATCHDOG_MS      = 2_000L
        private const val STARTUP_GRACE_MS = 1_000L
        private const val LANG_CONFIRM     = 3
        private const val QUEUE_CAP        = 3
        private const val MIN_ENQUEUE_LEN  = 8   // minimum chars to bother translating
        private const val MIN_GROW_CHARS   = 15  // minimum new chars before re-enqueue

        private val LC_PACKAGES = setOf(
            "com.google.android.as",
            "com.google.android.as.oss",
            "com.google.android.tts",
        )

        @Volatile var isRunning = false
        @Volatile var instance: LiveCaptionReader? = null
    }

    private val scope        = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pendingJob:    Job? = null
    private var forceJob:      Job? = null
    private var translateJob:  Job? = null
    private var watchdogJob:   Job? = null

    // FIFO + sequence tokens
    private val queue      = LinkedBlockingQueue<Pair<Long, String>>()
    private val seqCounter = AtomicLong(0)
    @Volatile private var expectedSeq = 0L

    // Dedup
    private var lastNorm         = ""
    private var lastEnqueuedFull = ""
    private var lastHindiOut     = ""
    private var lastHindiTime    = 0L
    private val HINDI_DEDUP_MS   = 6_000L

    // Language confirmation
    private var confirmedLang = ""
    private var pendingLang   = ""
    private var pendingCount  = 0

    // Window state
    private var lastRaw     = ""
    private var lastFull    = ""
    private var lcVisible   = false
    private var startupTime = 0L

    // Stats
    private val evtCount = AtomicLong(0)
    private val enqCount = AtomicLong(0)
    private val okCount  = AtomicLong(0)
    private val errCount = AtomicLong(0)

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance    = this
        isRunning   = true
        startupTime = System.currentTimeMillis()

        serviceInfo = serviceInfo?.also {
            it.eventTypes = (AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED)
            it.feedbackType        = AccessibilityServiceInfo.FEEDBACK_GENERIC
            it.notificationTimeout = 100
            it.flags = (AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS)
            it.packageNames = null
        }

        resetAll()
        startWorker()
        startWatchdog()
        startStats()
        CaptionLogger.log(TAG, "=== Connected ===")
        scope.launch(Dispatchers.Main) { MainActivity.instance?.onLiveCaptionReaderConnected() }
    }

    override fun onInterrupt() { CaptionLogger.log(TAG, "Interrupted") }

    override fun onDestroy() {
        isRunning = false; instance = null
        pendingJob?.cancel(); forceJob?.cancel()
        watchdogJob?.cancel(); translateJob?.cancel()
        queue.clear(); scope.cancel()
        SpeechCaptureService.latestHindi    = ""
        SpeechCaptureService.latestEnglish  = ""
        SpeechCaptureService.latestOriginal = ""
        OverlayService.updateText("", "")
        CaptionLogger.stop()
        super.onDestroy()
    }

    // ── Events ────────────────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRunning || event == null) return
        if (System.currentTimeMillis() - startupTime < STARTUP_GRACE_MS) return
        evtCount.incrementAndGet()
        val text = readWindow() ?: return
        schedule(text)
    }

    // ── Watchdog ──────────────────────────────────────────────────────────────

    private fun startWatchdog() {
        watchdogJob = scope.launch {
            var tick = 0L
            while (isActive && isRunning) {
                delay(WATCHDOG_MS)
                if (System.currentTimeMillis() - startupTime < STARTUP_GRACE_MS) continue
                tick++
                val text = withContext(Dispatchers.Main) {
                    try { readWindow() } catch (_: Exception) { null }
                } ?: run {
                    if (tick % 20L == 0L)
                        CaptionLogger.log(TAG, "WD null tick=$tick vis=$lcVisible")
                    return@run null
                } ?: continue

                // Watchdog uses same dedup — only schedule if meaningfully new
                val n = norm(text)
                if (n == lastNorm) continue
                val tail     = if (n.length > 80) n.takeLast(80) else n
                val lastTail = if (lastNorm.length > 80) lastNorm.takeLast(80) else lastNorm
                if (tail == lastTail) continue

                CaptionLogger.log(TAG, "WD new text tick=$tick")
                schedule(text)
            }
        }
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    private fun startStats() {
        scope.launch {
            while (isActive && isRunning) {
                delay(30_000L)
                CaptionLogger.log(TAG, "STATS evt=${evtCount.get()} enq=${enqCount.get()} " +
                    "ok=${okCount.get()} err=${errCount.get()} q=${queue.size} " +
                    "vis=$lcVisible lang=$confirmedLang seq=$expectedSeq")
            }
        }
    }

    // ── Window reader ─────────────────────────────────────────────────────────

    private fun readWindow(): String? {
        val wins = try { windows } catch (_: Exception) { return null }

        var root: AccessibilityNodeInfo? = null
        wins?.forEach { w ->
            if (root != null) return@forEach
            val r = try { w.root } catch (_: Exception) { null } ?: return@forEach
            if (r.packageName?.toString() in LC_PACKAGES) root = r else r.recycle()
        }

        if (root == null) {
            if (lcVisible) {
                lcVisible = false
                lastRaw = ""; lastFull = ""
                lastNorm = ""; lastEnqueuedFull = ""
                lastHindiOut = ""; lastHindiTime = 0L
                val dropped = queue.size
                queue.clear()
                expectedSeq = seqCounter.get() + 1
                pendingJob?.cancel(); pendingJob = null
                forceJob?.cancel();   forceJob   = null
                if (dropped > 0) CaptionLogger.log(TAG, "LC gone dropped=$dropped")
                else              CaptionLogger.log(TAG, "LC gone")
                OverlayService.clearQueue()
            }
            return null
        }

        val nodes = mutableListOf<String>()
        collectText(root, nodes)
        root.recycle()

        val full = nodes.filter { validCaption(it) && !uiLabel(it) }
            .maxByOrNull { it.length }?.trim() ?: return null

        if (!lcVisible) {
            lcVisible = true; lastRaw = ""; lastFull = ""
            CaptionLogger.log(TAG, "LC appeared '${full.take(50)}'")
        }

        if (full == lastRaw) return null

        val prev = lastFull
        lastRaw  = full

        val newPart = if (prev.isNotEmpty() && full.startsWith(prev))
            full.substring(prev.length).trim()
        else full.takeLast(120).trim()

        lastFull = full

        val isCjk = newPart.any { it.code in 0x3000..0x9FFF || it.code in 0xAC00..0xD7AF }
        if (newPart.length < if (isCjk) 1 else 4) return null

        return if (full.length > 120) full.takeLast(120).trim() else full.trim()
    }

    // ── Scheduling ────────────────────────────────────────────────────────────

    private fun norm(t: String) = t.trim().replace(Regex("\\s+"), " ")

    private fun schedule(text: String) {
        // Language switch detection
        val script = detectScript(text)
        if (script != confirmedLang) {
            if (script == pendingLang) {
                if (++pendingCount >= LANG_CONFIRM) {
                    CaptionLogger.log(TAG, "LANG $confirmedLang→$script")
                    confirmedLang = script; pendingLang = ""; pendingCount = 0
                    lastNorm = ""; lastEnqueuedFull = ""; lastFull = ""; lastRaw = ""
                    queue.clear(); expectedSeq = seqCounter.get() + 1
                }
            } else { pendingLang = script; pendingCount = 1 }
        } else { pendingLang = ""; pendingCount = 0 }

        // Debounce: restart 300ms timer on every new event
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(DEBOUNCE_MS)
            enqueue(lastFull.ifBlank { text })
        }

        // Force-send: one timer only, fires MAX_WAIT_MS after first unhandled event
        // Self-cancels after firing to prevent stacking
        if (forceJob == null || forceJob?.isActive == false) {
            forceJob = scope.launch {
                delay(MAX_WAIT_MS)
                forceJob = null
                pendingJob?.cancel(); pendingJob = null
                enqueue(lastFull.ifBlank { text })
            }
        }
    }

    private fun enqueue(text: String) {
        if (text.isBlank() || text.length < MIN_ENQUEUE_LEN) return

        val n = norm(text)

        // Level 1: exact match
        if (n == lastNorm) return

        // Level 2: tail-80 match (LC accumulates — same tail = same content)
        val tail     = if (n.length > 80) n.takeLast(80) else n
        val lastTail = if (lastNorm.length > 80) lastNorm.takeLast(80) else lastNorm
        if (tail == lastTail) return

        // Level 3: minor growth gate — require 15 new meaningful chars
        // Cancel force-job too so it doesn't retry the same minor-grow text
        val isAppend = lastEnqueuedFull.isNotEmpty() &&
            n.length > lastEnqueuedFull.length &&
            n.startsWith(lastEnqueuedFull.take(lastEnqueuedFull.length.coerceAtMost(n.length - 5)))
        if (isAppend && (n.length - lastEnqueuedFull.length) < MIN_GROW_CHARS) {
            CaptionLogger.log(TAG, "SKIP grow +${n.length - lastEnqueuedFull.length}ch")
            forceJob?.cancel(); forceJob = null   // don't retry same minor-grow
            return
        }

        lastNorm         = n
        lastEnqueuedFull = n
        val seq          = seqCounter.incrementAndGet()

        // Cap: drop oldest when backlogged
        while (queue.size >= QUEUE_CAP) queue.poll()
        queue.offer(Pair(seq, text))
        enqCount.incrementAndGet()
        CaptionLogger.log(TAG, "ENQ seq=$seq q=${queue.size} '${text.take(60)}'")
    }

    // ── Translation worker ────────────────────────────────────────────────────

    private fun startWorker() {
        translateJob = scope.launch {
            while (isActive) {
                val item = withContext(Dispatchers.IO) {
                    try { queue.poll(2, TimeUnit.SECONDS) }
                    catch (_: InterruptedException) { null }
                } ?: continue

                val (seq, text) = item
                if (seq < expectedSeq) { CaptionLogger.log(TAG, "STALE $seq"); continue }

                val t0    = System.currentTimeMillis()
                val hindi = callServer(text)
                val ms    = System.currentTimeMillis() - t0

                if (seq < expectedSeq) { CaptionLogger.log(TAG, "DISCARD $seq ${ms}ms"); continue }

                if (hindi.isNullOrBlank()) {
                    errCount.incrementAndGet()
                    CaptionLogger.log(TAG, "ERR ${ms}ms '${text.take(40)}'")
                    if (norm(text) == lastNorm) lastNorm = ""
                    continue
                }

                // Hindi output dedup — same result within HINDI_DEDUP_MS = skip
                val hNorm = norm(hindi)
                val now   = System.currentTimeMillis()
                if (hNorm == norm(lastHindiOut) && (now - lastHindiTime) < HINDI_DEDUP_MS) {
                    CaptionLogger.log(TAG, "SKIP dup Hindi ${ms}ms")
                    continue
                }
                lastHindiOut  = hindi
                lastHindiTime = now

                okCount.incrementAndGet()
                CaptionLogger.log(TAG, "OK $seq ${ms}ms '${hindi.take(50)}'")
                SpeechCaptureService.latestHindi   = hindi
                SpeechCaptureService.latestEnglish = text
                withContext(Dispatchers.Main) {
                    OverlayService.updateText(text, hindi)
                    MainActivity.instance?.onTranslation(text, hindi, hindi)
                }
            }
        }
    }

    private fun callServer(text: String): String? {
        if (text.trim().length < MIN_ENQUEUE_LEN) return null
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(TRANSLATE_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.doOutput       = true
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout    = READ_TIMEOUT
            val body = """{"text":${JSONObject.quote(text)},"src":"auto","tgt":"hi"}"""
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) {
                CaptionLogger.log(TAG, "HTTP ${conn.responseCode}"); return null
            }
            JSONObject(conn.inputStream.bufferedReader().readText())
                .optString("text", "").trim().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            CaptionLogger.log(TAG, "server ex: ${e.javaClass.simpleName}: ${e.message}"); null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun resetAll() {
        lastNorm = ""; lastEnqueuedFull = ""; confirmedLang = ""
        pendingLang = ""; pendingCount = 0
        lastRaw = ""; lastFull = ""; lcVisible = false; expectedSeq = 0L
        lastHindiOut = ""; lastHindiTime = 0L
        SpeechCaptureService.latestHindi    = ""
        SpeechCaptureService.latestEnglish  = ""
        SpeechCaptureService.latestOriginal = ""
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        node ?: return
        node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        node.contentDescription?.toString()?.trim()
            ?.takeIf { it.isNotBlank() && it != out.lastOrNull() }?.let { out.add(it) }
        for (i in 0 until node.childCount) collectText(node.getChild(i), out)
    }

    private fun uiLabel(t: String): Boolean {
        val l = t.lowercase()
        if (l == "live caption" || l == "live captions") return true
        if (l.startsWith("live caption") && t.length < 30) return true
        if (l.contains("united states") || l.contains("united kingdom")) return true
        if (l.contains("simplified") || l.contains("traditional")) return true
        if (t == "Hide" || t == "Settings" || t == "Feedback") return true
        return false
    }

    private fun validCaption(t: String): Boolean {
        if (t.length < 2 || t.length > 500) return false
        if (t.count { it.isLetter() } < 2) return false
        if (t.contains("com.android") || t.contains("com.google")) return false
        if (t.contains("http") || t.contains("www.")) return false
        return true
    }

    private fun detectScript(text: String): String {
        var ja = 0; var zh = 0; var ko = 0; var ar = 0; var ru = 0; var hi = 0
        for (c in text) when (c.code) {
            in 0x3040..0x30FF -> ja++
            in 0x4E00..0x9FFF -> zh++
            in 0xAC00..0xD7AF -> ko++
            in 0x0600..0x06FF -> ar++
            in 0x0400..0x04FF -> ru++
            in 0x0900..0x097F -> hi++
        }
        val nonLatin = maxOf(ja, zh, ko, ar, ru, hi)
        if (nonLatin > 0) return when (nonLatin) {
            ja -> "ja"; ko -> "ko"; hi -> "hi"; ar -> "ar"; ru -> "ru"; else -> "zh"
        }
        return if (text.any { it.isLetter() && it.code in 0x00C0..0x024F })
            "latin_foreign" else "latin_en"
    }
}
