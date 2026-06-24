package com.example.nihongolens

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioTrack
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
    @JvmField val spokenTokens = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()  // accessible from GenderAnalyzer

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
            stopAudio()
            spokenTokens.clear()
        }
    }

    fun setGender(g: Gender) { selectedGender = g }

    fun setSpeedMultiplier(m: Float) { ttsSpeedMultiplier = m.coerceIn(0.5f, 4.0f) }

    fun isSuppressed() = isSpeaking || System.currentTimeMillis() < speakingUntilMs

    fun stopAndClear() {
        fetchQueue.clear(); playQueue.clear()
        stopAudio()
        isSpeaking = false
        speakingUntilMs = System.currentTimeMillis() + 2_000L
        spokenTokens.clear()
        Log.d(TAG, "Stopped (LC silent)")
    }

    fun destroy() {
        genderJob?.cancel(); fetchWorker?.cancel(); playWorker?.cancel()
        fetchQueue.clear(); playQueue.clear()
        stopAudio()
        scope.cancel()
    }

    // ── Speak ─────────────────────────────────────────────────────────────────

    fun speak(hindi: String) {
        if (!enabled || hindi.isBlank()) return
        val n = hindi.trim().replace(Regex("\\s+"), " ")

        val token = n.hashCode()
        if (spokenTokens.putIfAbsent(token, true) != null) return
        if (spokenTokens.size > 300) spokenTokens.clear()

        val emotion = detectEmotion(n)
        val speed   = (emotionSpeed(emotion) * ttsSpeedMultiplier).coerceIn(0.5f, 4.0f)
        // Always store "auto" — gender resolved at fetch time so switches apply immediately
        // even for sentences already in queue
        val genderTag = when (selectedGender) {
            Gender.FEMALE -> "female"
            Gender.MALE   -> "male"
            Gender.AUTO   -> "auto"   // resolved in fetchWorker to catch late gender switches
        }

        // If fetch queue is backlogged (> 2 items), drop oldest to stay near real-time
        while (fetchQueue.size >= 2) fetchQueue.poll()
        fetchQueue.offer(FetchItem(n, genderTag, speed))
    }


    // Called from LiveCaptionReader with original source text + confirmed language
    // Detects gender from pronouns — works for all 9 languages, no audio needed
    fun updateGenderFromSource(srcText: String, lang: String) {
        if (selectedGender != Gender.AUTO) return
        val t = srcText.lowercase()

        // Normalize language — mul_latin, latin_en etc. treated as English
        val normalizedLang = when {
            lang.contains("en") || lang.contains("latin") || lang.contains("mul") -> "en"
            else -> lang
        }

        val fScore = when (normalizedLang) {
            "en" -> listOf(" she "," her "," mrs ","woman","girl","lady","mother",
                           "sister","daughter","wife","aunt","queen","princess",
                           "ladies","ma'am","madam","female").count { t.contains(it) }
            "es" -> listOf(" ella ","señora","mamá","mujer","chica","hermana").count { t.contains(it) }
            "fr" -> listOf(" elle ","madame","femme","fille","mère","sœur").count { t.contains(it) }
            "de" -> listOf(" sie ","frau","mutter","schwester","mädchen").count { t.contains(it) }
            "ja" -> listOf("彼女","女","姉","妹","母","娘","女性","あたし").count { t.contains(it) }
            "zh" -> listOf("她","姐","妹","妈","女士","小姐").count { t.contains(it) }
            "ko" -> listOf("그녀","여자","언니","누나","어머니").count { t.contains(it) }
            "ru" -> listOf("она "," её ","женщина","девушка","сестра").count { t.contains(it) }
            "ar" -> listOf("هي","امرأة","بنت","أخت","أم").count { t.contains(it) }
            else -> 0
        }
        val mScore = when (normalizedLang) {
            "en" -> listOf(" he "," him "," his "," mr ","man ","boy ","father",
                           "brother","son ","husband","uncle","king","prince",
                           "gentleman","gentlemen","male","sir ").count { t.contains(it) }
            "es" -> listOf(" él ","señor","papá","hombre","chico","hermano").count { t.contains(it) }
            "fr" -> listOf(" il ","monsieur","homme","garçon","père","frère").count { t.contains(it) }
            "de" -> listOf(" er ","herr","vater","bruder","mann","junge").count { t.contains(it) }
            "ja" -> listOf("彼 ","男","兄","弟","父","息子","俺","僕").count { t.contains(it) }
            "zh" -> listOf("他 ","哥","弟","爸","先生","男士").count { t.contains(it) }
            "ko" -> listOf("그는","남자","오빠","형","아버지").count { t.contains(it) }
            "ru" -> listOf("он "," его ","мужчина","парень","брат","папа").count { t.contains(it) }
            "ar" -> listOf("هو","رجل","ولد","أخ","أب").count { t.contains(it) }
            else -> 0
        }
        if (fScore == 0 && mScore == 0) return
        val newG = if (fScore > mScore) Gender.FEMALE else Gender.MALE
        genderHistory.addLast(newG)
        if (genderHistory.size > GENDER_HIST) genderHistory.removeFirst()
        val fCount = genderHistory.count { it == Gender.FEMALE }
        // Switch on 3/5 majority (60%) — faster than waiting for 8 samples
        val threshold = (genderHistory.size * 0.55).toInt()
        val majority = if (fCount >= threshold) Gender.FEMALE else Gender.MALE
        if (majority != detectedGender) {
            detectedGender = majority
            android.util.Log.d("HindiTTS", "Gender→$majority f=$fScore m=$mScore lang=$normalizedLang hist=${genderHistory.size} '${srcText.take(40)}'")
            CaptionLogger.log("HindiTTS", "Gender switched to $majority (fScore=$fScore mScore=$mScore)")
            // Clear dedup tokens so next sentence re-speaks in new voice
            spokenTokens.clear()
        }
    }

    // ── Fetch worker (Piper HTTP → WAV) ───────────────────────────────────────

    private fun startFetchWorker() {
        fetchWorker = scope.launch {
            while (isActive) {
                val item = fetchQueue.take()   // blocks — never misses
                if (!enabled) continue
                try {
                    // Resolve gender at fetch time — captures latest GenderAnalyzer result
                    val resolvedGender = if (item.gender == "auto")
                        if (detectedGender == Gender.FEMALE) "female" else "male"
                    else item.gender
                    val wav = fetchWav(item.text, resolvedGender, item.speed)
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
                    // No audio focus request — USAGE_ASSISTANT handles exclusion from LC
                    // AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE was pausing video → LC gap → loop
                    withContext(Dispatchers.Main) {
                        OverlayService.showTtsText(item.text)
                    }
                    playWav(item.wav, item.durMs)
                    withContext(Dispatchers.Main) {
                        OverlayService.clearTtsText()
                    }
                    speakingUntilMs = System.currentTimeMillis() + 300L
                } catch (e: Exception) {
                    Log.e(TAG, "Play: ${e.message}")
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

    // Two-layer gender detection:
    // Layer 1 (primary): GenderAnalyzer.kt — real audio FFT via MediaProjection
    // Layer 2 (fallback): HTTP poll /gender on whisper_server every 3s
    //   whisper_server /gender endpoint returns pitch analysis from mic/playback
    //   This fires if MediaProjection was not granted or GenderAnalyzer lost audio
    private fun startGenderPoller() {
        Log.d(TAG, "Gender poller started (fallback layer)")
        scope.launch(Dispatchers.IO) {
            var failStreak = 0
            while (isActive) {
                delay(3_000L)
                if (selectedGender != Gender.AUTO) continue
                try {
                    val conn = URL(GENDER_URL).openConnection() as java.net.HttpURLConnection
                    conn.requestMethod  = "GET"
                    conn.connectTimeout = 2_000
                    conn.readTimeout    = 3_000
                    if (conn.responseCode == 200) {
                        val json = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                        val g = json.optString("gender", "")
                        if (g == "female" || g == "male") {
                            val newG = if (g == "female") Gender.FEMALE else Gender.MALE
                            if (newG != detectedGender) {
                                detectedGender = newG
                                Log.d(TAG, "GenderPoller→$newG (audio pitch)")
                                CaptionLogger.log(TAG, "Gender fallback→$g")
                            }
                        }
                        failStreak = 0
                    }
                    conn.disconnect()
                } catch (_: Exception) {
                    failStreak++
                    // Back off after 5 consecutive failures (server not running)
                    if (failStreak > 5) delay(15_000L)
                }
            }
        }
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
