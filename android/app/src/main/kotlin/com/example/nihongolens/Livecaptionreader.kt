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
 * LiveCaptionReader — Live Captions → Hindi translation → Overlay
 *
 * Key design decisions:
 * - readWindow() extracts the LAST COMPLETE SENTENCE from LC accumulated text
 *   (sentence ending with 。？！\n or punctuation) — not raw accumulated text
 * - Simple dedup: only skip if exact same text was last enqueued
 * - No grow-gate: every meaningful new sentence is enqueued
 * - FIFO queue (unbounded) with sequence tokens — nothing dropped by dedup logic
 * - Queue cap=5 only drops oldest when severely backlogged (CT2 slow)
 */
class LiveCaptionReader : AccessibilityService() {

    companion object {
        private const val TAG              = "LCReader"
        private const val TRANSLATE_URL    = "http://127.0.0.1:8765/translate_text"
        private const val CONNECT_TIMEOUT  = 3_000
        private const val READ_TIMEOUT     = 35_000
        private const val DEBOUNCE_MS      = 400L
        private const val WATCHDOG_MS      = 2_000L
        private const val STARTUP_GRACE_MS = 1_000L
        private const val LANG_CONFIRM     = 3
        private const val QUEUE_CAP        = 5

        private val LC_PACKAGES = setOf(
            "com.google.android.as",
            "com.google.android.as.oss",
            "com.google.android.tts",
        )

        @Volatile var isRunning = false
        @Volatile var instance: LiveCaptionReader? = null
    }

    private val scope       = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pendingJob:   Job? = null
    private var translateJob: Job? = null
    private var watchdogJob:  Job? = null

    // FIFO + tokens
    private val queue      = LinkedBlockingQueue<Pair<Long, String>>()
    private val seqCounter = AtomicLong(0)
    @Volatile private var expectedSeq = 0L

    // Simple dedup — only exact match
    private var lastEnqueued  = ""
    private var lastHindiOut  = ""
    private var lastHindiTime = 0L
    private val HINDI_DEDUP_MS = 4_000L

    // Language tracking
    private var confirmedLang = ""
    private var pendingLang   = ""
    private var pendingCount  = 0

    // Window state
    private var lastRawFull           = ""
    private var lastSentText          = ""
    private var lastEnqueuedSents     = mutableSetOf<String>()
    private var lcVisible             = false
    private var startupTime           = 0L

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
        pendingJob?.cancel()
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
                lcVisible             = false
                lastRawFull           = ""
                lastEnqueued          = ""
                lastSentText          = ""
                lastEnqueuedSents.clear()
                val dropped = queue.size
                queue.clear()
                expectedSeq = seqCounter.get() + 1
                pendingJob?.cancel(); pendingJob = null
                if (dropped > 0) CaptionLogger.log(TAG, "LC gone dropped=$dropped")
                else              CaptionLogger.log(TAG, "LC gone")
                OverlayService.clearQueue()
                // Stop TTS immediately — video is silent/paused
                HindiTtsService.stopAndClear()
                sentenceTimerJob?.cancel(); sentenceTimerJob = null
                sentenceBuffer = ""; lastBufferEnqueued = ""; lastEnqueuedWordCount = 0
                lastEnqueuedText = ""
            }
            return null
        }

        val nodes = mutableListOf<String>()
        collectText(root, nodes)
        root.recycle()

        val full = nodes.filter { validCaption(it) && !uiLabel(it) }
            .maxByOrNull { it.length }?.trim() ?: return null

        if (!lcVisible) {
            lcVisible   = true
            lastRawFull = ""
            lastEnqueuedSents.clear()
            CaptionLogger.log(TAG, "LC appeared '${full.take(60)}'")
        }

        if (full == lastRawFull) return null

        val prev    = lastRawFull
        lastRawFull = full

        // Get only the NEW text added since last read
        val newText = if (prev.isNotEmpty() && full.startsWith(prev))
            full.substring(prev.length).trim()
        else {
            // LC window scrolled or reset — use only the last sentence of full text
            // Split by sentence boundary and take the last complete-looking sentence
            val sentences = full.split(Regex("""(?<=[.!?。！？])\s+"""))
            sentences.lastOrNull { it.trim().length >= 4 }?.trim() ?: return null
        }

        if (newText.length >= 4) return newText
        return null
    }

    private fun splitSentences(text: String): List<String> {
        val result = mutableListOf<String>()
        val parts  = text.split(Regex("""(?<=[。！？
.!?])\s*"""))
        for (part in parts) {
            val t = part.trim()
            if (t.length >= 4) result.add(t)
        }
        if (result.isEmpty() && text.trim().length >= 4) result.add(text.trim())
        return result
    }

    private fun directEnqueue(text: String) {
        if (text.isBlank() || text.length < 4) return
        val n = norm(text)
        if (n == lastEnqueued) return
        lastEnqueued = n
        val seq = seqCounter.incrementAndGet()
        if (queue.size >= QUEUE_CAP) queue.poll()
        queue.offer(Pair(seq, text))
        enqCount.incrementAndGet()
        CaptionLogger.log(TAG, "ENQ-S seq=$seq q=${queue.size} '${text.take(60)}'")
    }

    // ── Scheduling ────────────────────────────────────────────────────────────

    private fun norm(t: String) = t.trim().replace(Regex("\\s+"), " ")

    private var sentenceBuffer      = ""
    private var lastBufferEnqueued  = ""   // tracks last text actually sent to worker
    private var lastLCChangeMs      = 0L
    private val SENTENCE_SILENCE_MS = 600L
    private var sentenceTimerJob: Job? = null
    private var lastEnqueuedWordCount = 0
    private var lastEnqueuedText      = ""

    private fun schedule(text: String) {
        // Language detection
        val script = detectScript(text)
        if (script != confirmedLang) {
            if (script == pendingLang) {
                if (++pendingCount >= LANG_CONFIRM) {
                    CaptionLogger.log(TAG, "LANG $confirmedLang→$script")
                    confirmedLang = script; pendingLang = ""; pendingCount = 0
                    lastEnqueued = ""; lastRawFull = ""; lastEnqueuedSents.clear()
                    sentenceBuffer = ""; lastBufferEnqueued = ""
                    lastEnqueuedWordCount = 0; lastEnqueuedText = ""
                    queue.clear(); expectedSeq = seqCounter.get() + 1
                }
            } else { pendingLang = script; pendingCount = 1 }
        } else { pendingLang = ""; pendingCount = 0 }

        sentenceBuffer = text
        lastLCChangeMs = System.currentTimeMillis()

        val trimmed   = text.trim()
        val words     = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
        val wordCount = words.size

        // Always cap at 12 words for translation — keeps TTS under 3s per chunk
        val toTranslate = if (wordCount > 12) words.take(12).joinToString(" ") else trimmed

        // TRIGGER 1a: strong punctuation → translate immediately (100ms settle)
        val endsWithHardPunct = trimmed.endsWith(".") || trimmed.endsWith("?") ||
                                trimmed.endsWith("!") || trimmed.endsWith("。") ||
                                trimmed.endsWith("？") || trimmed.endsWith("！") ||
                                trimmed.endsWith("…")
        if (endsWithHardPunct && wordCount >= 3) {
            sentenceTimerJob?.cancel(); pendingJob?.cancel()
            pendingJob = scope.launch {
                delay(100)
                val t = capWords(sentenceBuffer.trim(), 12)
                if (t.isNotBlank() && t != lastEnqueuedText) {
                    lastEnqueuedText = t; lastEnqueuedWordCount = wordCount
                    enqueue(t); sentenceBuffer = ""
                }
            }
            return
        }

        // TRIGGER 1b: soft punctuation (,;:-) → translate after 350ms if 5+ words
        val endsWithSoftPunct = trimmed.endsWith(",") || trimmed.endsWith(";") ||
                                trimmed.endsWith(":") || trimmed.endsWith(" -") ||
                                trimmed.endsWith("—")
        if (endsWithSoftPunct && wordCount >= 5) {
            sentenceTimerJob?.cancel(); pendingJob?.cancel()
            pendingJob = scope.launch {
                delay(350)
                val t = capWords(sentenceBuffer.trim(), 12)
                if (t.isNotBlank() && t != lastEnqueuedText) {
                    lastEnqueuedText = t; lastEnqueuedWordCount = wordCount
                    enqueue(t); sentenceBuffer = ""
                }
            }
            return
        }

        // TRIGGER 2: natural phrase boundary — 5+ word growth OR 8+ words total
        // Lower threshold catches spoken phrases faster without waiting for silence
        val grown = wordCount - lastEnqueuedWordCount
        if ((grown >= 5 && wordCount >= 5) || (wordCount >= 8 && grown >= 3)) {
            sentenceTimerJob?.cancel(); pendingJob?.cancel()
            pendingJob = scope.launch {
                delay(150)  // brief settle for word correction
                val t = capWords(sentenceBuffer.trim(), 12)
                if (t.isNotBlank() && t != lastEnqueuedText) {
                    lastEnqueuedText = t; lastEnqueuedWordCount = wordCount
                    enqueue(t); sentenceBuffer = ""
                }
            }
            return
        }

        // TRIGGER 3: silence — 1.2s no change
        sentenceTimerJob?.cancel()
        sentenceTimerJob = scope.launch {
            delay(SENTENCE_SILENCE_MS)
            val t = capWords(sentenceBuffer.trim(), 12)
            if (t.isNotBlank() && t != lastEnqueuedText && wordCount >= 2) {
                CaptionLogger.log(TAG, "SILENCE translate")
                lastEnqueuedText = t; lastEnqueuedWordCount = wordCount
                enqueue(t); sentenceBuffer = ""
            }
        }
        pendingJob?.cancel()
    }

        private fun capWords(text: String, maxWords: Int): String {
        val words = text.trim().split(Regex("\\s+"))
        return if (words.size <= maxWords) text.trim()
               else words.take(maxWords).joinToString(" ")
    }

    private fun enqueue(text: String) {
        if (text.isBlank() || text.length < 4) return

        // LOOP PREVENTION 1: Block ANY Devanagari — TTS output re-captured by Live Captions
        val devanagariCount = text.count { it.code in 0x0900..0x097F }
        if (devanagariCount > 0) {
            CaptionLogger.log(TAG, "SKIP: Devanagari detected (TTS loop guard)")
            return
        }

        // Skip very short texts — single words like "Time.", "Like," are not worth translating
        // They are usually mid-sentence LC accumulation artifacts
        val wordCount = text.trim().split(Regex("\\s+")).size
        if (wordCount < 2) {
            CaptionLogger.log(TAG, "SKIP: too short ($wordCount words)")
            return
        }
        // These words appear in LC when TTS Hindi speech is re-captured
        val lower = text.lowercase()
        val romanizedHindi = listOf(
            "sunkar","muskura","heto","hain","nahin","theek","aapko",
            "tumhe","kijiye","karein","chahiye","matlab","lekin",
            "parantu","isliye","kyunki","waise","raha","rahi","rahe",
            "bolta","bolti","kehta","kehti","sunta","sunti"
        )
        if (romanizedHindi.any { lower.contains(it) } && text.split(" ").size < 10) {
            CaptionLogger.log(TAG, "SKIP: romanized Hindi detected '${text.take(30)}'")
            return
        }

        // NOTE: isSuppressed() check removed — subtitle display is independent of TTS.
        // TTS loop is prevented by: (1) Devanagari guard above, (2) romanized Hindi guard,
        // (3) USAGE_ASSISTANT audio attribute (excluded from Live Captions capture).
        // Blocking subtitle on isSuppressed caused 4-6s display delay per sentence.

        val n = norm(text)
        if (n == lastEnqueued || n == norm(lastSentText)) {
            CaptionLogger.log(TAG, "SKIP dup")
            return
        }

        lastEnqueued = n
        val seq = seqCounter.incrementAndGet()

        if (queue.size >= QUEUE_CAP) {
            queue.poll()
            CaptionLogger.log(TAG, "CAP: dropped oldest")
        }

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

                val t0     = System.currentTimeMillis()
                lastSentText = text
                val result = callServer(text)
                val ms     = System.currentTimeMillis() - t0

                if (seq < expectedSeq) { CaptionLogger.log(TAG, "DISCARD $seq ${ms}ms"); continue }

                if (result == null) {
                    errCount.incrementAndGet()
                    CaptionLogger.log(TAG, "ERR ${ms}ms '${text.take(40)}'")
                    continue
                }

                val (hindi, serverLang) = result

                // Skip if same Hindi shown very recently
                val now   = System.currentTimeMillis()
                val hNorm = norm(hindi)
                if (hNorm == norm(lastHindiOut) && (now - lastHindiTime) < HINDI_DEDUP_MS) {
                    CaptionLogger.log(TAG, "SKIP dup Hindi")
                    lastEnqueued = ""; lastRawFull = ""
                    continue
                }
                lastHindiOut  = hindi
                lastHindiTime = now

                okCount.incrementAndGet()
                CaptionLogger.log(TAG, "OK $seq ${ms}ms lang=$serverLang '${hindi.take(50)}'")
                SpeechCaptureService.latestHindi   = hindi
                SpeechCaptureService.latestEnglish = text

                withContext(Dispatchers.Main) {
                    OverlayService.updateText(text, hindi)
                    MainActivity.instance?.onTranslation(text, hindi, hindi)
                }
                // Gender detection is audio-only (GenderAnalyzer.kt) — no pronoun detection
                HindiTtsService.speak(hindi)
            }
        }
    }

    private fun callServer(text: String): Pair<String, String>? {
        if (text.trim().length < 4) return null
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
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val hindi = json.optString("text", "").trim()
            val lang  = json.optString("detected_lang", confirmedLang)
            if (hindi.isBlank()) null else Pair(hindi, lang)
        } catch (e: Exception) {
            CaptionLogger.log(TAG, "server ex: ${e.javaClass.simpleName}: ${e.message}"); null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun resetAll() {
        lastEnqueued = ""; lastRawFull = ""; lastSentText = ""
        lastEnqueuedSents.clear()
        confirmedLang = ""; pendingLang = ""; pendingCount = 0
        lcVisible = false; expectedSeq = 0L
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
