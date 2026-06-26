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
    enum class Emotion {
        NEUTRAL, HAPPY, SAD, ANGRY, FEARFUL, SURPRISED, DISGUST,
        BREATHY, WHISPERY, HUSHED, MURMURED,
        VELVETY, SULTRY, WARM, TENDER,
        HUSKY, RASPY, GRAVELLY, STRAINED,
        SIGHING, PANTING, MOANING, GASPING,
        SINGING;   // sustained vocal performance

        val speedMult: Float get() = when (this) {
            HAPPY->1.12f; SAD->0.85f; ANGRY->1.05f; FEARFUL->0.95f
            SURPRISED->1.08f; DISGUST->0.90f
            BREATHY->0.90f; WHISPERY->0.85f; HUSHED->0.88f; MURMURED->0.82f
            VELVETY->0.92f; SULTRY->0.88f; WARM->0.95f; TENDER->0.90f
            HUSKY->0.93f; RASPY->1.00f; GRAVELLY->0.85f; STRAINED->1.10f
            SIGHING->0.80f; PANTING->1.20f; MOANING->0.75f; GASPING->1.15f
            SINGING->0.88f
            else->1.00f
        }
        val pitchMult: Float get() = when (this) {
            HAPPY->1.10f; SAD->0.92f; ANGRY->1.02f; FEARFUL->1.08f
            SURPRISED->1.12f; DISGUST->0.90f
            BREATHY->0.95f; WHISPERY->0.90f; HUSHED->0.93f; MURMURED->0.88f
            VELVETY->0.96f; SULTRY->0.88f; WARM->0.97f; TENDER->0.94f
            HUSKY->0.92f; RASPY->0.88f; GRAVELLY->0.85f; STRAINED->1.08f
            SIGHING->0.90f; PANTING->1.05f; MOANING->0.87f; GASPING->1.12f
            SINGING->1.00f  // neutral pitch — melody is in the content
            SINGING->1.00f
            else->1.00f
        }
        val category: String get() = when(this) {
            NEUTRAL,HAPPY,SAD,ANGRY,FEARFUL,SURPRISED,DISGUST -> "basic"
            BREATHY,WHISPERY,HUSHED,MURMURED -> "breathive"
            VELVETY,SULTRY,WARM,TENDER -> "warm"
            HUSKY,RASPY,GRAVELLY,STRAINED -> "intense"
            SIGHING,PANTING,MOANING,GASPING -> "rhythmic"
            SINGING -> "singing"
        }
    }

    @JvmField @Volatile var enabled           = false
    @JvmField @Volatile var selectedGender    = Gender.AUTO
    @Volatile var ttsSpeedMultiplier          = 1.5f
    @Volatile var detectedGender              = Gender.MALE
    // Set by GenderAnalyzer from audio analysis — drives TTS emotion + voice profile
    @Volatile var currentEmotion: Emotion     = Emotion.NEUTRAL
    @Volatile var isSpeaking                  = false
    @Volatile private var speakingUntilMs     = 0L

    private val scope       = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var cacheDir: java.io.File? = null
    private var am: AudioManager? = null
    private var focusRequest: android.media.AudioFocusRequest? = null

    // ── FIFO queues (unbounded — never drop sentences) ────────────────────────
    data class FetchItem(
        val text: String, val gender: String, val speed: Float,
        val volume: Float = 1.0f, val pitchSlope: String = "flat",
        val srcText: String = "", val emotion: Emotion = Emotion.NEUTRAL
    )
    data class PlayItem (val text: String, val wav: ByteArray, val durMs: Long)

    private val fetchQueue = LinkedBlockingQueue<FetchItem>()
    private val playQueue  = LinkedBlockingQueue<PlayItem>()

    // Token dedup — never re-speak same sentence
    @JvmField val spokenTokens = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()  // accessible from GenderAnalyzer

    private var fetchWorker: Job? = null
    private var playWorker:  Job? = null
    private var genderJob:   Job? = null

    // Gender history
    // genderHistory removed — pronoun detection removed entirely

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

    fun speak(hindi: String, srcText: String = "") {
        if (!enabled || hindi.isBlank()) return
        val n = hindi.trim().replace(Regex("\\s+"), " ")

        val token = n.hashCode()
        if (spokenTokens.putIfAbsent(token, true) != null) return
        if (spokenTokens.size > 300) spokenTokens.clear()

        // Read voice profile from GenderAnalyzer (set by audio analysis)
        val audioEmotion = currentEmotion
        val textEmotion  = detectTextEmotion(n)
        val emotion      = if (audioEmotion != Emotion.NEUTRAL) audioEmotion else textEmotion

        // Speed: emotion multiplier × user preference, capped
        val emotionSpeedMult = emotion.speedMult.coerceIn(0.75f, 1.15f)
        val speed = (emotionSpeedMult * ttsSpeedMultiplier).coerceIn(0.5f, 3.0f)

        // Read volume and pitch_slope from live voice profile
        // Emotion character is conveyed via Piper native params on the server
        val profile    = GenderAnalyzer.currentProfile
        val volume     = profile.volume.coerceIn(0.5f, 1.8f)
        val pitchSlope = profile.pitchSlope
        // Always store "auto" — gender resolved at fetch time so switches apply immediately
        // even for sentences already in queue
        val genderTag = when (selectedGender) {
            Gender.FEMALE -> "female"
            Gender.MALE   -> "male"
            Gender.AUTO   -> "auto"   // resolved in fetchWorker to catch late gender switches
        }

        // FIFO: never drop sentences — every sentence gets spoken in order
        // fetchQueue is unbounded LinkedBlockingQueue so memory is safe
        fetchQueue.offer(FetchItem(
            text=n, gender=genderTag, speed=speed,
            volume=volume, pitchSlope=pitchSlope,
            srcText=srcText, emotion=emotion
        ))
    }


    // updateGenderFromSource() removed — pronouns describe who is talked ABOUT,
    // not who is speaking. Gender detection is audio-only via GenderAnalyzer.kt.

    // ── Fetch worker (Piper HTTP → WAV) ───────────────────────────────────────

    private fun startFetchWorker() {
        fetchWorker = scope.launch {
            while (isActive) {
                val item = fetchQueue.take()   // blocks — never misses
                if (!enabled) continue
                try {
                    // Resolve gender at fetch time — captures latest GenderAnalyzer result
                    // ONLY GenderAnalyzer (audio pitch) sets detectedGender.
                    // Pronouns in source text NEVER change the voice — only verb forms.
                    val resolvedGender = if (item.gender == "auto")
                        if (detectedGender == Gender.FEMALE) "female" else "male"
                    else item.gender

                    // Verb form gender: audio gender takes priority.
                    // If audio says male but text has "she/her" → still use male voice
                    // but DO apply feminine verb conjugation for accuracy.
                    val verbGender = when {
                        resolvedGender == "female" -> "female"           // audio says female
                        hasFemininePronouns(item.srcText) -> "female"    // text hint only → verbs
                        else -> "male"
                    }
                    val textToSpeak = if (verbGender == "female")
                        toFeminineHindi(item.text) else item.text

                    val itemWithText = item.copy(text = textToSpeak)
                    val wav = fetchWav(itemWithText, resolvedGender)
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
                    // Duck background audio while TTS speaks
                    GenderAnalyzer.duckBackground()
                    playWav(item.wav, item.durMs)
                    // Restore background audio after TTS finishes
                    GenderAnalyzer.unduckBackground()
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

    private suspend fun fetchWav(item: FetchItem, resolvedGender: String): ByteArray? =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val enc = java.net.URLEncoder.encode(item.text, "UTF-8")
                // Send emotion NAME — server maps to Piper native params (noise_scale, noise_w)
                // No pitch shift, no noise injection — all artifact-free
                val url = "$TTS_URL?text=$enc&gender=$resolvedGender" +
                    "&speed=${String.format("%.3f", item.speed)}" +
                    "&emotion=${item.emotion.name}" +
                    "&volume=${String.format("%.3f", item.volume)}" +
                    "&pitch_slope=${item.pitchSlope}"
                CaptionLogger.log("HindiTTS", "[${item.emotion.name}/${item.emotion.category}] " +
                    "spd=${String.format("%.2f",item.speed)} " +
                    "vol=${String.format("%.2f",item.volume)} slope=${item.pitchSlope}")
                conn = URL(url).openConnection() as HttpURLConnection
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
                // MAY_DUCK: background music ducks to ~30% but keeps playing
                // EXCLUSIVE would silence everything — wrong for mimicry
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setWillPauseWhenDucked(false)   // don't pause, just duck
                .setAcceptsDelayedFocusGain(true) // smooth transition
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


    // ── Pronoun hint (verb forms only — does NOT affect voice switching) ──────
    //
    // Checks English source text for feminine pronouns.
    // Result is used ONLY for Hindi verb conjugation, never for voice selection.
    // Voice (male/female) is controlled exclusively by GenderAnalyzer audio pitch.

    private fun hasFemininePronouns(src: String): Boolean {
        if (src.isBlank()) return false
        val t = " ${src.lowercase()} "
        return t.contains(" she ") || t.contains(" her ") ||
               t.contains(" herself ") || t.contains(" she's ") ||
               t.contains(" she'd ") || t.contains(" she'll ")
    }

    // ── Feminine Hindi verb form conversion ──────────────────────────────────
    //
    // Converts masculine verb endings to feminine in Hindi text.
    // Applied when: (a) audio detects female voice, OR (b) source text has she/her pronouns.
    // Covers ि / ी / े / ैं suffix patterns in common verb+auxiliary combinations.

    private fun toFeminineHindi(text: String): String {
        var t = text

        // ── First person present ──────────────────────────────────────────────
        t = t.replace("ता हूँ", "ती हूँ")
        t = t.replace("ता हूं", "ती हूं")
        t = t.replace("रहा हूँ", "रही हूँ")
        t = t.replace("रहा हूं", "रही हूं")
        t = t.replace("सकता हूँ", "सकती हूँ")
        t = t.replace("सकता हूं", "सकती हूं")
        t = t.replace("चाहता हूँ", "चाहती हूँ")
        t = t.replace("चाहता हूं", "चाहती हूं")

        // ── Third person present ──────────────────────────────────────────────
        t = t.replace("ता है", "ती है")
        t = t.replace("रहा है", "रही है")
        t = t.replace("ता हैं", "ती हैं")
        t = t.replace("रहे हैं", "रही हैं")
        t = t.replace("सकता है", "सकती है")
        t = t.replace("चाहता है", "चाहती है")
        t = t.replace("लगता है", "लगती है")
        t = t.replace("होता है", "होती है")
        t = t.replace("मिलता है", "मिलती है")

        // ── Past tense ────────────────────────────────────────────────────────
        t = t.replace("ता था", "ती थी")
        t = t.replace("ते थे", "ती थीं")
        t = t.replace("रहा था", "रही थी")
        t = t.replace("सकता था", "सकती थी")
        t = t.replace("लगता था", "लगती थी")
        t = t.replace("होता था", "होती थी")
        t = t.replace("चाहता था", "चाहती थी")

        // ── Past participle ───────────────────────────────────────────────────
        t = t.replace("गया", "गई")
        t = t.replace("गए", "गईं")
        t = t.replace("आया", "आई")
        t = t.replace("आए", "आईं")
        t = t.replace("किया", "की")
        t = t.replace("लिया", "ली")
        t = t.replace("दिया", "दी")
        t = t.replace("पाया", "पाई")
        t = t.replace("पाए", "पाईं")
        t = t.replace("बताया", "बताई")
        t = t.replace("सुनाया", "सुनाई")
        t = t.replace("बनाया", "बनाई")

        // ── Continuous ────────────────────────────────────────────────────────
        t = t.replace("रहा हूँ", "रही हूँ")  // (already above, idempotent)
        t = t.replace("रहा हूं", "रही हूं")

        return t
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

    private fun detectTextEmotion(t: String): Emotion {
        val l = t.lowercase()
        if (t.contains("!!"))                                                              return Emotion.ANGRY
        if (t.endsWith("!") || t.endsWith("！"))                                          return Emotion.SURPRISED
        if (listOf("उदास","दुखी","sad","sorry","cry","रोना").any { l.contains(it) })   return Emotion.SAD
        if (listOf("गुस्सा","angry","hate","damn","क्रोध").any    { l.contains(it) })   return Emotion.ANGRY
        if (listOf("डर","afraid","scared","fearful","घबरा").any   { l.contains(it) })   return Emotion.FEARFUL
        if (listOf("वाह","wow","खुश","happy","great","love").any  { l.contains(it) })   return Emotion.HAPPY
        if (listOf("warm","tender","soft","प्यार","gentle").any   { l.contains(it) })   return Emotion.TENDER
        return Emotion.NEUTRAL
    }



    private fun readInt(b: ByteArray, o: Int) =
        ((b[o+3].toInt() and 0xFF) shl 24) or ((b[o+2].toInt() and 0xFF) shl 16) or
        ((b[o+1].toInt() and 0xFF) shl  8) or  (b[o].toInt() and 0xFF)

    private fun readShort(b: ByteArray, o: Int) =
        ((b[o+1].toInt() and 0xFF) shl 8) or (b[o].toInt() and 0xFF)
}
