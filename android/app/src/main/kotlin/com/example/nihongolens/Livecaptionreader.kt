package com.example.nihongolens

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * LiveCaptionReader
 *
 * Reads Live Captions → translates to Hindi via whisper_server → OverlayService.
 *
 * Key fixes vs previous versions:
 * - Lang switch: only resets state if same script persists for 3+ consecutive reads
 *   (prevents oscillation when LC window contains mixed JA+ZH text)
 * - FIFO queue: unbounded LinkedBlockingQueue, never drops
 * - Startup grace: 1s ignore after connect to avoid burst
 * - Error retry: clears dedup on CT2 error so sentence can retry
 * - Watchdog dedup: shares lastSentText with event path
 */
class LiveCaptionReader : AccessibilityService() {

    companion object {
        private const val TAG              = "LCReader"
        private const val TRANSLATE_URL    = "http://127.0.0.1:8765/translate_text"
        private const val CONNECT_TIMEOUT  = 3_000
        private const val READ_TIMEOUT     = 30_000
        private const val DEBOUNCE_MS      = 500L
        private const val MAX_WAIT_MS      = 3_000L
        private const val WATCHDOG_MS      = 1_500L
        private const val STARTUP_GRACE_MS = 1_000L
        // Lang switch only triggers after this many consecutive reads of same script
        // Prevents oscillation when LC window has mixed-script content
        private const val LANG_SWITCH_CONFIRM = 3

        private val LIVE_CAPTION_PACKAGES = setOf(
            "com.google.android.as",
            "com.google.android.as.oss",
            "com.google.android.tts",
        )

        @Volatile var isRunning = false
        @Volatile var instance: LiveCaptionReader? = null
    }

    private val scope       = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pendingJob:   Job? = null
    private var forceJob:     Job? = null
    private var translateJob: Job? = null
    private var watchdogJob:  Job? = null

    // FIFO — unbounded, never drops
    // Each item carries a sequence token so worker can detect items
    // enqueued before a queue-clear (stale after LC window disappeared)
    private val translateQueue   = LinkedBlockingQueue<Pair<Long, String>>()
    private val seqCounter       = AtomicLong(0)
    private var expectedSeq      = 0L   // items with seq < expectedSeq are stale

    // Dedup
    private var lastEnqueuedNorm = ""
    private var lastSentText     = ""

    // Lang tracking with confirmation counter (prevents oscillation)
    private var lastConfirmedLang    = ""
    private var pendingLang          = ""
    private var pendingLangCount     = 0

    // Window state
    private var lastRawCaption    = ""
    private var lastSentText2     = ""
    private var captionWasVisible = false

    // Startup
    private var startupTime = 0L

    // Stats
    private val eventsReceived  = AtomicLong(0)
    private val enqueued        = AtomicLong(0)
    private val translated      = AtomicLong(0)
    private val translateErrors = AtomicLong(0)

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance    = this
        isRunning   = true
        startupTime = System.currentTimeMillis()

        serviceInfo = serviceInfo?.also { info ->
            info.eventTypes = (
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            )
            info.feedbackType        = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.notificationTimeout = 100
            info.flags               = (
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            )
            info.packageNames = null
        }

        resetState()
        startTranslateWorker()
        startWatchdog()
        startStatsLogger()
        CaptionLogger.log(TAG, "=== Connected ===")
        scope.launch(Dispatchers.Main) { MainActivity.instance?.onLiveCaptionReaderConnected() }
    }

    override fun onInterrupt() { CaptionLogger.log(TAG, "!!! Interrupted !!!") }

    override fun onDestroy() {
        CaptionLogger.log(TAG, "=== Destroyed | enq=${enqueued.get()} " +
            "ok=${translated.get()} err=${translateErrors.get()} ===")
        isRunning = false; instance = null
        pendingJob?.cancel(); forceJob?.cancel()
        watchdogJob?.cancel(); translateJob?.cancel()
        translateQueue.clear()
        scope.cancel()
        SpeechCaptureService.latestHindi    = ""
        SpeechCaptureService.latestEnglish  = ""
        SpeechCaptureService.latestOriginal = ""
        OverlayService.updateText("", "")
        CaptionLogger.stop()
        super.onDestroy()
    }

    // ── Event path ────────────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRunning || event == null) return
        if (System.currentTimeMillis() - startupTime < STARTUP_GRACE_MS) return
        eventsReceived.incrementAndGet()
        val text = readFromCaptionWindow() ?: return
        CaptionLogger.log(TAG, "EV '${text.take(60)}'")
        scheduleTranslation(text)
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
                    try { readFromCaptionWindow() } catch (e: Exception) {
                        CaptionLogger.log(TAG, "WD ex: ${e.message}"); null
                    }
                } ?: run {
                    if (tick % 20L == 0L) CaptionLogger.log(TAG,
                        "WD null tick=$tick vis=$captionWasVisible rawLen=${lastRawCaption.length}")
                    return@run null
                } ?: continue
                if (text == lastSentText) continue
                CaptionLogger.log(TAG, "WD '${text.take(60)}'")
                scheduleTranslation(text)
            }
        }
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    private fun startStatsLogger() {
        scope.launch {
            while (isActive && isRunning) {
                delay(30_000L)
                CaptionLogger.log(TAG, "STATS events=${eventsReceived.get()} " +
                    "enqueued=${enqueued.get()} translated=${translated.get()} " +
                    "errors=${translateErrors.get()} qSize=${translateQueue.size} " +
                    "visible=$captionWasVisible lang=$lastConfirmedLang " +
                    "lastSent='${lastSentText.take(40)}'")
            }
        }
    }

    // ── Window reader ─────────────────────────────────────────────────────────

    private fun readFromCaptionWindow(): String? {
        val allWindows = try { windows } catch (e: Exception) {
            CaptionLogger.log(TAG, "windows() ex: ${e.message}"); return null
        }

        var captionRoot: AccessibilityNodeInfo? = null
        allWindows?.forEach { win ->
            if (captionRoot != null) return@forEach
            val root = try { win.root } catch (_: Exception) { null } ?: return@forEach
            if (root.packageName?.toString() in LIVE_CAPTION_PACKAGES) captionRoot = root
            else root.recycle()
        }

        if (captionRoot == null) {
            if (captionWasVisible) {
                captionWasVisible  = false
                lastRawCaption     = ""
                lastSentText2      = ""
                // Clear dedup so next LC session enqueues fresh content immediately
                lastEnqueuedNorm   = ""
                lastSentText       = ""
                val dropped = translateQueue.size
                translateQueue.clear()
                expectedSeq = seqCounter.get() + 1
                pendingJob?.cancel(); pendingJob = null
                forceJob?.cancel();   forceJob   = null
                if (dropped > 0) CaptionLogger.log(TAG, "LC gone → dropped $dropped (expectedSeq=$expectedSeq)")
                else CaptionLogger.log(TAG, "LC gone → reset")
                OverlayService.clearQueue()
            }
            return null
        }

        val nodes = mutableListOf<String>()
        collectAllText(captionRoot, nodes)
        captionRoot.recycle()

        val fullText = nodes
            .filter { isValidCaption(it) }
            .filter { !isStaticUiLabel(it) }
            .maxByOrNull { it.length }
            ?.trim() ?: run {
                val raw = nodes.joinToString("|") { "'${it.take(30)}'" }
                CaptionLogger.log(TAG, "No valid (${nodes.size}): $raw")
                return null
            }

        if (!captionWasVisible) {
            captionWasVisible  = true
            lastRawCaption     = ""
            lastSentText2      = ""
            CaptionLogger.log(TAG, "LC appeared: '${fullText.take(60)}'")
        }

        if (fullText == lastRawCaption) return null

        val prev = lastSentText2
        lastRawCaption = fullText

        val newPart = if (prev.isNotEmpty() && fullText.startsWith(prev))
            fullText.substring(prev.length).trim()
        else {
            CaptionLogger.log(TAG, "Non-append: prevLen=${prev.length} newLen=${fullText.length}")
            fullText.takeLast(150).trim()
        }
        lastSentText2 = fullText

        val isCjk = newPart.any { it.code in 0x3000..0x9FFF || it.code in 0xAC00..0xD7AF }
        if (newPart.length < if (isCjk) 1 else 4) {
            CaptionLogger.log(TAG, "newPart short (${newPart.length})")
            return null
        }

        return if (fullText.length > 150) fullText.takeLast(150).trim() else fullText.trim()
    }

    // ── Scheduling ────────────────────────────────────────────────────────────

    private fun normalize(t: String) = t.trim().replace(Regex("\\s+"), " ")

    private fun scheduleTranslation(text: String) {
        // Lang switch detection with confirmation counter.
        // Require LANG_SWITCH_CONFIRM consecutive reads of the new script
        // before accepting it as a real switch — prevents oscillation
        // when LC window briefly shows mixed JA+ZH content during scene change.
        val script = detectScript(text)
        if (script != lastConfirmedLang) {
            if (script == pendingLang) {
                pendingLangCount++
                if (pendingLangCount >= LANG_SWITCH_CONFIRM) {
                    CaptionLogger.log(TAG, "LANG CONFIRMED $lastConfirmedLang→$script")
                    lastConfirmedLang = script
                    pendingLang       = ""
                    pendingLangCount  = 0
                    lastSentText      = ""
                    lastEnqueuedNorm  = ""
                    lastRawCaption    = ""
                    lastSentText2     = ""
                    translateQueue.clear()
                    expectedSeq = seqCounter.get() + 1
                }
            } else {
                pendingLang      = script
                pendingLangCount = 1
            }
        } else {
            pendingLang      = ""
            pendingLangCount = 0
        }

        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(DEBOUNCE_MS)
            enqueueForTranslation(lastSentText2.ifBlank { text })
        }

        if (forceJob == null || forceJob?.isActive == false) {
            forceJob = scope.launch {
                delay(MAX_WAIT_MS)
                pendingJob?.cancel()
                enqueueForTranslation(lastSentText2.ifBlank { text })
            }
        }
    }

    private fun enqueueForTranslation(text: String) {
        forceJob?.cancel(); forceJob = null
        if (text.isBlank()) return
        val norm = normalize(text)
        if (norm == lastEnqueuedNorm) {
            CaptionLogger.log(TAG, "SKIP dup '${text.take(50)}'"); return
        }
        lastEnqueuedNorm = norm
        lastSentText     = text
        val seq = seqCounter.incrementAndGet()
        translateQueue.offer(Pair(seq, text))
        enqueued.incrementAndGet()
        CaptionLogger.log(TAG, "ENQ seq=$seq q=${translateQueue.size} '${text.take(60)}'")
    }

    // ── Translation worker ────────────────────────────────────────────────────

    private fun startTranslateWorker() {
        translateJob = scope.launch {
            while (isActive) {
                val item = withContext(Dispatchers.IO) {
                    try { translateQueue.poll(2, java.util.concurrent.TimeUnit.SECONDS) }
                    catch (_: InterruptedException) { null }
                } ?: continue

                val (seq, text) = item

                // Skip stale items (enqueued before LC window disappeared)
                if (seq < expectedSeq) {
                    CaptionLogger.log(TAG, "SKIP stale seq=$seq (expected>=$expectedSeq)")
                    continue
                }

                val t0    = System.currentTimeMillis()
                val hindi = translate(text)
                val ms    = System.currentTimeMillis() - t0

                // Check again after translation (LC may have disappeared during CT2 call)
                if (seq < expectedSeq) {
                    CaptionLogger.log(TAG, "DISCARD post-translate stale seq=$seq")
                    continue
                }

                if (hindi.isNullOrBlank()) {
                    translateErrors.incrementAndGet()
                    CaptionLogger.log(TAG, "ERR ${ms}ms seq=$seq '${text.take(40)}'")
                    if (normalize(text) == lastEnqueuedNorm) lastEnqueuedNorm = ""
                    continue
                }

                translated.incrementAndGet()
                CaptionLogger.log(TAG, "OK seq=$seq ${ms}ms → '${hindi.take(40)}'")
                SpeechCaptureService.latestHindi   = hindi
                SpeechCaptureService.latestEnglish = text
                withContext(Dispatchers.Main) {
                    OverlayService.updateText(text, hindi)
                    MainActivity.instance?.onTranslation(text, hindi, hindi)
                }
            }
        }
    }

    private fun translate(text: String): String? {
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
            val code = conn.responseCode
            if (code != 200) { CaptionLogger.log(TAG, "HTTP $code"); return null }
            val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            JSONObject(resp).optString("text", "").trim().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            CaptionLogger.log(TAG, "translate ex: ${e.javaClass.simpleName}: ${e.message}"); null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun resetState() {
        lastSentText      = ""; lastEnqueuedNorm  = ""
        lastConfirmedLang = ""; pendingLang        = ""; pendingLangCount = 0
        lastRawCaption    = ""; lastSentText2      = ""; captionWasVisible = false
        expectedSeq       = 0L
        SpeechCaptureService.latestHindi    = ""
        SpeechCaptureService.latestEnglish  = ""
        SpeechCaptureService.latestOriginal = ""
    }

    private fun collectAllText(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        node ?: return
        val t = node.text?.toString()?.trim() ?: ""
        if (t.isNotBlank()) out.add(t)
        val d = node.contentDescription?.toString()?.trim() ?: ""
        if (d.isNotBlank() && d != t) out.add(d)
        for (i in 0 until node.childCount) collectAllText(node.getChild(i), out)
    }

    private fun isStaticUiLabel(text: String): Boolean {
        val l = text.lowercase()
        if (l.contains("united states") || l.contains("united kingdom")) return true
        if (l.contains("simplified")    || l.contains("traditional"))    return true
        if (text == "Hide" || text == "Settings" || text == "Feedback")   return true
        return false
    }

    private fun isValidCaption(text: String): Boolean {
        if (text.length < 2 || text.length > 500) return false
        if (text.count { it.isLetter() } < 2) return false
        if (text.contains("com.android") || text.contains("com.google")) return false
        if (text.contains("http") || text.contains("www.")) return false
        return true
    }

    private fun detectScript(text: String): String {
        // Count characters per script to pick the dominant one
        // Avoids false switches when a single foreign char appears in otherwise Latin text
        var ja = 0; var zh = 0; var ko = 0; var ar = 0; var ru = 0; var hi = 0
        for (c in text) {
            val cp = c.code
            when {
                cp in 0x3040..0x30FF -> ja++
                cp in 0x4E00..0x9FFF -> zh++
                cp in 0xAC00..0xD7AF -> ko++
                cp in 0x0600..0x06FF -> ar++
                cp in 0x0400..0x04FF -> ru++
                cp in 0x0900..0x097F -> hi++
            }
        }
        val maxCount = maxOf(ja, zh, ko, ar, ru, hi)
        if (maxCount == 0) {
            return if (text.any { it.isLetter() && it.code in 0x00C0..0x024F })
                "latin_foreign" else "latin_en"
        }
        return when (maxCount) {
            ja -> "ja"
            ko -> "ko"
            hi -> "hi"
            ar -> "ar"
            ru -> "ru"
            else -> "zh"
        }
    }
}
