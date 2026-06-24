package com.example.nihongolens

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

/**
 * HindiTtsService — Piper TTS via hindi_tts_server.py on port 8766
 *
 * Architecture:
 *   speak(text) → fetchQueue (unbounded FIFO)
 *   Fetch worker: text → WAV from Piper (~200-400ms at 1.5x speed)
 *   Play worker:  WAV → AudioTrack (USAGE_ASSISTANT, excluded from LC capture)
 *
 * Loop prevention:
 *   AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE: pauses video while TTS plays
 *   → LC has no video audio to caption → loop impossible
 *   Token dedup: same sentence never spoken twice
 *   isSuppressed(): blocks LiveCaptionReader.enqueue() during TTS + grace
 *
 * Gender:
 *   Polls whisper_server :8765/gender (real audio FFT from video)
 *   Passes gender=male/female to Piper server
 *   Female: pitch shifted +35% in numpy on server side (no extra model)
 *
 * Female/Male voices come from same hi_IN-rohan model — female is
 * pitch-shifted up. Not a separate model but clearly distinguishable.
 */
object HindiTtsService {

    private const val TAG      = "HindiTTS"
    private const val TTS_URL  = "http://127.0.0.1:8766/tts"
    private const val GENDER_URL = "http://127.0.0.1:8765/gender"

    enum class Gender  { AUTO, MALE, FEMALE }
    enum class Emotion { NEUTRAL, HAPPY, SAD, ANGRY, EXCITED, CURIOUS }

    @JvmField @Volatile var enabled           = false
    @JvmField @Volatile var selectedGender    = Gender.AUTO
    @Volatile var ttsSpeedMultiplier          = 1.5f
    @Volatile var detectedGender              = Gender.MALE
    @Volatile var isSpeaking                  = false
    @Volatile private var speakingUntilMs     = 0L

    private val scope       = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var cacheDir: java.io.File? = null
    private var am: AudioManager? = null
    private var focusRequest: android.media.AudioFocusRequest? = null

    // ── FIFO queues (unbounded — never drop sentences) ────────────────────────
    data class FetchItem(val text: String, val gender: String, val speed: Float)
    data class PlayItem (val text: String, val wav: ByteArray, val durMs: Long)

    private val fetchQueue = LinkedBlockingQueue<FetchItem>()
    private val playQueue  = LinkedBlockingQueue<PlayItem>()

    // Token dedup — never re-speak same sentence
    private val spokenTokens = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

    private var fetchWorker: Job? = null
    private var playWorker:  Job? = null
    private var genderJob:   Job? = null

    // Gender history
    private val genderHistory = ArrayDeque<Gender>()
    private val GENDER_HIST   = 5

    // ── Init / lifecycle ──────────────────────────────────────────────────────

    fun init(context: Context) {
        cacheDir = context.cacheDir
        am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // Prevent LC from capturing this app's audio
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            am?.allowedCapturePolicy = AudioAttributes.ALLOW_CAPTURE_BY_NONE
        }
        startFetchWorker()
        startPlayWorker()
        startGenderPoller()
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) {
            fetchQueue.clear(); playQueue.clear()
            stopAudio(); releaseAudioFocus()
            spokenTokens.clear()
        }
    }

    fun setGender(g: Gender) { selectedGender = g }

    fun setSpeedMultiplier(m: Float) { ttsSpeedMultiplier = m.coerceIn(0.5f, 4.0f) }

    fun isSuppressed() = isSpeaking || System.currentTimeMillis() < speakingUntilMs

    fun stopAndClear() {
        fetchQueue.clear(); playQueue.clear()
        stopAudio(); releaseAudioFocus()
        isSpeaking = false
        speakingUntilMs = System.currentTimeMillis() + 2_000L
        spokenTokens.clear()
        Log.d(TAG, "Stopped (LC silent)")
    }

    fun destroy() {
        genderJob?.cancel(); fetchWorker?.cancel(); playWorker?.cancel()
        fetchQueue.clear(); playQueue.clear()
        stopAudio(); releaseAudioFocus()
        scope.cancel()
    }

    // ── Speak ─────────────────────────────────────────────────────────────────

    fun speak(hindi: String) {
        if (!enabled || hindi.isBlank()) return
        val n = hindi.trim().replace(Regex("\\s+"), " ")

        // Token dedup
        val token = n.hashCode()
        if (spokenTokens.putIfAbsent(token, true) != null) return
        if (spokenTokens.size > 300) spokenTokens.clear()

        val emotion = detectEmotion(n)
        val speed   = (emotionSpeed(emotion) * ttsSpeedMultiplier).coerceIn(0.5f, 4.0f)
        val gender  = when (selectedGender) {
            Gender.FEMALE -> "female"
            Gender.MALE   -> "male"
            Gender.AUTO   -> if (detectedGender == Gender.FEMALE) "female" else "male"
        }
        fetchQueue.offer(FetchItem(n, gender, speed))
    }

    // ── Fetch worker (Piper HTTP → WAV) ───────────────────────────────────────

    private fun startFetchWorker() {
        fetchWorker = scope.launch {
            while (isActive) {
                val item = fetchQueue.take()   // blocks — never misses
                if (!enabled) continue
                try {
                    val wav = fetchWav(item.text, item.gender, item.speed)
                    if (wav != null && wav.size > 44) {
                        val sr  = readInt(wav, 24).coerceAtLeast(8_000)
                        val nch = readShort(wav, 22).coerceAtLeast(1)
                        val bit = readShort(wav, 34).coerceAtLeast(8)
                        val dur = ((wav.size - 44).toLong() * 1000) / (sr.toLong() * nch * (bit / 8))
                        playQueue.offer(PlayItem(item.text, wav, dur))
                    } else {
                        Log.w(TAG, "Empty WAV — is hindi_tts_server.py running? Start: python3 ~/hindi_tts_server.py &")
                    }
                } catch (e: Exception) { Log.e(TAG, "Fetch: ${e.message}") }
            }
        }
    }

    // ── Play worker (AudioTrack — excluded from LC capture) ───────────────────

    private fun startPlayWorker() {
        playWorker = scope.launch {
            while (isActive) {
                val item = playQueue.take()   // blocks — no gaps
                if (!enabled) continue
                try {
                    isSpeaking = true
                    requestAudioFocus()
                    // Show subtitle exactly when TTS starts speaking
                    withContext(Dispatchers.Main) {
                        OverlayService.showTtsText(item.text)
                    }
                    playWav(item.wav, item.durMs)
                    // Clear subtitle when TTS finishes
                    withContext(Dispatchers.Main) {
                        OverlayService.clearTtsText()
                    }
                    releaseAudioFocus()
                    speakingUntilMs = System.currentTimeMillis() + 800L
                } catch (e: Exception) {
                    Log.e(TAG, "Play: ${e.message}")
                    releaseAudioFocus()
                } finally {
                    isSpeaking = false
                }
            }
        }
    }

    // ── HTTP fetch ────────────────────────────────────────────────────────────

    private suspend fun fetchWav(text: String, gender: String, speed: Float): ByteArray? =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val enc = java.net.URLEncoder.encode(text, "UTF-8")
                conn = URL("$TTS_URL?text=$enc&gender=$gender&speed=$speed")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 5_000
                conn.readTimeout    = 20_000
                if (conn.responseCode == 200) conn.inputStream.readBytes() else null
            } catch (e: Exception) { null }
            finally { try { conn?.disconnect() } catch (_: Exception) {} }
        }

    // ── AudioTrack playback (USAGE_ASSISTANT = excluded from LC) ─────────────

    private var audioTrack: AudioTrack? = null

    private fun stopAudio() {
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
    }

    private suspend fun playWav(wav: ByteArray, durMs: Long) = withContext(Dispatchers.IO) {
        try {
            val sr   = readInt(wav, 24).coerceAtLeast(8_000)
            val nch  = readShort(wav, 22)
            val bit  = readShort(wav, 34)
            val pcm  = wav.copyOfRange(44, wav.size)
            val fmt  = if (bit == 16) AudioFormat.ENCODING_PCM_16BIT else AudioFormat.ENCODING_PCM_8BIT
            val chan = if (nch == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBuf = AudioTrack.getMinBufferSize(sr, chan, fmt).coerceAtLeast(pcm.size)

            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    // USAGE_ASSISTANT: system services (including Live Captions)
                    // cannot capture audio with this usage type
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(sr).setChannelMask(chan).setEncoding(fmt).build())
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            stopAudio()
            audioTrack = track
            track.write(pcm, 0, pcm.size)
            track.setVolume(AudioTrack.getMaxVolume())
            track.play()

            // Wait for playback to finish
            delay(durMs + 200L)
            track.stop(); track.release()
            audioTrack = null
        } catch (e: Exception) { Log.e(TAG, "playWav: ${e.message}") }
    }

    // ── Audio focus (pauses video while TTS plays) ────────────────────────────

    private fun requestAudioFocus() {
        val manager = am ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val req = android.media.AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener {}.build()
            focusRequest = req
            manager.requestAudioFocus(req)
        }
    }

    private fun releaseAudioFocus() {
        val req = focusRequest ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            am?.abandonAudioFocusRequest(req)
        }
        focusRequest = null
    }

    // ── Gender poller ─────────────────────────────────────────────────────────

    private fun startGenderPoller() {
        genderJob = scope.launch {
            Log.d(TAG, "Gender detector starting — REMOTE_SUBMIX internal audio")
            while (isActive) {
                if (selectedGender != Gender.AUTO || isSuppressed()) {
                    delay(2_000); continue
                }
                try {
                    sampleAndDetectGender()
                } catch (e: Exception) {
                    Log.w(TAG, "Gender sample: ${e.message}")
                }
                delay(2_000)
            }
        }
    }

    private suspend fun sampleAndDetectGender() = withContext(Dispatchers.IO) {
        val SR      = 16_000
        val SAMPLES = SR * 2   // 2s
        val minBuf  = AudioRecord.getMinBufferSize(
            SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(SAMPLES * 2)

        // REMOTE_SUBMIX (100) captures internal system audio — same as Live Captions
        // This is what screen recorders use on Android 10+
        // Fallback to VOICE_RECOGNITION (mic) if REMOTE_SUBMIX unavailable
        val sources = listOf(100, MediaRecorder.AudioSource.VOICE_RECOGNITION,
                             MediaRecorder.AudioSource.MIC)
        var rec: AudioRecord? = null
        for (src in sources) {
            try {
                val r = AudioRecord(src, SR, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, minBuf)
                if (r.state == AudioRecord.STATE_INITIALIZED) {
                    rec = r
                    Log.d(TAG, "Gender AudioRecord src=$src")
                    break
                }
                r.release()
            } catch (_: Exception) {}
        }
        val record = rec ?: return@withContext

        val buf = ShortArray(SAMPLES)
        try {
            record.startRecording()
            var read = 0
            while (read < SAMPLES) {
                val n = record.read(buf, read, SAMPLES - read)
                if (n <= 0) break
                read += n
            }
            record.stop()
        } finally {
            try { record.release() } catch (_: Exception) {}
        }

        if (isSuppressed()) return@withContext  // check again after recording

        // RMS check
        var sq = 0.0
        for (i in buf.indices) sq += buf[i].toLong() * buf[i]
        val rms = kotlin.math.sqrt(sq / buf.size)
        if (rms < 20) return@withContext  // silence

        // Convert to bytes and POST to whisper_server for FFT analysis
        val pcm = ByteArray(buf.size * 2)
        for (i in buf.indices) {
            pcm[i*2]   = (buf[i].toInt() and 0xFF).toByte()
            pcm[i*2+1] = (buf[i].toInt() shr 8 and 0xFF).toByte()
        }

        val conn = URL("http://127.0.0.1:8765/analyze_audio")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 2_000
        conn.readTimeout = 3_000
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        conn.outputStream.write(pcm)

        if (conn.responseCode == 200) {
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val g    = json.optString("gender", "")
            val conf = json.optInt("confidence", 0)
            conn.disconnect()

            if (g == "female" || g == "male") {
                val newG = if (g == "female") Gender.FEMALE else Gender.MALE
                genderHistory.addLast(newG)
                if (genderHistory.size > GENDER_HIST) genderHistory.removeFirst()
                val fCount = genderHistory.count { it == Gender.FEMALE }
                val majority = if (fCount > genderHistory.size / 2)
                    Gender.FEMALE else Gender.MALE
                if (majority != detectedGender) {
                    detectedGender = majority
                    Log.d(TAG, "Gender switched → $majority rms=${rms.toInt()} g=$g conf=$conf")
                }
            }
        } else conn.disconnect()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun detectEmotion(t: String): Emotion {
        if (t.endsWith("!") || t.endsWith("！")) return Emotion.EXCITED
        if (t.endsWith("?") || t.endsWith("？")) return Emotion.CURIOUS
        val l = t.lowercase()
        if (listOf("दुखी","उदास","sad","sorry","cry").any { l.contains(it) }) return Emotion.SAD
        if (listOf("गुस्सा","angry","hate","damn").any  { l.contains(it) }) return Emotion.ANGRY
        if (listOf("वाह","wow","खुश","love","great").any { l.contains(it) }) return Emotion.HAPPY
        return Emotion.NEUTRAL
    }

    private fun emotionSpeed(e: Emotion) = when (e) {
        Emotion.EXCITED -> 1.10f; Emotion.HAPPY -> 1.05f
        Emotion.CURIOUS -> 0.97f; Emotion.SAD   -> 0.88f
        Emotion.ANGRY   -> 1.08f; else          -> 1.00f
    }

    private fun readInt(b: ByteArray, o: Int) =
        ((b[o+3].toInt() and 0xFF) shl 24) or ((b[o+2].toInt() and 0xFF) shl 16) or
        ((b[o+1].toInt() and 0xFF) shl  8) or  (b[o].toInt() and 0xFF)

    private fun readShort(b: ByteArray, o: Int) =
        ((b[o+1].toInt() and 0xFF) shl 8) or (b[o].toInt() and 0xFF)
}
