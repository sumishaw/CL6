package com.example.nihongolens

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.sqrt

/**
 * HindiTtsService
 *
 * Architecture:
 *   sherpa-onnx TTS server runs in Termux on port 8766
 *   (uses AI4Bharat Hindi VITS model — native Indian accent, offline)
 *
 *   Pipeline:
 *     Hindi text → emotion detection (keywords + punctuation)
 *                → gender detection (audio pitch ZCR)
 *                → sherpa-onnx HTTP request with speaker_id + speed
 *                → WAV bytes → AudioTrack playback
 *
 *   Emotion detection (text-based, instant, no ML needed):
 *     ! at end        → excited  (faster speed, higher pitch)
 *     ? at end        → curious  (slight pitch rise)
 *     sad keywords    → sad      (slower, lower pitch)
 *     angry keywords  → angry    (faster, louder)
 *     happy keywords  → happy    (moderate fast, higher pitch)
 *     default         → neutral
 *
 *   Gender:
 *     AUTO → ZCR pitch detection from mic
 *     MALE   → speaker_id 0 (AI4Bharat male Hindi voice)
 *     FEMALE → speaker_id 1 (AI4Bharat female Hindi voice)
 */
object HindiTtsService {

    private const val TAG      = "HindiTTS"
    private const val TTS_URL  = "http://127.0.0.1:8766/tts"

    enum class Gender  { AUTO, MALE, FEMALE }
    enum class Emotion { NEUTRAL, HAPPY, SAD, ANGRY, EXCITED, CURIOUS }

    // Use JvmField to suppress auto-generated getter/setter
    // which would clash with our explicit setEnabled/setGender functions
    @JvmField @Volatile var enabled        = false
    @JvmField @Volatile var selectedGender = Gender.AUTO

    @Volatile private var detectedGender    = Gender.MALE
    @Volatile var isSpeaking                = false   // public — read by LiveCaptionReader
    @Volatile private var speakingUntilMs   = 0L      // grace period after speech ends

    private val scope        = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pitchDetector: PitchDetector? = null
    private var workerJob:   Job? = null

    // FIFO queue — holds pending TTS texts, max 3 (keep current)
    private val ttsQueue = java.util.concurrent.LinkedBlockingQueue<Pair<String, Float>>(3)

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Public API ────────────────────────────────────────────────────────────

    fun init(context: Context) {
        pitchDetector = PitchDetector { gender -> detectedGender = gender }
        startWorker()
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        if (on && selectedGender == Gender.AUTO) pitchDetector?.start()
        else if (!on) {
            pitchDetector?.stop()
            ttsQueue.clear()
            stopMediaPlayer()
        }
    }

    fun setGender(gender: Gender) {
        selectedGender = gender
        if (gender == Gender.AUTO) pitchDetector?.start() else pitchDetector?.stop()
    }

    fun speak(hindiText: String) {
        if (!enabled || hindiText.isBlank()) return
        val emotion    = detectEmotion(hindiText)
        val (speed, _) = emotionParams(emotion)
        // Drop oldest if queue full — keep audio current with subtitles
        if (ttsQueue.size >= 3) ttsQueue.poll()
        ttsQueue.offer(Pair(hindiText, speed))
    }

    fun destroy() {
        pitchDetector?.stop()
        workerJob?.cancel()
        ttsQueue.clear()
        stopMediaPlayer()
        scope.cancel()
    }

    // ── Worker — processes queue sequentially ─────────────────────────────────

    private fun startWorker() {
        workerJob = scope.launch {
            while (isActive) {
                val (text, speed) = ttsQueue.poll(2, java.util.concurrent.TimeUnit.SECONDS)
                    ?: continue
                if (!enabled) continue
                try {
                    isSpeaking = true
                    val wavBytes = requestTts(text, 0, speed)
                    if (wavBytes != null && wavBytes.size > 44) {
                        playWav(wavBytes)
                        // Grace period: LC may have buffered TTS audio for 1-2s after playback ends
                        // Block enqueue during this window to prevent loop tail
                        speakingUntilMs = System.currentTimeMillis() + 2_000L
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Worker error: ${e.message}")
                } finally {
                    isSpeaking = false
                }
            }
        }
    }

    // Called by LiveCaptionReader to check if it should suppress enqueue
    fun isSuppressed(): Boolean {
        if (isSpeaking) return true
        if (System.currentTimeMillis() < speakingUntilMs) return true
        return false
    }

    // ── Emotion detection ─────────────────────────────────────────────────────

    private fun detectEmotion(text: String): Emotion {
        val t = text.trim()

        // Punctuation-based
        if (t.endsWith("!") || t.endsWith("！")) return Emotion.EXCITED
        if (t.endsWith("?") || t.endsWith("？")) return Emotion.CURIOUS

        val lower = t.lowercase()

        // Hindi emotion keywords
        val sadWords    = listOf("दुखी","रो","मर","मृत्यु","दर्द","तकलीफ","उदास","बेचारा","गम","आँसू","रोया","बुरा")
        val angryWords  = listOf("गुस्सा","क्रोध","चिल्लाया","मारो","हत्या","नफरत","बेकार","कमीना","बर्बाद","धोखा","झूठ")
        val happyWords  = listOf("खुश","मजा","शुक्रिया","धन्यवाद","प्यार","अच्छा","बढ़िया","हँसी","जीत","खुशी","शादी","बधाई")
        val exciteWords = listOf("वाह","अरे","हाँ","जीत","कमाल","अद्भुत","शानदार","जबरदस्त","लाजवाब")

        // Also check English words that LC may have left in
        val sadEn    = listOf("sad","cry","death","pain","sorry","miss","alone","hurt","lost")
        val angryEn  = listOf("angry","hate","kill","stupid","idiot","damn","liar","cheat")
        val happyEn  = listOf("happy","love","great","wonderful","thanks","yes","win","yay")
        val exciteEn = listOf("wow","amazing","awesome","incredible","fantastic","brilliant")

        if (sadWords.any { lower.contains(it) }    || sadEn.any { lower.contains(it) })
            return Emotion.SAD
        if (angryWords.any { lower.contains(it) }  || angryEn.any { lower.contains(it) })
            return Emotion.ANGRY
        if (exciteWords.any { lower.contains(it) } || exciteEn.any { lower.contains(it) })
            return Emotion.EXCITED
        if (happyWords.any { lower.contains(it) }  || happyEn.any { lower.contains(it) })
            return Emotion.HAPPY

        return Emotion.NEUTRAL
    }

    /** Returns (speed, pitch_note) per emotion */
    private fun emotionParams(emotion: Emotion): Pair<Float, String> = when (emotion) {
        Emotion.EXCITED -> Pair(1.25f, "high")
        Emotion.HAPPY   -> Pair(1.10f, "high")
        Emotion.CURIOUS -> Pair(0.95f, "rise")
        Emotion.SAD     -> Pair(0.75f, "low")
        Emotion.ANGRY   -> Pair(1.20f, "harsh")
        Emotion.NEUTRAL -> Pair(1.00f, "normal")
    }

    // ── sherpa-onnx HTTP request ───────────────────────────────────────────────

    private suspend fun requestTts(text: String, speakerId: Int, speed: Float): ByteArray? =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                // Build URL: GET /tts?text=...&speaker_id=0&speed=1.0
                val encoded  = java.net.URLEncoder.encode(text, "UTF-8")
                val urlStr   = "$TTS_URL?text=$encoded&speaker_id=$speakerId&speed=$speed"
                conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.requestMethod  = "GET"
                conn.connectTimeout = 5_000
                conn.readTimeout    = 20_000
                if (conn.responseCode == 200) {
                    conn.inputStream.readBytes()
                } else {
                    Log.e(TAG, "TTS server HTTP ${conn.responseCode}")
                    null
                }
            } catch (e: Exception) {
                null  // server not running — silent
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }

    // ── WAV playback ──────────────────────────────────────────────────────────

    private var mediaPlayer: android.media.MediaPlayer? = null

    private fun stopMediaPlayer() {
        mainHandler.post {
            try { mediaPlayer?.stop() } catch (_: Exception) {}
            try { mediaPlayer?.release() } catch (_: Exception) {}
            mediaPlayer = null
        }
    }

    private suspend fun playWav(wavBytes: ByteArray) {
        val latch = java.util.concurrent.CountDownLatch(1)

        // Determine pitch for gender
        // Male=1.0 (natural), Female=1.3 (higher pitch), AUTO=detected
        val effectiveGender = if (selectedGender == Gender.AUTO) detectedGender else selectedGender
        val pitchShift = when (effectiveGender) {
            Gender.FEMALE -> 1.30f
            Gender.MALE   -> 1.00f
            Gender.AUTO   -> if (detectedGender == Gender.FEMALE) 1.30f else 1.00f
        }

        withContext(Dispatchers.Main) {
            try {
                mediaPlayer?.let { try { it.release() } catch (_: Exception) {} }
                mediaPlayer = null

                val mp = android.media.MediaPlayer()
                mp.setAudioAttributes(AudioAttributes.Builder()
                    // USAGE_ASSISTANT: excluded from Live Captions capture
                    // Live Captions only captures USAGE_MEDIA and USAGE_GAME
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                mp.setVolume(1.0f, 1.0f)

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    mp.setDataSource(ByteArrayMediaDataSource(wavBytes))
                } else {
                    val tmp = java.io.File("/data/local/tmp/tts_hindi.wav")
                    tmp.parentFile?.mkdirs()
                    tmp.writeBytes(wavBytes)
                    mp.setDataSource(tmp.absolutePath)
                }

                mp.setOnCompletionListener {
                    try { it.release() } catch (_: Exception) {}
                    mediaPlayer = null
                    latch.countDown()
                }
                mp.setOnErrorListener { it, what, extra ->
                    Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                    try { it.release() } catch (_: Exception) {}
                    mediaPlayer = null
                    latch.countDown()
                    true
                }

                mp.prepare()

                // Apply pitch for gender (Android 6+)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    mp.playbackParams = mp.playbackParams.setPitch(pitchShift)
                }

                mp.start()
                mediaPlayer = mp
                Log.d(TAG, "TTS playing gender=$effectiveGender pitch=$pitchShift dur=${mp.duration}ms")
            } catch (e: Exception) {
                Log.e(TAG, "playWav setup: ${e.message}")
                latch.countDown()
            }
        }
        withContext(Dispatchers.IO) {
            latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    fun stopCurrent() {
        ttsQueue.clear()
        stopMediaPlayer()
        isSpeaking = false
    }
}

// ── PitchDetector ─────────────────────────────────────────────────────────────

class PitchDetector(private val onGender: (HindiTtsService.Gender) -> Unit) {

    private val scope   = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    private val SAMPLE_RATE  = 16_000
    private val BUFFER_FRAMES = 1_600   // 100ms
    private val FEMALE_ZCR   = 0.045f
    private val HISTORY      = 10

    private val history = ArrayDeque<HindiTtsService.Gender>()

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            var rec: AudioRecord? = null
            try {
                rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, BUFFER_FRAMES * 2))
                if (rec.state != AudioRecord.STATE_INITIALIZED) return@launch
                rec.startRecording()
                val buf = ShortArray(BUFFER_FRAMES)
                while (isActive) {
                    val read = rec.read(buf, 0, BUFFER_FRAMES)
                    if (read <= 0) { delay(50); continue }

                    // Skip pitch analysis while TTS is speaking — we'd detect our own voice
                    if (HindiTtsService.isSuppressed()) { delay(100); continue }
                    val rms = sqrt(buf.take(read).sumOf { it.toLong() * it }.toDouble() / read)
                    if (rms < 200) { delay(20); continue }
                    var zc = 0
                    for (i in 1 until read) if ((buf[i] >= 0) != (buf[i-1] >= 0)) zc++
                    val zcr = zc.toFloat() / read
                    val g = if (zcr > FEMALE_ZCR) HindiTtsService.Gender.FEMALE
                            else HindiTtsService.Gender.MALE
                    history.addLast(g)
                    if (history.size > HISTORY) history.removeFirst()
                    val fc = history.count { it == HindiTtsService.Gender.FEMALE }
                    withContext(Dispatchers.Main) {
                        onGender(if (fc > HISTORY/2) HindiTtsService.Gender.FEMALE
                                 else HindiTtsService.Gender.MALE)
                    }
                    delay(100)
                }
            } finally {
                try { rec?.stop(); rec?.release() } catch (_: Exception) {}
            }
        }
    }

    fun stop() { job?.cancel(); job = null; history.clear() }
}

// Allows MediaPlayer to read WAV from memory — no temp file conflicts
@androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.M)
class ByteArrayMediaDataSource(private val data: ByteArray) :
    android.media.MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= data.size) return -1
        val end = minOf(position + size, data.size.toLong()).toInt()
        val len = (end - position.toInt()).coerceAtLeast(0)
        System.arraycopy(data, position.toInt(), buffer, offset, len)
        return len
    }
    override fun getSize() = data.size.toLong()
    override fun close() {}
}
