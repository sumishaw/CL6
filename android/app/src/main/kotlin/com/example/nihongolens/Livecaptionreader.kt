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
 * LiveCaptionReader — reads Live Captions, translates to Hindi.
 * Full diagnostic logging via CaptionLogger → /sdcard/caption_lens_log.txt
 */
class LiveCaptionReader : AccessibilityService() {

    companion object {
        private const val TAG          = "LCReader"
        private const val TRANSLATE_URL   = "http://127.0.0.1:8765/translate_text"
        private const val CONNECT_TIMEOUT = 2_000
        private const val READ_TIMEOUT    = 12_000
        private const val DEBOUNCE_MS     = 500L
        private const val MAX_WAIT_MS     = 3_000L
        private const val WATCHDOG_MS     = 1_500L

        private val LIVE_CAPTION_PACKAGES = setOf(
            "com.google.android.as",
            "com.google.android.as.oss",
            "com.google.android.tts",
        )

        @Volatile var isRunning = false
        @Volatile var instance: LiveCaptionReader? = null
    }

    private val scope          = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pendingJob:      Job? = null
    private var forceJob:        Job? = null
    private var translateJob:    Job? = null
    private var watchdogJob:     Job? = null

    private var lastSentText     = ""
    private var lastHindiOut     = ""
    private var lastDetectedLang = ""
    private val translateQueue   = LinkedBlockingQueue<String>()

    // Window reader state
    private var lastRawCaption    = ""
    private var lastSentText2     = ""
    private var captionWasVisible = false

    // Counters for log diagnostics
    private val eventsReceived   = AtomicLong(0)
    private val windowReadNull   = AtomicLong(0)
    private val enqueued         = AtomicLong(0)
    private val translated       = AtomicLong(0)
    private val translateErrors  = AtomicLong(0)
    private var lastTranslatedSentence = ""

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance  = this
        isRunning = true

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

        CaptionLogger.log(TAG, "=== LiveCaptionReader CONNECTED ===")
        CaptionLogger.log(TAG, "WATCHDOG=${WATCHDOG_MS}ms DEBOUNCE=${DEBOUNCE_MS}ms MAX_WAIT=${MAX_WAIT_MS}ms")

        scope.launch(Dispatchers.Main) {
            MainActivity.instance?.onLiveCaptionReaderConnected()
        }
    }

    override fun onInterrupt() {
        CaptionLogger.log(TAG, "!!! onInterrupt called — accessibility service interrupted !!!")
    }

    override fun onDestroy() {
        CaptionLogger.log(TAG, "=== LiveCaptionReader DESTROYED ===")
        CaptionLogger.log(TAG, "Final stats: events=${eventsReceived.get()} " +
            "nullReads=${windowReadNull.get()} enqueued=${enqueued.get()} " +
            "translated=${translated.get()} errors=${translateErrors.get()}")
        isRunning = false
        instance  = null
        pendingJob?.cancel()
        forceJob?.cancel()
        watchdogJob?.cancel()
        translateJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // ── Event path ────────────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRunning || event == null) return
        val n = eventsReceived.incrementAndGet()
        // Log every 50th event to avoid flooding the file
        if (n % 50 == 0L) CaptionLogger.log(TAG, "Events received: $n")

        val sendText = readFromCaptionWindow() ?: run {
            windowReadNull.incrementAndGet(); return
        }
        CaptionLogger.log(TAG, "EVENT text='${sendText.take(60)}'")
        scheduleTranslation(sendText)
    }

    // ── Watchdog path ─────────────────────────────────────────────────────────

    private fun startWatchdog() {
        watchdogJob = scope.launch {
            CaptionLogger.log(TAG, "Watchdog started ${WATCHDOG_MS}ms")
            var watchdogTick = 0L
            while (isActive && isRunning) {
                delay(WATCHDOG_MS)
                watchdogTick++

                val sendText = withContext(Dispatchers.Main) {
                    try { readFromCaptionWindow() } catch (e: Exception) {
                        CaptionLogger.log(TAG, "Watchdog readWindow EXCEPTION: ${e.message}")
                        null
                    }
                }

                if (sendText == null) {
                    // Log every 20 watchdog ticks
                    if (watchdogTick % 20 == 0L) {
                        CaptionLogger.log(TAG, "Watchdog tick=$watchdogTick: readWindow=null " +
                            "captionVisible=$captionWasVisible rawLen=${lastRawCaption.length} " +
                            "queueSize=${translateQueue.size}")
                    }
                    continue
                }

                if (sendText == lastSentText) {
                    CaptionLogger.log(TAG, "Watchdog: skip duplicate '${sendText.take(40)}'")
                    continue
                }

                CaptionLogger.log(TAG, "Watchdog tick=$watchdogTick NEW text='${sendText.take(60)}'")
                scheduleTranslation(sendText)
            }
            CaptionLogger.log(TAG, "Watchdog stopped")
        }
    }

    // ── Stats logger ──────────────────────────────────────────────────────────

    private fun startStatsLogger() {
        scope.launch {
            while (isActive && isRunning) {
                delay(30_000L)  // every 30s
                CaptionLogger.log(TAG, "STATS 30s: events=${eventsReceived.get()} " +
                    "nullReads=${windowReadNull.get()} enqueued=${enqueued.get()} " +
                    "translated=${translated.get()} errors=${translateErrors.get()} " +
                    "queueSize=${translateQueue.size} " +
                    "captionVisible=$captionWasVisible " +
                    "lastLang=$lastDetectedLang " +
                    "lastSent='${lastSentText.take(40)}'")
            }
        }
    }

    // ── Window reader ─────────────────────────────────────────────────────────

    private fun readFromCaptionWindow(): String? {
        val allWindows = try { windows } catch (e: Exception) {
            CaptionLogger.log(TAG, "windows EXCEPTION: ${e.message}")
            return null
        }

        var captionRoot: AccessibilityNodeInfo? = null
        if (!allWindows.isNullOrEmpty()) {
            for (window in allWindows) {
                val root = try { window.root } catch (_: Exception) { continue } ?: continue
                if (root.packageName?.toString() in LIVE_CAPTION_PACKAGES) {
                    captionRoot = root; break
                }
                root.recycle()
            }
        }

        if (captionRoot == null) {
            if (captionWasVisible) {
                captionWasVisible = false
                lastRawCaption    = ""
                lastSentText2     = ""
                CaptionLogger.log(TAG, "LC window GONE — state reset")
            }
            return null
        }

        val nodes = mutableListOf<String>()
        collectAllText(captionRoot, nodes)
        captionRoot.recycle()

        val fullText = nodes
            .filter  { isValidCaption(it) }
            .filter  { !isStaticUiLabel(it) }
            .maxByOrNull { it.length }
            ?.trim() ?: run {
                // Log actual content of every raw node to diagnose filter failures
                val rawSample = nodes.joinToString(" | ") { "'${it.take(60)}'" }
                CaptionLogger.log(TAG, "No valid nodes (${nodes.size}): [$rawSample] " +
                    "captionVisible=$captionWasVisible rawLen=${lastRawCaption.length}")
                return null
            }

        if (!captionWasVisible) {
            captionWasVisible = true
            lastRawCaption    = ""
            lastSentText2     = ""
            CaptionLogger.log(TAG, "LC window APPEARED — fresh session text='${fullText.take(60)}'")
        }

        if (fullText == lastRawCaption) return null

        val prevFull   = lastSentText2
        lastRawCaption = fullText

        val newPart: String = if (prevFull.isNotEmpty() && fullText.startsWith(prevFull))
            fullText.substring(prevFull.length).trim()
        else {
            CaptionLogger.log(TAG, "LC non-append: prevLen=${prevFull.length} newLen=${fullText.length}")
            fullText.takeLast(150).trim()
        }

        lastSentText2 = fullText

        if (newPart.length < 3) {
            CaptionLogger.log(TAG, "newPart too short (${newPart.length}): '${newPart}'")
            return null
        }
        return newPart
    }

    // ── Scheduling ────────────────────────────────────────────────────────────

    private fun scheduleTranslation(sendText: String) {
        val scriptNow = detectScript(sendText)
        if (scriptNow != lastDetectedLang && lastDetectedLang.isNotEmpty()) {
            CaptionLogger.log(TAG, "LANG SWITCH $lastDetectedLang→$scriptNow — clearing state+queue")
            lastSentText           = ""
            lastTranslatedSentence = ""
            lastHindiOut           = ""
            lastRawCaption         = ""
            lastSentText2          = ""
            translateQueue.clear()
        }
        lastDetectedLang = scriptNow

        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(DEBOUNCE_MS)
            val toSend = lastSentText2.ifBlank { sendText }
            CaptionLogger.log(TAG, "Debounce fired — enqueue '${toSend.take(60)}'")
            enqueueForTranslation(toSend)
        }

        if (forceJob == null || forceJob?.isActive == false) {
            forceJob = scope.launch {
                delay(MAX_WAIT_MS)
                pendingJob?.cancel()
                val toSend = lastSentText2.ifBlank { sendText }
                CaptionLogger.log(TAG, "ForceJob fired — enqueue '${toSend.take(60)}'")
                enqueueForTranslation(toSend)
            }
        }
    }

    private fun enqueueForTranslation(text: String) {
        forceJob?.cancel()
        forceJob = null
        if (text.isBlank()) {
            CaptionLogger.log(TAG, "enqueue SKIP: blank"); return
        }
        if (text == lastSentText) {
            CaptionLogger.log(TAG, "enqueue SKIP: duplicate '${text.take(40)}'"); return
        }
        lastSentText = text
        translateQueue.offer(text)
        enqueued.incrementAndGet()
        CaptionLogger.log(TAG, "ENQUEUED #${enqueued.get()} qSize=${translateQueue.size} '${text.take(60)}'")
    }

    // ── Translation worker ────────────────────────────────────────────────────

    private fun startTranslateWorker() {
        translateJob = scope.launch {
            CaptionLogger.log(TAG, "TranslateWorker started")
            while (isActive) {
                val text = withContext(Dispatchers.IO) {
                    try { translateQueue.poll(2, java.util.concurrent.TimeUnit.SECONDS) }
                    catch (_: InterruptedException) { null }
                }

                if (text == null) {
                    // Poll timeout — log every 5th idle to detect stalls
                    if (translated.get() > 0 && enqueued.get() == translated.get())
                        CaptionLogger.log(TAG, "Worker idle — queue empty, all translated")
                    continue
                }

                CaptionLogger.log(TAG, "Worker dequeued '${text.take(60)}' — calling server")
                val t0    = System.currentTimeMillis()
                val hindi = translate(text)
                val ms    = System.currentTimeMillis() - t0

                if (hindi == null) {
                    translateErrors.incrementAndGet()
                    CaptionLogger.log(TAG, "translate() RETURNED NULL in ${ms}ms errors=${translateErrors.get()}")
                    continue
                }
                if (hindi.isBlank()) {
                    CaptionLogger.log(TAG, "translate() returned BLANK in ${ms}ms text='${text.take(60)}'")
                    continue
                }

                translated.incrementAndGet()
                CaptionLogger.log(TAG, "TRANSLATED #${translated.get()} in ${ms}ms " +
                    "'${text.take(40)}' → '${hindi.take(40)}'")

                SpeechCaptureService.latestHindi   = hindi
                SpeechCaptureService.latestEnglish = text
                withContext(Dispatchers.Main) {
                    OverlayService.updateText(text, hindi)
                    MainActivity.instance?.onTranslation(text, hindi, hindi)
                }
            }
            CaptionLogger.log(TAG, "TranslateWorker STOPPED")
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
            if (code != 200) {
                CaptionLogger.log(TAG, "HTTP $code from server for '${text.take(40)}'")
                return null
            }
            val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            JSONObject(resp).optString("text", "").trim().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            CaptionLogger.log(TAG, "translate() EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun resetState() {
        lastSentText            = ""
        lastHindiOut            = ""
        lastTranslatedSentence  = ""
        lastDetectedLang        = ""
        lastRawCaption          = ""
        lastSentText2           = ""
        captionWasVisible       = false
        SpeechCaptureService.latestHindi   = ""
        SpeechCaptureService.latestEnglish = ""
    }

    private fun collectAllText(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        node ?: return
        val text = node.text?.toString()?.trim() ?: ""
        if (text.isNotBlank()) out.add(text)
        // Also check contentDescription — LC sometimes puts text here
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        if (desc.isNotBlank() && desc != text) out.add(desc)
        for (i in 0 until node.childCount) collectAllText(node.getChild(i), out)
    }

    private fun isStaticUiLabel(text: String): Boolean {
        val lower = text.lowercase()
        // Only drop confirmed Live Captions UI strings — locale selectors
        if (lower.contains("united states") || lower.contains("united kingdom")) return true
        if (lower.contains("simplified") || lower.contains("traditional")) return true
        // Known LC UI button labels
        if (text == "Hide" || text == "Settings" || text == "Feedback") return true
        return false
    }

    private fun isValidCaption(text: String): Boolean {
        if (text.length < 2 || text.length > 500) return false
        // Must contain at least 2 letters
        val letters = text.count { it.isLetter() }
        if (letters < 2) return false
        // Drop Android package names
        if (text.contains("com.android") || text.contains("com.google")) return false
        if (text.contains("http") || text.contains("www.")) return false
        return true
    }

    private fun detectScript(text: String): String {
        for (c in text) {
            val cp = c.code
            if (cp in 0x3040..0x30FF) return "ja"
            if (cp in 0x4E00..0x9FFF) return "zh"
            if (cp in 0xAC00..0xD7AF) return "ko"
            if (cp in 0x0600..0x06FF) return "ar"
            if (cp in 0x0400..0x04FF) return "ru"
            if (cp in 0x0900..0x097F) return "hi"
        }
        val hasAccent = text.any { it.isLetter() && it.code in 0x00C0..0x024F }
        return if (hasAccent) "latin_foreign" else "latin_en"
    }
}
