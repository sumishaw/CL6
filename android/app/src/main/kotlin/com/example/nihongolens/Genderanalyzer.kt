package com.example.nihongolens

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlin.math.*
import java.io.RandomAccessFile
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * GenderAnalyzer v10 — Full Voice Profile extraction from USAGE_MEDIA internal audio.
 *
 * Extracts per-frame acoustic features and maintains a smoothed VoiceProfile
 * that is used by HindiTtsService to replicate the original speaker's voice
 * characteristics in the Hindi TTS output.
 *
 * VOICE PROFILE PARAMETERS (all continuously updated):
 *   speed       — speaking pace ratio vs baseline (0.6–1.8)
 *   pitch       — F0 ratio vs speaker baseline (0.75–1.35)
 *   volume      — normalised RMS energy (0.4–2.0)
 *   breathiness — air leakage through cords; 1−HNR (0=clear, 1=breathy)
 *   roughness   — frame-to-frame F0 jitter (0=smooth, 1=very rough)
 *   pitchSlope  — intonation direction: "rising", "flat", "falling"
 *   emotion     — one of 23 voice types detected from the above features
 */
object GenderAnalyzer {

    private const val TAG           = "GenderAnalyzer"
    private const val SR            = 16_000
    private const val WIN           = 2048        // 128 ms analysis window
    private const val F0_VOICE_MIN  = 85f         // below = music bass, not voice
    private const val F0_FEMALE_MIN = 165f         // male: 85–164 Hz, female: 165–400 Hz
    private const val F0_VOICE_MAX  = 400f         // above = noise/harmonics
    private const val YIN_THRESH    = 0.22f
    private const val RMS_FLOOR     = 80f
    private const val HIST          = 3            // 2/3 majority to switch gender
    private const val PROFILE_SMOOTH = 8          // frames to smooth voice profile

    // ── VoiceProfile ──────────────────────────────────────────────────────────
    /**
     * Continuously updated snapshot of the speaker's voice characteristics.
     * Smoothed over PROFILE_SMOOTH frames to avoid jitter.
     * Read by HindiTtsService.speak() at enqueue time.
     */
    data class VoiceProfile(
        val speed:       Float  = 1.00f,  // 0.6–1.8   speaking pace
        val pitch:       Float  = 1.00f,  // 0.75–1.35 F0 ratio vs baseline
        val volume:      Float  = 1.00f,  // 0.4–2.0   normalised energy
        val breathiness: Float  = 0.20f,  // 0–1       1-HNR (breathy = high)
        val roughness:   Float  = 0.05f,  // 0–1       F0 jitter (rough = high)
        val pitchSlope:  String = "flat", // "rising" | "flat" | "falling"
        val emotion:     HindiTtsService.Emotion = HindiTtsService.Emotion.NEUTRAL
    )

    @Volatile var enabled        = false
    @Volatile var lastStatus     = "waiting for screen capture permission"
    @Volatile var currentProfile = VoiceProfile()

    private val genderHistory  = ArrayDeque<HindiTtsService.Gender>()
    private val emotionHistory = ArrayDeque<HindiTtsService.Emotion>()
    private val accum          = ShortArray(WIN)
    private var accumFill      = 0

    // Smoothing buffers for each profile dimension
    private val speedBuf       = FloatArray(PROFILE_SMOOTH) { 1.0f }
    private val pitchBuf       = FloatArray(PROFILE_SMOOTH) { 1.0f }
    private val volumeBuf      = FloatArray(PROFILE_SMOOTH) { 1.0f }
    private val breathBuf      = FloatArray(PROFILE_SMOOTH) { 0.2f }
    private val roughBuf       = FloatArray(PROFILE_SMOOTH) { 0.05f }
    private var bufIdx         = 0

    // Tracking state
    private var prevF0          = 0f
    private val f0Ring          = FloatArray(8)   // recent F0 values for jitter
    private var f0RingIdx       = 0
    private var sustainedFrames = 0    // consecutive stable-F0 frames (singing detection)
    private var lastStableF0    = 0f   // F0 of current sustained note
    private var maleF0Base   = 0f    // male F0 baseline (85–164Hz range)
    private var femaleF0Base = 0f    // female F0 baseline (165–400Hz range)
    private var frameCount     = 0
    private var analyzeCount   = 0

    // Silence tracking for pace estimation
    private var lastVoicedMs   = 0L
    private var silenceRatio   = 0.5f

    private val scope          = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var captureJob:    Job?         = null
    private var captureRec:    AudioRecord? = null

    // ── Background audio shared file ─────────────────────────────────────────
    // Captured USAGE_MEDIA PCM written to a circular file on disk.
    // TTS server reads this file directly at synthesis time — zero latency.
    // File: bg_pcm.raw in app files dir — 3s circular buffer (96KB)
    private var bgDucked       = false
    private var bgFile:        RandomAccessFile? = null
    private val BG_FILE_BYTES  = 16000 * 2 * 3   // 3s × 16kHz × 2 bytes/sample = 96000
    private val bgWritePos     = AtomicLong(0L)   // circular write position

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start(projection: MediaProjection? = null) {
        if (enabled) return
        if (projection == null) {
            lastStatus = "no projection — grant screen capture permission"
            CaptionLogger.log(TAG, "start() — no projection"); return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            lastStatus = "API < Q"; return
        }
        stop()
        captureJob = scope.launch { captureLoop(projection) }
    }

    fun stop() {
        enabled = false
        captureJob?.cancel(); captureJob = null
        try { bgFile?.close() } catch (_: Exception) {}; bgFile = null
        try { captureRec?.stop()    } catch (_: Exception) {}
        try { captureRec?.release() } catch (_: Exception) {}
        captureRec = null
        genderHistory.clear(); emotionHistory.clear(); sustainedFrames = 0; lastStableF0 = 0f
        accumFill = 0; maleF0Base = 0f; femaleF0Base = 0f
        if (lastStatus != "waiting for screen capture permission")
            CaptionLogger.log(TAG, "stopped")
    }

    // ── USAGE_MEDIA capture loop ──────────────────────────────────────────────

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun captureLoop(projection: MediaProjection) = withContext(Dispatchers.IO) {
        val config = try {
            AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .build()
        } catch (e: Exception) {
            enabled = false; lastStatus = "config failed: ${e.message}"
            CaptionLogger.log(TAG, "config: ${e.message}"); return@withContext
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(WIN * 4)

        val rec = try {
            AudioRecord.Builder()
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SR)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build())
                .setBufferSizeInBytes(minBuf)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (e: Exception) {
            enabled = false; lastStatus = "AudioRecord failed: ${e.message}"
            CaptionLogger.log(TAG, "AudioRecord: ${e.message}"); return@withContext
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            enabled = false; rec.release()
            lastStatus = "AudioRecord state=${rec.state}"; return@withContext
        }

        captureRec = rec; enabled = true
        lastStatus = "capturing USAGE_MEDIA SR=${SR}Hz"
        rec.startRecording()
        CaptionLogger.log(TAG, ">>> VOICE PROFILE CAPTURE STARTED SR=${SR}Hz <<<")

        // Open circular PCM file for TTS server to read BG audio from
        try {
            val filesDir = android.app.ActivityThread.currentApplication()
                ?.filesDir?.absolutePath ?: "/data/data/com.example.nihongolens/files"
            val bgPath = "$filesDir/bg_pcm.raw"
            bgFile = RandomAccessFile(bgPath, "rw")
            bgFile?.setLength(BG_FILE_BYTES.toLong())  // pre-allocate 96KB circular buffer
            // Write path to a companion file so server knows where to find it
            File("$filesDir/bg_pcm.path").writeText(bgPath)
            CaptionLogger.log(TAG, "BG PCM file: $bgPath (${BG_FILE_BYTES/1024}KB circular)")
        } catch (e: Exception) {
            CaptionLogger.log(TAG, "BG file init failed: ${e.message}")
            bgFile = null
        }



        val buf = ByteArray(WIN * 2); var reads = 0
        try {
            while (currentCoroutineContext().isActive && enabled) {
                val n = rec.read(buf, 0, buf.size)
                when {
                    n > 0 -> {
                        reads++
                        if (reads == 1) CaptionLogger.log(TAG, "FIRST read — media audio flowing!")
                        // Write to circular PCM file — TTS server reads this for BG mixing
                        bgFile?.let { f ->
                            try {
                                val pos = (bgWritePos.getAndAdd(n.toLong())) % BG_FILE_BYTES
                                f.seek(pos)
                                val space = (BG_FILE_BYTES - pos).toInt()
                                if (n <= space) {
                                    f.write(buf, 0, n)
                                } else {
                                    f.write(buf, 0, space)
                                    f.seek(0)
                                    f.write(buf, space, n - space)
                                }
                            } catch (_: Exception) {}
                        }
                        ingest(buf, n)
                    }
                    n < 0 -> { CaptionLogger.log(TAG, "read error=$n"); break }
                }
            }
        } finally {
            try { rec.stop(); rec.release() } catch (_: Exception) {}
            try { bgFile?.close() } catch (_: Exception) {}; bgFile = null
            captureRec = null; enabled = false
        }
    }

    // ── PCM ingestion ─────────────────────────────────────────────────────────

    private fun ingest(bytes: ByteArray, count: Int) {
        var i = 0
        while (i + 1 < count) {
            val lo = bytes[i].toInt() and 0xFF
            val hi = bytes[i + 1].toInt() and 0xFF
            accum[accumFill++] = ((hi shl 8) or lo).toShort()
            i += 2
            if (accumFill >= WIN) { analyze(); accumFill = 0 }
        }
    }

    // ── Feature extraction ────────────────────────────────────────────────────

    private fun analyze() {
        analyzeCount++
        val now = System.currentTimeMillis()

        // RMS (volume)
        var energy = 0.0
        for (s in accum) energy += s.toLong() * s
        val rms = sqrt(energy / WIN).toFloat()
        val rmsNorm = (rms / 3000f).coerceIn(0.1f, 3.0f)

        // Silence / pace tracking
        if (rms < RMS_FLOOR) {
            val silMs = if (lastVoicedMs > 0) (now - lastVoicedMs) else 0L
            // Long silence = end of phrase → pace feels slower
            silenceRatio = (silenceRatio * 0.9f + (if (silMs > 400) 1f else 0f) * 0.1f)
                .coerceIn(0f, 1f)
            prevF0 = 0f
            return
        }
        lastVoicedMs = now

        // YIN pitch detection
        val tauMin = (SR / 300).coerceAtLeast(1)
        val tauMax = (SR / 60).coerceAtMost(WIN / 2 - 1)
        val half   = WIN / 2
        val d = FloatArray(tauMax + 1)
        for (tau in 1..tauMax) {
            var s = 0f
            for (j in 0 until half) {
                val diff = accum[j].toFloat() / 32768f - accum[j + tau].toFloat() / 32768f
                s += diff * diff
            }
            d[tau] = s
        }
        val c = FloatArray(tauMax + 1); c[0] = 1f; var rs = 0f
        for (tau in 1..tauMax) { rs += d[tau]; c[tau] = if (rs > 0f) d[tau] * tau / rs else 1f }

        var minCmndf = 1f; var tau = tauMin
        while (tau < tauMax - 1) {
            if (c[tau] < minCmndf) minCmndf = c[tau]
            if (c[tau] < YIN_THRESH) {
                val best = if (tau + 1 < tauMax && c[tau + 1] < c[tau]) tau + 1 else tau
                onVoicedFrame(SR.toFloat() / best, rmsNorm, 1f - minCmndf, now)
                return
            }
            tau++
        }
        // Unvoiced voiced frame — still update volume smoothing
        updateSmoothing(null, rmsNorm, 0f, 0f)
    }

    // ── Per-voiced-frame processing ───────────────────────────────────────────

    private fun onVoicedFrame(f0: Float, rmsNorm: Float, hnr: Float, nowMs: Long) {
        frameCount++

        // Voice range gate — ignore music bass and noise
        if (f0 < F0_VOICE_MIN || f0 > F0_VOICE_MAX) {
            if (frameCount % 20 == 0)
                CaptionLogger.log(TAG, "IGNORE F0=${f0.toInt()}Hz outside ${F0_VOICE_MIN.toInt()}–${F0_VOICE_MAX.toInt()}Hz")
            return
        }

        // ── GENDER ────────────────────────────────────────────────────────────
        val gender = if (f0 >= F0_FEMALE_MIN) HindiTtsService.Gender.FEMALE
                     else                      HindiTtsService.Gender.MALE
        genderHistory.addLast(gender)
        if (genderHistory.size > HIST) genderHistory.removeFirst()
        val maj = if (genderHistory.count { it == HindiTtsService.Gender.FEMALE } > genderHistory.size / 2)
            HindiTtsService.Gender.FEMALE else HindiTtsService.Gender.MALE
        if (maj != HindiTtsService.detectedGender) {
            HindiTtsService.detectedGender = maj
            HindiTtsService.spokenTokens.clear()
            CaptionLogger.log(TAG, ">>> Gender SWITCHED to $maj F0=${f0.toInt()}Hz <<<")
        }

        // ── PITCH RATIO vs speaker baseline ───────────────────────────────────
        // Track per-gender F0 baseline via exponential moving average
        val isFemale = f0 >= F0_FEMALE_MIN
        if (isFemale) femaleF0Base = if (femaleF0Base == 0f) f0 else femaleF0Base * 0.97f + f0 * 0.03f
        else          maleF0Base   = if (maleF0Base   == 0f) f0 else maleF0Base   * 0.97f + f0 * 0.03f
        val base       = if (isFemale) femaleF0Base else maleF0Base
        val pitchRatio = if (base > 0f) (f0 / base).coerceIn(0.85f, 1.18f) else 1.0f

        // ── F0 SLOPE (intonation) ─────────────────────────────────────────────
        val f0Slope = if (prevF0 > 0f) (f0 - prevF0) / prevF0 else 0f
        prevF0 = f0
        val pitchSlope = when {
            f0Slope >  0.06f -> "rising"
            f0Slope < -0.06f -> "falling"
            else             -> "flat"
        }

        // ── F0 JITTER (roughness) ─────────────────────────────────────────────
        f0Ring[f0RingIdx % f0Ring.size] = f0; f0RingIdx++

        // Sustained note detection: count frames where F0 stays within ±5%
        val f0Diff = if (lastStableF0 > 0f) kotlin.math.abs(f0 - lastStableF0) / lastStableF0 else 1f
        if (f0Diff < 0.05f) {
            sustainedFrames++
        } else {
            sustainedFrames = 0
            lastStableF0 = f0
        }
        val validF0  = f0Ring.filter { it > 0f }
        val f0Mean   = if (validF0.isEmpty()) f0 else validF0.average().toFloat()
        val roughness = if (validF0.size < 2) 0.05f else
            (validF0.map { abs(it - f0Mean) }.average().toFloat() / f0Mean.coerceAtLeast(1f))
                .coerceIn(0f, 1f)

        // ── BREATHINESS (1 - HNR) ─────────────────────────────────────────────
        val breathiness = (1f - hnr).coerceIn(0f, 1f)

        // ── SPEAKING PACE ─────────────────────────────────────────────────────
        // Pace = inverse of silence ratio + F0 slope activity
        // High silence ratio = slower speaker; low = faster
        val pace = (1.0f - silenceRatio * 0.15f).coerceIn(0.90f, 1.10f)

        // ── UPDATE SMOOTHING BUFFERS ──────────────────────────────────────────
        updateSmoothing(pitchRatio, rmsNorm, breathiness, roughness)
        val idx = bufIdx % PROFILE_SMOOTH
        speedBuf[idx] = pace
        bufIdx++

        // ── EMOTION DETECTION ─────────────────────────────────────────────────
        val emotion: HindiTtsService.Emotion = when {
            // SINGING: sustained note — very harmonic (high HNR), stable F0 (low jitter),
            // held for 5+ consecutive analysis frames (~640ms)
            hnr > 0.75f && roughness < 0.04f && rmsNorm in 0.3f..2.0f
                && sustainedFrames >= 5 ->
                HindiTtsService.Emotion.SINGING

            f0Slope > 0.20f && rmsNorm > 2.0f && roughness > 0.18f     -> HindiTtsService.Emotion.GASPING
            roughness > 0.22f && rmsNorm > 1.8f && f0 > f0Mean * 1.02f -> HindiTtsService.Emotion.PANTING
            f0 < f0Mean * 0.85f && roughness < 0.06f && rmsNorm in 0.3f..1.0f && hnr > 0.4f -> HindiTtsService.Emotion.MOANING
            f0Slope < -0.12f && rmsNorm < 0.6f && hnr > 0.3f           -> HindiTtsService.Emotion.SIGHING
            f0 > f0Mean * 1.12f && rmsNorm > 1.3f && roughness > 0.10f && hnr < 0.5f -> HindiTtsService.Emotion.STRAINED
            f0 < f0Mean * 0.80f && hnr < 0.30f && roughness > 0.10f    -> HindiTtsService.Emotion.GRAVELLY
            hnr < 0.35f && rmsNorm > 1.0f && roughness > 0.08f         -> HindiTtsService.Emotion.RASPY
            hnr < 0.45f && rmsNorm in 0.7f..1.5f && roughness in 0.05f..0.12f -> HindiTtsService.Emotion.HUSKY
            f0Slope > 0.15f && rmsNorm > 0.8f                          -> HindiTtsService.Emotion.SURPRISED
            rmsNorm > 1.4f && hnr < 0.5f                               -> HindiTtsService.Emotion.ANGRY
            f0 > f0Mean * 1.05f && roughness > 0.12f                   -> HindiTtsService.Emotion.FEARFUL
            rmsNorm < 0.25f && hnr < 0.25f                             -> HindiTtsService.Emotion.WHISPERY
            f0 < f0Mean * 0.88f && rmsNorm < 0.4f && hnr < 0.4f       -> HindiTtsService.Emotion.MURMURED
            rmsNorm < 0.35f && hnr < 0.40f && roughness < 0.06f        -> HindiTtsService.Emotion.HUSHED
            hnr < 0.40f && rmsNorm in 0.2f..0.8f && roughness < 0.07f -> HindiTtsService.Emotion.BREATHY
            f0 < f0Mean * 0.92f && hnr > 0.65f && roughness < 0.05f && rmsNorm < 0.9f -> HindiTtsService.Emotion.SULTRY
            rmsNorm < 0.45f && hnr > 0.60f && roughness < 0.05f        -> HindiTtsService.Emotion.TENDER
            f0 < f0Mean * 0.97f && hnr > 0.70f && roughness < 0.04f   -> HindiTtsService.Emotion.VELVETY
            hnr > 0.65f && roughness < 0.05f && rmsNorm in 0.4f..1.1f -> HindiTtsService.Emotion.WARM
            f0 > f0Mean * 1.08f && roughness < 0.08f && rmsNorm > 0.6f && hnr > 0.6f -> HindiTtsService.Emotion.HAPPY
            f0 < f0Mean * 0.93f && abs(f0Slope) < 0.05f && rmsNorm < 0.7f -> HindiTtsService.Emotion.SAD
            f0Slope < -0.10f && rmsNorm < 0.9f && hnr < 0.45f         -> HindiTtsService.Emotion.DISGUST
            else                                                         -> HindiTtsService.Emotion.NEUTRAL
        }

        emotionHistory.addLast(emotion)
        if (emotionHistory.size > 3) emotionHistory.removeFirst()  // 3-frame window = 384ms
        val smoothedEmotion = emotionHistory.groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.key ?: HindiTtsService.Emotion.NEUTRAL

        // ── PUBLISH VOICE PROFILE ─────────────────────────────────────────────
        val smoothSpeed  = speedBuf.average().toFloat().coerceIn(0.6f, 1.8f)
        val smoothPitch  = pitchBuf.average().toFloat().coerceIn(0.75f, 1.35f)
        val smoothVol    = volumeBuf.average().toFloat().coerceIn(0.4f, 2.0f)
        val smoothBreath = breathBuf.average().toFloat().coerceIn(0f, 1f)
        val smoothRough  = roughBuf.average().toFloat().coerceIn(0f, 1f)

        val newProfile = VoiceProfile(
            speed       = smoothSpeed,
            pitch       = smoothPitch,
            volume      = smoothVol,
            breathiness = smoothBreath,
            roughness   = smoothRough,
            pitchSlope  = pitchSlope,
            emotion     = smoothedEmotion
        )

        if (newProfile != currentProfile) {
            currentProfile = newProfile
            HindiTtsService.currentEmotion = smoothedEmotion
        }

        if (frameCount % 8 == 0)
            CaptionLogger.log(TAG, "PROFILE F0=${f0.toInt()}Hz|${maj} " +
                "spd=${"%.2f".format(smoothSpeed)} pch=${"%.2f".format(smoothPitch)} " +
                "vol=${"%.2f".format(smoothVol)} breath=${"%.2f".format(smoothBreath)} " +
                "rough=${"%.3f".format(smoothRough)} slope=$pitchSlope " +
                "emo=${smoothedEmotion.name}")
    }

    private fun updateSmoothing(pitch: Float?, vol: Float, breath: Float, rough: Float) {
        val i = bufIdx % PROFILE_SMOOTH
        if (pitch != null) pitchBuf[i] = pitch
        volumeBuf[i] = vol
        breathBuf[i] = breath
        roughBuf[i]  = rough
    }

    private fun Float.format() = String.format("%.2f", this)

    // ── Background audio streaming ───────────────────────────────────────────

            conn.responseCode         // trigger send
            conn.disconnect()
    // ── Background audio (Android handles mixing automatically) ──────────────
    // USAGE_MEDIA (video) and USAGE_ASSISTANT (TTS) are mixed by Android AudioManager.
    // No manual ducking needed — AudioFocus system handles volume balance.
    // These are kept as no-ops to avoid changing call sites in HindiTtsService.

    fun duckBackground()   { /* Android AudioFocus handles ducking automatically */ }
    fun unduckBackground() { /* Android AudioFocus handles restoration automatically */ }
}
