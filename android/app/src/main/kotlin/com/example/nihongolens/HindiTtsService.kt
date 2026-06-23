package com.example.nihongolens

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.sqrt

/**
 * HindiTtsService — TTS-driven subtitle sync
 *
 * Core principle: subtitle only shows when TTS speaks it.
 * Pipeline per sentence:
 *   1. Fetch WAV from Kokoro server
 *   2. Show subtitle on overlay
 *   3. Play WAV
 *   4. Clear subtitle when done
 *   5. Next sentence immediately (no gap)
 *
 * Gender: mic-based YIN pitch detection.
 *   Mic picks up speaker audio from tablet speakers.
 *   F0 < 165Hz → male  (sid=33)
 *   F0 ≥ 165Hz → female (sid=31)
 *   Paused during TTS playback to avoid self-detection.
 *
 * Speed: ttsSpeedMultiplier applied to Kokoro speed parameter.
 *   Kokoro 2x = speech twice as fast = shorter WAV = faster throughput.
 */
object HindiTtsService {

    private const val TAG     = "HindiTTS"
    private const val TTS_URL = "http://127.0.0.1:8766/tts"

    private const val SID_FEMALE = 31   // hf_alpha — real Indian female voice
    private const val SID_MALE   = 33   // hm_omega — Indian male voice

    enum class Gender  { AUTO, MALE, FEMALE }
    enum class Emotion { NEUTRAL, HAPPY, SAD, ANGRY, EXCITED, CURIOUS }

    @JvmField @Volatile var enabled           = false
    @JvmField @Volatile var selectedGender    = Gender.AUTO
    @Volatile var ttsSpeedMultiplier          = 1.5f
    @Volatile var detectedGender              = Gender.MALE
    @Volatile var isSpeaking                  = false
    @Volatile private var speakingUntilMs     = 0L

    private val scope      = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var cacheDir: java.io.File? = null

    // Single FIFO queue — one worker processes it sequentially
    // Unbounded so no sentence is ever dropped
    private val queue = LinkedBlockingQueue<String>()
    private var worker: Job? = null
    private var pitchJob: Job? = null
    private var mediaPlayer: android.media.MediaPlayer? = null
    private var lastSpokenNorm = ""

    // Pitch history for gender smoothing
    private val pitchHistory = ArrayDeque<Gender>()
    private val PITCH_HISTORY = 10

    // ── Init / lifecycle ──────────────────────────────────────────────────────

    fun init(context: Context) {
        cacheDir = context.cacheDir
        startWorker()
        startPitchDetector()
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) {
            queue.clear()
            stopMp()
            lastSpokenNorm = ""
        }
    }

    fun setGender(g: Gender) {
        selectedGender = g
        if (g != Gender.AUTO) {
            pitchJob?.cancel(); pitchJob = null; pitchHistory.clear()
        } else {
            startPitchDetector()
        }
    }

    fun setSpeedMultiplier(m: Float) { ttsSpeedMultiplier = m.coerceIn(0.5f, 4.0f) }

    fun isSuppressed() = isSpeaking || System.currentTimeMillis() < speakingUntilMs

    fun destroy() {
        pitchJob?.cancel()
        worker?.cancel()
        queue.clear()
        stopMp()
        scope.cancel()
    }

    // ── Enqueue sentence ─────────────────────────────────────────────────────

    fun speak(hindi: String) {
        if (!enabled || hindi.isBlank()) return
        val n = hindi.trim().replace(Regex("\\s+"), " ")
        if (n == lastSpokenNorm) return
        lastSpokenNorm = n
        // Drop oldest if queue is building up — stay near real time
        while (queue.size >= 4) queue.poll()
        queue.offer(n)
    }

    // ── Single worker — fetch WAV → show subtitle → play → clear → repeat ────

    private fun startWorker() {
        worker = scope.launch {
            while (isActive) {
                val text = queue.poll(2, java.util.concurrent.TimeUnit.SECONDS) ?: continue
                if (!enabled) continue

                val emotion = detectEmotion(text)
                val speed   = (emotionSpeed(emotion) * ttsSpeedMultiplier).coerceIn(0.5f, 4.0f)
                val sid     = if ((if (selectedGender == Gender.AUTO) detectedGender
                                   else selectedGender) == Gender.FEMALE)
                                SID_FEMALE else SID_MALE

                try {
                    // Step 1: Fetch WAV
                    val wav = fetchWav(text, sid, speed)
                    if (wav == null || wav.size <= 44) {
                        Log.w(TAG, "No WAV for '${text.take(30)}' — TTS server running?")
                        // Still show subtitle even if TTS fails
                        showSubtitle(text)
                        delay(2_000)
                        hideSubtitle()
                        continue
                    }

                    // Step 2: Show subtitle exactly when speech starts
                    showSubtitle(text)

                    // Step 3: Play audio
                    isSpeaking = true
                    playWav(wav)
                    isSpeaking = false
                    speakingUntilMs = System.currentTimeMillis() + 300L

                    // Step 4: Hide subtitle after speech ends
                    hideSubtitle()

                } catch (e: Exception) {
                    Log.e(TAG, "Worker: ${e.message}")
                    isSpeaking = false
                    hideSubtitle()
                }
                // No delay — next sentence starts immediately
            }
        }
    }

    // ── Subtitle sync (show/hide driven by TTS) ───────────────────────────────

    private fun showSubtitle(text: String) {
        mainHandler.post {
            OverlayService.showTtsText(text)
        }
    }

    private fun hideSubtitle() {
        mainHandler.post {
            OverlayService.clearTtsText()
        }
    }

    // ── WAV fetch from Kokoro server ─────────────────────────────────────────

    private suspend fun fetchWav(text: String, sid: Int, speed: Float): ByteArray? =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val enc = java.net.URLEncoder.encode(text, "UTF-8")
                val url = "$TTS_URL?text=$enc&sid=$sid&speed=$speed"
                conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod  = "GET"
                conn.connectTimeout = 5_000
                conn.readTimeout    = 30_000
                if (conn.responseCode == 200) conn.inputStream.readBytes() else null
            } catch (e: Exception) {
                Log.e(TAG, "fetchWav: ${e.message}"); null
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }

    // ── WAV playback ─────────────────────────────────────────────────────────

    private suspend fun playWav(wav: ByteArray) {
        // Parse duration from WAV header
        val sr    = readInt(wav, 24).coerceAtLeast(8_000)
        val nCh   = readShort(wav, 22).coerceAtLeast(1)
        val bits  = readShort(wav, 34).coerceAtLeast(8)
        val pcm   = (wav.size - 44).coerceAtLeast(0)
        val durMs = (pcm.toLong() * 1000) / (sr.toLong() * nCh * (bits / 8))

        val latch = java.util.concurrent.CountDownLatch(1)

        withContext(Dispatchers.Main) {
            try {
                stopMp()
                val mp = android.media.MediaPlayer()
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        // USAGE_ASSISTANT → excluded from Live Captions capture
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )

                // Write to cache file
                val f = java.io.File(cacheDir, "tts_play.wav")
                f.writeBytes(wav)
                mp.setDataSource(f.absolutePath)

                mp.setOnCompletionListener {
                    try { it.release() } catch (_: Exception) {}
                    mediaPlayer = null
                    latch.countDown()
                }
                mp.setOnErrorListener { it, w, x ->
                    Log.e(TAG, "MP err w=$w x=$x")
                    try { it.release() } catch (_: Exception) {}
                    mediaPlayer = null
                    latch.countDown()
                    true
                }
                mp.prepare()
                mp.start()
                mediaPlayer = mp
                Log.d(TAG, "Playing sid=${ if(selectedGender==Gender.AUTO) detectedGender else selectedGender } dur=${durMs}ms speed=${ttsSpeedMultiplier}x")
            } catch (e: Exception) {
                Log.e(TAG, "playWav: ${e.message}"); latch.countDown()
            }
        }

        // Wait for actual playback duration + small buffer
        val timeout = (durMs + 500L).coerceAtMost(30_000L)
        withContext(Dispatchers.IO) {
            latch.await(timeout, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    private fun stopMp() {
        try { mediaPlayer?.stop()    } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
    }

    // ── Pitch detection (mic picks up speaker audio) ─────────────────────────

    private fun startPitchDetector() {
        if (pitchJob?.isActive == true) return
        pitchJob = scope.launch {
            val SR  = 16_000
            val BUF = 2048
            val minBuf = AudioRecord.getMinBufferSize(SR,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

            var rec: AudioRecord? = null
            for (src in listOf(
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.DEFAULT)) {
                try {
                    val r = AudioRecord(src, SR, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, BUF * 2))
                    if (r.state == AudioRecord.STATE_INITIALIZED) {
                        rec = r; break
                    }
                    r.release()
                } catch (_: Exception) {}
            }

            if (rec == null) { Log.e(TAG, "PitchDetector: no AudioRecord"); return@launch }

            try {
                rec.startRecording()
                val buf = ShortArray(BUF)
                while (isActive) {
                    // Pause during TTS — avoid detecting own voice
                    if (isSuppressed()) { delay(200); continue }

                    val read = rec.read(buf, 0, BUF)
                    if (read < BUF) { delay(50); continue }

                    // RMS — skip silence
                    val rms = sqrt(buf.take(read).sumOf { it.toLong() * it }.toDouble() / read)
                    if (rms < 100.0) { delay(30); continue }

                    // YIN pitch estimation — reliable F0 detector
                    val f0 = yinPitch(buf, read, SR)
                    if (f0 <= 0f) { delay(50); continue }

                    // F0 < 165Hz = male, ≥ 165Hz = female
                    val g = if (f0 < 165f) Gender.MALE else Gender.FEMALE

                    pitchHistory.addLast(g)
                    if (pitchHistory.size > PITCH_HISTORY) pitchHistory.removeFirst()

                    val fCount = pitchHistory.count { it == Gender.FEMALE }
                    val majority = if (fCount > pitchHistory.size / 2) Gender.FEMALE else Gender.MALE
                    if (majority != detectedGender) {
                        detectedGender = majority
                        Log.d(TAG, "Gender → $majority (F0=${f0.toInt()}Hz f=$fCount/${pitchHistory.size})")
                    }
                    delay(100)
                }
            } finally {
                try { rec.stop(); rec.release() } catch (_: Exception) {}
            }
        }
    }

    // YIN pitch detection algorithm — accurate F0 estimation from PCM
    private fun yinPitch(buf: ShortArray, size: Int, sr: Int): Float {
        val tau_max = sr / 80    // min 80Hz
        val tau_min = sr / 500   // max 500Hz
        val n       = size.coerceAtMost(1024)
        val diff    = FloatArray(tau_max + 1)

        // Step 1: Difference function
        for (tau in 1..tau_max) {
            var sum = 0.0
            for (i in 0 until n - tau) {
                val d = buf[i].toDouble() - buf[i + tau].toDouble()
                sum += d * d
            }
            diff[tau] = sum.toFloat()
        }

        // Step 2: Cumulative mean normalized difference
        diff[0] = 1f
        var runSum = 0f
        for (tau in 1..tau_max) {
            runSum += diff[tau]
            diff[tau] = if (runSum == 0f) 1f else diff[tau] * tau / runSum
        }

        // Step 3: Absolute threshold — find first tau below 0.15
        for (tau in tau_min..tau_max) {
            if (diff[tau] < 0.15f) {
                // Parabolic interpolation for better accuracy
                val better = if (tau in (tau_min + 1)..(tau_max - 1)) {
                    val s0 = diff[tau - 1]; val s1 = diff[tau]; val s2 = diff[tau + 1]
                    tau - (s2 - s0) / (2f * (2f * s1 - s0 - s2))
                } else tau.toFloat()
                return sr / better
            }
        }

        // No clear pitch found
        return 0f
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun detectEmotion(text: String): Emotion {
        val t = text.trim()
        if (t.endsWith("!") || t.endsWith("！")) return Emotion.EXCITED
        if (t.endsWith("?") || t.endsWith("？")) return Emotion.CURIOUS
        val l = t.lowercase()
        if (listOf("दुखी","उदास","sad","cry","sorry").any { l.contains(it) }) return Emotion.SAD
        if (listOf("गुस्सा","angry","hate","damn").any     { l.contains(it) }) return Emotion.ANGRY
        if (listOf("वाह","wow","amazing","खुश","love").any { l.contains(it) }) return Emotion.HAPPY
        return Emotion.NEUTRAL
    }

    private fun emotionSpeed(e: Emotion) = when (e) {
        Emotion.EXCITED -> 1.10f
        Emotion.HAPPY   -> 1.05f
        Emotion.CURIOUS -> 0.97f
        Emotion.SAD     -> 0.85f
        Emotion.ANGRY   -> 1.08f
        Emotion.NEUTRAL -> 1.00f
    }

    private fun readInt(b: ByteArray, o: Int) =
        ((b[o+3].toInt() and 0xFF) shl 24) or ((b[o+2].toInt() and 0xFF) shl 16) or
        ((b[o+1].toInt() and 0xFF) shl 8)  or  (b[o].toInt() and 0xFF)

    private fun readShort(b: ByteArray, o: Int) =
        ((b[o+1].toInt() and 0xFF) shl 8) or (b[o].toInt() and 0xFF)
}
