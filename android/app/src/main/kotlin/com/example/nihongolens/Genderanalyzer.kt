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

/**
 * GenderAnalyzer v9 — Gender + Emotion detection from USAGE_MEDIA internal audio.
 *
 * GENDER: YIN pitch → F0 < 165Hz = MALE, F0 ≥ 165Hz = FEMALE
 *
 * EMOTION: Rule-based classifier using 5 acoustic features per 128ms window:
 *   F0_mean       — average fundamental frequency (pitch)
 *   F0_slope      — pitch contour direction (rising/falling)
 *   F0_jitter     — frame-to-frame F0 variability (voice steadiness)
 *   RMS           — signal energy (loudness)
 *   HNR_approx    — harmonics-to-noise ratio (voice clarity vs breathiness)
 *                   approximated from YIN CMNDF minimum value
 *
 * EMOTION → TTS PARAMS:
 *   Each detected emotion maps to speed/pitch multipliers sent to hindi_tts_server.py
 *   The TTS server applies these at synthesis time using Piper/Kokoro params.
 *
 *   HAPPY:     speed×1.12 pitch×1.10  (lively, brighter)
 *   SAD:       speed×0.85 pitch×0.92  (slower, subdued)
 *   ANGRY:     speed×1.05 pitch×1.02  (tense, loud)
 *   FEARFUL:   speed×0.95 pitch×1.08  (shaky, higher)
 *   SURPRISED: speed×1.08 pitch×1.12  (sudden rise)
 *   DISGUST:   speed×0.90 pitch×0.90  (dropping)
 *   NEUTRAL:   speed×1.00 pitch×1.00  (baseline)
 */
object GenderAnalyzer {

    private const val TAG          = "GenderAnalyzer"
    private const val SR           = 16_000
    private const val WIN          = 2048
    // Voice frequency range — filters music bass (<85Hz) and noise (>400Hz)
    private const val F0_VOICE_MIN  = 85f    // below = music bass, not voice
    private const val F0_FEMALE_MIN = 165f   // male: 85-164Hz, female: 165-400Hz
    private const val F0_VOICE_MAX  = 400f   // above = falsetto/noise
    private const val YIN_THRESH   = 0.22f  // stricter — rejects music/noise
    private const val RMS_FLOOR    = 80f
    private const val HIST         = 3


        val pitchMult: Float get() = when (this) {
            // Basic emotions
            HAPPY     -> 1.10f;  SAD      -> 0.92f;  ANGRY    -> 1.02f
            FEARFUL   -> 1.08f;  SURPRISED-> 1.12f;  DISGUST  -> 0.90f
            // Breathive — lower, softer
            BREATHY   -> 0.95f;  WHISPERY -> 0.90f
            HUSHED    -> 0.93f;  MURMURED -> 0.88f
            // Warm — slightly lower, richer
            VELVETY   -> 0.96f;  SULTRY   -> 0.88f
            WARM      -> 0.97f;  TENDER   -> 0.94f
            // Intense — husky/raspy lower, strained higher
            HUSKY     -> 0.92f;  RASPY    -> 0.88f
            GRAVELLY  -> 0.85f;  STRAINED -> 1.08f
            // Rhythmic
            SIGHING   -> 0.90f;  PANTING  -> 1.05f
            MOANING   -> 0.87f;  GASPING  -> 1.12f
            else      -> 1.00f
        }

        /** Category for logging */
        val category: String get() = when (this) {
            NEUTRAL, HAPPY, SAD, ANGRY, FEARFUL, SURPRISED, DISGUST -> "basic"
            BREATHY, WHISPERY, HUSHED, MURMURED                     -> "breathive"
            VELVETY, SULTRY, WARM, TENDER                           -> "warm"
            HUSKY, RASPY, GRAVELLY, STRAINED                        -> "intense"
            SIGHING, PANTING, MOANING, GASPING                      -> "rhythmic"
        }
    }

    @Volatile var enabled     = false
    @Volatile var lastStatus  = "waiting for screen capture permission"
    @Volatile var detectedEmotion: HindiTtsService.Emotion = HindiTtsService.Emotion.NEUTRAL

    private val history       = ArrayDeque<HindiTtsService.Gender>()
    private val emotionHistory = ArrayDeque<HindiTtsService.Emotion>()  // smoothed over 5 frames
    private val accum         = ShortArray(WIN)
    private var accumFill     = 0

    private val scope         = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var captureJob:   Job?         = null
    private var captureRec:   AudioRecord? = null

    // Emotion feature tracking across frames
    private var prevF0        = 0f    // for slope calculation
    private var f0History     = FloatArray(8)  // ring buffer for jitter
    private var f0HistIdx     = 0
    private var frameCount    = 0
    private var analyzeCount  = 0

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start(projection: MediaProjection? = null) {
        if (enabled) return
        if (projection == null) {
            lastStatus = "no projection — grant screen capture permission"
            CaptionLogger.log(TAG, "start() — no projection")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            lastStatus = "API < Q — not supported"
            return
        }
        stop()
        captureJob = scope.launch { captureLoop(projection) }
    }

    fun stop() {
        enabled = false
        captureJob?.cancel(); captureJob = null
        try { captureRec?.stop()    } catch (_: Exception) {}
        try { captureRec?.release() } catch (_: Exception) {}
        captureRec = null
        history.clear(); emotionHistory.clear()
        accumFill = 0
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
            enabled = false
            lastStatus = "capture config failed: ${e.message}"
            CaptionLogger.log(TAG, "config failed: ${e.message}")
            return@withContext
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
            enabled = false
            lastStatus = "AudioRecord failed: ${e.message}"
            CaptionLogger.log(TAG, "AudioRecord failed: ${e.message}")
            return@withContext
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            enabled = false; rec.release()
            lastStatus = "AudioRecord state=${rec.state}"
            CaptionLogger.log(TAG, "AudioRecord not initialized")
            return@withContext
        }

        captureRec = rec; enabled = true
        lastStatus = "capturing USAGE_MEDIA SR=${SR}Hz"
        rec.startRecording()
        CaptionLogger.log(TAG, ">>> INTERNAL AUDIO CAPTURE STARTED SR=${SR}Hz <<<")

        val buf = ByteArray(WIN * 2)
        var readCount = 0
        try {
            while (currentCoroutineContext().isActive && enabled) {
                val n = rec.read(buf, 0, buf.size)
                when {
                    n > 0 -> {
                        readCount++
                        if (readCount == 1)
                            CaptionLogger.log(TAG, "FIRST read: $n bytes — media audio flowing!")
                        ingest(buf, n)
                    }
                    n < 0 -> { CaptionLogger.log(TAG, "read error=$n"); break }
                }
            }
        } finally {
            try { rec.stop(); rec.release() } catch (_: Exception) {}
            captureRec = null; enabled = false
            CaptionLogger.log(TAG, "captureLoop ended reads=$readCount")
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

    // ── YIN + emotion feature extraction ─────────────────────────────────────

    private fun analyze() {
        analyzeCount++

        // RMS energy
        var energy = 0.0
        for (s in accum) energy += s.toLong() * s
        val rms = sqrt(energy / WIN).toFloat()
        if (rms < RMS_FLOOR) return

        val tauMin = (SR / 300).coerceAtLeast(1)
        val tauMax = (SR / 60).coerceAtMost(WIN / 2 - 1)
        val half   = WIN / 2

        // YIN difference function
        val d = FloatArray(tauMax + 1)
        for (tau in 1..tauMax) {
            var s = 0f
            for (j in 0 until half) {
                val diff = accum[j].toFloat() / 32768f - accum[j + tau].toFloat() / 32768f
                s += diff * diff
            }
            d[tau] = s
        }

        // CMNDF
        val c = FloatArray(tauMax + 1); c[0] = 1f; var rs = 0f
        for (tau in 1..tauMax) {
            rs += d[tau]
            c[tau] = if (rs > 0f) d[tau] * tau / rs else 1f
        }

        // Find pitch + HNR approximation
        var tau = tauMin
        var minCmndf = 1f
        while (tau < tauMax - 1) {
            if (c[tau] < minCmndf) minCmndf = c[tau]
            if (c[tau] < YIN_THRESH) {
                val best = if (tau + 1 < tauMax && c[tau + 1] < c[tau]) tau + 1 else tau
                val f0   = SR.toFloat() / best
                // HNR approximation: lower CMNDF = more harmonic = clearer voice
                val hnr  = 1f - minCmndf   // 0 = noisy, 1 = pure tone
                onPitch(f0, rms, hnr)
                return
            }
            tau++
        }

        // No voiced frame — reset F0 slope tracking
        prevF0 = 0f
    }

    // ── Gender + Emotion classification ──────────────────────────────────────

    private fun onPitch(f0: Float, rms: Float, hnr: Float) {
        frameCount++

        // ── GENDER ────────────────────────────────────────────────────────────
        // Filter non-voice frequencies: music bass <85Hz, noise >400Hz
        if (f0 < F0_VOICE_MIN || f0 > F0_VOICE_MAX) {
            if (frameCount % 15 == 0)
                CaptionLogger.log(TAG, "IGNORE F0=${f0.toInt()}Hz outside voice ${F0_VOICE_MIN.toInt()}-${F0_VOICE_MAX.toInt()}Hz")
            return
        }

        val gender = if (f0 >= F0_FEMALE_MIN) HindiTtsService.Gender.FEMALE
                     else                  HindiTtsService.Gender.MALE

        history.addLast(gender)
        if (history.size > HIST) history.removeFirst()
        val fCount = history.count { it == HindiTtsService.Gender.FEMALE }
        val majGender = if (fCount > history.size / 2) HindiTtsService.Gender.FEMALE
                        else                            HindiTtsService.Gender.MALE

        if (majGender != HindiTtsService.detectedGender) {
            HindiTtsService.detectedGender = majGender
            HindiTtsService.spokenTokens.clear()
            lastStatus = "MEDIA audio → $majGender (F0=${f0.toInt()}Hz)"
            CaptionLogger.log(TAG, ">>> Gender SWITCHED to $majGender F0=${f0.toInt()}Hz <<<")
        }

        // ── EMOTION FEATURES ──────────────────────────────────────────────────

        // F0 slope: compare to previous voiced frame
        val f0Slope = if (prevF0 > 0f) (f0 - prevF0) / prevF0 else 0f
        prevF0 = f0

        // F0 jitter: variance in recent F0 values
        f0History[f0HistIdx % f0History.size] = f0; f0HistIdx++
        val validF0 = f0History.filter { it > 0f }
        val f0Mean  = if (validF0.isEmpty()) f0 else validF0.average().toFloat()
        val f0Jitter = if (validF0.size < 2) 0f else {
            validF0.map { abs(it - f0Mean) }.average().toFloat() / f0Mean.coerceAtLeast(1f)
        }

        // RMS relative to expected speech level
        val rmsNorm = (rms / 3000f).coerceIn(0f, 3f)  // normalized: 1.0 = normal speech

        // ── EMOTION RULES — all 23 voice types ───────────────────────────────
        // Priority: Rhythmic > Intense > Breathive/Warm > Basic emotions
        // (More specific/extreme patterns detected first)

        val emotion: HindiTtsService.Emotion = when {

            // ── RHYTHMIC & EXPRESSIVE (strongest physical signals) ────────────
            // GASPING: sudden RMS spike + sharp F0 rise + high jitter
            f0Slope > 0.20f && rmsNorm > 2.0f && f0Jitter > 0.18f ->
                HindiTtsService.Emotion.GASPING

            // PANTING: very high jitter + high RMS + elevated F0 (rapid bursts)
            f0Jitter > 0.22f && rmsNorm > 1.8f && f0 > f0Mean * 1.02f ->
                HindiTtsService.Emotion.PANTING

            // MOANING: very low F0, sustained (low jitter), mid energy, mid HNR
            f0 < f0Mean * 0.85f && f0Jitter < 0.06f && rmsNorm in 0.3f..1.0f && hnr > 0.4f ->
                HindiTtsService.Emotion.MOANING

            // SIGHING: falling F0 slope, low energy (exhalation)
            f0Slope < -0.12f && rmsNorm < 0.6f && hnr > 0.3f ->
                HindiTtsService.Emotion.SIGHING

            // ── INTENSE & PHYSIOLOGICAL ───────────────────────────────────────
            // STRAINED: high F0, high energy, high jitter, harsh
            f0 > f0Mean * 1.12f && rmsNorm > 1.3f && f0Jitter > 0.10f && hnr < 0.5f ->
                HindiTtsService.Emotion.STRAINED

            // GRAVELLY: very low F0, very low HNR (creaky), high jitter
            f0 < f0Mean * 0.80f && hnr < 0.30f && f0Jitter > 0.10f ->
                HindiTtsService.Emotion.GRAVELLY

            // RASPY: low HNR (gritty), high RMS, high jitter (rough texture)
            hnr < 0.35f && rmsNorm > 1.0f && f0Jitter > 0.08f ->
                HindiTtsService.Emotion.RASPY

            // HUSKY: low HNR (rough), mid-high RMS, mid jitter (throat constriction)
            hnr < 0.45f && rmsNorm in 0.7f..1.5f && f0Jitter in 0.05f..0.12f ->
                HindiTtsService.Emotion.HUSKY

            // ── BASIC HIGH-ENERGY EMOTIONS ────────────────────────────────────
            // SURPRISED: sudden sharp F0 rise (>15% in one frame) + energy
            f0Slope > 0.15f && rmsNorm > 0.8f ->
                HindiTtsService.Emotion.SURPRISED

            // ANGRY: very high energy, harsh (low HNR), any pitch
            rmsNorm > 1.4f && hnr < 0.5f ->
                HindiTtsService.Emotion.ANGRY

            // FEARFUL: high pitch, HIGH jitter (shaky), inconsistent
            f0 > f0Mean * 1.05f && f0Jitter > 0.12f ->
                HindiTtsService.Emotion.FEARFUL

            // ── BREATHIVE & LOW-INTENSITY (low energy + low HNR) ─────────────
            // WHISPERY: very low RMS + very low HNR (unvoiced air)
            rmsNorm < 0.25f && hnr < 0.25f ->
                HindiTtsService.Emotion.WHISPERY

            // MURMURED: low F0, low RMS, low HNR (continuous low sound)
            f0 < f0Mean * 0.88f && rmsNorm < 0.4f && hnr < 0.4f ->
                HindiTtsService.Emotion.MURMURED

            // HUSHED: low RMS, low HNR, low jitter (soft but controlled)
            rmsNorm < 0.35f && hnr < 0.40f && f0Jitter < 0.06f ->
                HindiTtsService.Emotion.HUSHED

            // BREATHY: low HNR (air leaking), low-mid RMS, steady F0
            hnr < 0.40f && rmsNorm in 0.2f..0.8f && f0Jitter < 0.07f ->
                HindiTtsService.Emotion.BREATHY

            // ── WARM & AFFECTIONATE (high HNR = clear resonant voice) ─────────
            // SULTRY: low F0, high HNR, low jitter, low-mid energy
            f0 < f0Mean * 0.92f && hnr > 0.65f && f0Jitter < 0.05f && rmsNorm < 0.9f ->
                HindiTtsService.Emotion.SULTRY

            // TENDER: low energy, high HNR, low jitter (gentle, light)
            rmsNorm < 0.45f && hnr > 0.60f && f0Jitter < 0.05f ->
                HindiTtsService.Emotion.TENDER

            // VELVETY: mid-low F0, high HNR, low jitter (smooth rich)
            f0 < f0Mean * 0.97f && hnr > 0.70f && f0Jitter < 0.04f ->
                HindiTtsService.Emotion.VELVETY

            // WARM: mid F0, high HNR, low jitter, mid energy (resonant)
            hnr > 0.65f && f0Jitter < 0.05f && rmsNorm in 0.4f..1.1f ->
                HindiTtsService.Emotion.WARM

            // ── BASIC EMOTIONAL STATES ────────────────────────────────────────
            // HAPPY: F0 above baseline, steady, energetic, clear
            f0 > f0Mean * 1.08f && f0Jitter < 0.08f && rmsNorm > 0.6f && hnr > 0.6f ->
                HindiTtsService.Emotion.HAPPY

            // SAD: F0 below baseline, flat slope, low energy
            f0 < f0Mean * 0.93f && abs(f0Slope) < 0.05f && rmsNorm < 0.7f ->
                HindiTtsService.Emotion.SAD

            // DISGUST: F0 dropping, low energy, creaky
            f0Slope < -0.10f && rmsNorm < 0.9f && hnr < 0.45f ->
                HindiTtsService.Emotion.DISGUST

            else -> HindiTtsService.Emotion.NEUTRAL
        }

        // Smooth emotion over 5 frames — prevent rapid flickering
        emotionHistory.addLast(emotion)
        if (emotionHistory.size > 7) emotionHistory.removeFirst()

        // Majority vote for smoothed emotion
        val emotionCounts = emotionHistory.groupingBy { it }.eachCount()
        val smoothedEmotion: HindiTtsService.Emotion = emotionCounts.maxByOrNull { it.value }?.key ?: HindiTtsService.Emotion.NEUTRAL

        if (smoothedEmotion != detectedEmotion) {
            detectedEmotion = smoothedEmotion
            CaptionLogger.log(TAG, "Emotion→${smoothedEmotion.name}[${smoothedEmotion.category}] " +
                "F0=${f0.toInt()}Hz slope=${String.format("%.2f",f0Slope)} " +
                "jitter=${String.format("%.3f",f0Jitter)} rms=${rmsNorm.format()} hnr=${hnr.format()} " +
                "spd=${smoothedEmotion.speedMult} pch=${smoothedEmotion.pitchMult}")
            HindiTtsService.currentEmotion = smoothedEmotion
        }

        // Periodic PITCH log
        if (frameCount % 5 == 0)
            CaptionLogger.log(TAG, "PITCH F0=${f0.toInt()}Hz → $gender | $smoothedEmotion " +
                "spd=${smoothedEmotion.speedMult} pch=${smoothedEmotion.pitchMult}")
    }

    private fun Float.format() = String.format("%.2f", this)
}
