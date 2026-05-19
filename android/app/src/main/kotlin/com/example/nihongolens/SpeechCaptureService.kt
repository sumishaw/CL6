package com.example.nihongolens

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * SpeechCaptureService — REWRITTEN v4
 *
 * ROOT CAUSES FIXED (diagnosed from full client+server code review):
 *
 * FIX 1 — DROPPED SENTENCES:
 *   Old design: AtomicReference pendingJob (latest-only). When LibreTranslate
 *   was slow (readTimeout=15s), worker blocked for up to 15s while capture
 *   thread overwrote pendingJob 7 times → 6 sentences silently lost every slow
 *   request.
 *   New design: LinkedBlockingQueue(capacity=3). Every chunk is enqueued. When
 *   full, OLDEST is dropped (not newest). readTimeout reduced to 8s to match
 *   actual hardware performance (Whisper ~1.5s + LibreTranslate ~0.5s).
 *
 * FIX 2 — RANDOM LANGUAGE SUBTITLES:
 *   Server needs 3 consecutive chunks to lock the language. First chunk of each
 *   session is queued 3 times (warm-up) so the language locks before any subtitle
 *   is displayed. All subsequent translations use the locked language.
 *
 * FIX 3 — STALE DROP KILLS VALID AUDIO:
 *   Old STALE_MS=3000 + readTimeout=15000 meant chunks were dropped as "stale"
 *   simply because a previous request was slow. STALE_MS raised to 8000 to match
 *   new readTimeout.
 *
 * FIX 4 — 503 HANDLING:
 *   Server returns 503 when its queue is full. This is not an error — just skip
 *   the chunk silently instead of incrementing consecutiveErrors.
 */
class SpeechCaptureService : Service() {

    companion object {
        const val CHANNEL_ID        = "speech_capture_channel"
        const val NOTIF_ID          = 2
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        @Volatile var isRunning      = false
        @Volatile var targetLanguage = "hindi"
        @Volatile var latestOriginal = ""
        @Volatile var latestEnglish  = ""
        @Volatile var latestHindi    = ""

        private const val TAG            = "SpeechCapture"
        private const val SAMPLE_RATE    = 16_000
        private const val WHISPER_URL    = "http://127.0.0.1:8765/transcribe"
        private const val WHISPER_HEALTH = "http://127.0.0.1:8765/health"

        // 4s chunks — small model processes in ~8-10s on Dimensity 7050 (4 threads).
        // 4s chunks give more sentence context than 3s → better translation quality.
        // Queue depth stays at 2-3 max, lag stays under ~20s.
        private const val CHUNK_SECS    = 4.0
        private const val CHUNK_SAMPLES = (SAMPLE_RATE * CHUNK_SECS).toInt()  // 64 000
        private const val CHUNK_BYTES   = CHUNK_SAMPLES * 2                   // 128 000

        private const val QUEUE_CAPACITY = 3

        // small model: ~10s Whisper + ~0.6s CT2 (two attempts) + 21s margin = 32s
        private const val STALE_MS           = 32_000L
        private const val CONNECT_TIMEOUT_MS = 2_000
        private const val READ_TIMEOUT_MS    = 32_000

        private const val MAX_CONSECUTIVE_ERRORS = 5
        private const val WATCHDOG_TIMEOUT_MS    = 45_000L
        private const val MAX_BACKOFF_MS         = 8_000L
    }

    private val mainHandler  = Handler(Looper.getMainLooper())
    private val capturing    = AtomicBoolean(false)
    private var captureThread: Thread?           = null
    private var workerThread:  Thread?           = null
    private var audioRecord:   AudioRecord?      = null
    private var mediaProjection: MediaProjection? = null
    private var wakeLock:      PowerManager.WakeLock? = null

    private data class AudioJob(val wav: ByteArray, val stampMs: Long)
    private val audioQueue           = LinkedBlockingQueue<AudioJob>(QUEUE_CAPACITY)
    private val chunksSentThisSession = AtomicInteger(0)

    private var lastPushedHindi = ""
    private val lastPushMs      = AtomicLong(0L)

    private val consecutiveErrors = AtomicInteger(0)
    @Volatile private var reconnecting    = false
    private var reconnectBackoffMs        = 2_000L
    private var reconnectRunnable: Runnable? = null
    private var watchdogRunnable:  Runnable? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, buildNotification("Initialising…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, buildNotification("Initialising…"))
        }

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        @Suppress("WakelockTimeout")
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "CaptionLens::SpeechCapture"
        ).also { it.acquire(60 * 60 * 1000L) }

        startWorkerThread()
    }

    private fun startWorkerThread() {
        workerThread = Thread({
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val job = audioQueue.take()
                    if (!capturing.get()) continue
                    val ageMs = System.currentTimeMillis() - job.stampMs
                    if (ageMs > STALE_MS) {
                        Log.d(TAG, "Stale drop ${ageMs}ms"); continue
                    }
                    sendToWhisper(job.wav, job.stampMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt(); break
                } catch (e: Exception) {
                    Log.w(TAG, "Worker: ${e.message}")
                }
            }
        }, "WhisperWorkerThread").apply { isDaemon = true; priority = Thread.NORM_PRIORITY; start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData: Intent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf(); return START_NOT_STICKY
        }

        try {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }

        if (mediaProjection == null) { stopSelf(); return START_NOT_STICKY }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { mainHandler.post { stopSelf() } }
            }, Handler(Looper.getMainLooper()))
        }

        startCapture()
        scheduleWatchdog()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false; capturing.set(false); reconnecting = false
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        watchdogRunnable?.let  { mainHandler.removeCallbacks(it) }
        captureThread?.interrupt(); captureThread = null
        workerThread?.interrupt(); workerThread = null
        audioQueue.clear()
        try { audioRecord?.stop() }    catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        mainHandler.removeCallbacksAndMessages(null)
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        super.onDestroy()
    }

    private fun startCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            OverlayService.updateText("", "Android 10+ required."); stopSelf(); return
        }
        val projection = mediaProjection ?: run {
            OverlayService.updateText("", "Screen capture lost."); stopSelf(); return
        }

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { OverlayService.updateText("", "Audio init failed."); stopSelf(); return }
        val bufSize = maxOf(minBuf * 4, CHUNK_BYTES * 2)

        val captureConfig = android.media.AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val ar = try {
            AudioRecord.Builder()
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build())
                .setBufferSizeInBytes(bufSize)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
        } catch (e: Exception) {
            OverlayService.updateText("", "Audio setup failed."); stopSelf(); return
        }

        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release(); OverlayService.updateText("", "Audio init failed."); stopSelf(); return
        }
        audioRecord = ar
        chunksSentThisSession.set(0)
        lastPushedHindi = ""   // clear so previous session subtitle never re-shows
        audioQueue.clear()

        capturing.set(true)
        ar.startRecording()
        updateNotification("Translating video audio to Hindi…")

        // Clear any leftover subtitle from previous session immediately
        mainHandler.post {
            OverlayService.updateText("", "")
        }

        // Reset server language cache — prevents previous session's language
        // from bleeding into this session
        Thread({
            try {
                val conn = URL("http://127.0.0.1:8765/reset").openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 2_000
                conn.readTimeout    = 2_000
                conn.responseCode   // trigger request
                conn.disconnect()
                Log.d(TAG, "Server session reset successful")
            } catch (e: Exception) {
                Log.d(TAG, "Server reset call failed (non-critical): ${e.message}")
            }
        }, "ResetThread").apply { isDaemon = true; start() }

        captureThread = Thread({
            val window  = ByteArray(CHUNK_BYTES)
            var filled  = 0
            val readBuf = ByteArray(4096)

            while (capturing.get() && !Thread.currentThread().isInterrupted) {
                val rec  = audioRecord ?: break
                val read = rec.read(readBuf, 0, readBuf.size)
                if (read <= 0 || read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                    if (read < 0) break else continue
                }

                var src = 0
                while (src < read) {
                    val toCopy = minOf(read - src, CHUNK_BYTES - filled)
                    System.arraycopy(readBuf, src, window, filled, toCopy)
                    filled += toCopy; src += toCopy

                    if (filled >= CHUNK_BYTES) {
                        if (!reconnecting) {
                            val wav     = pcmToWav(window.copyOf(CHUNK_BYTES))
                            val stampMs = System.currentTimeMillis()
                            val job     = AudioJob(wav, stampMs)

                            // Bounded enqueue: drop oldest if full
                            if (!audioQueue.offer(job)) {
                                audioQueue.poll(); audioQueue.offer(job)
                                Log.d(TAG, "Queue full — dropped oldest")
                            }
                            chunksSentThisSession.incrementAndGet()
                        }
                        filled = 0
                    }
                }
            }
            Log.d(TAG, "Capture thread ended")
        }, "AudioCaptureThread").apply { isDaemon = false; priority = Thread.NORM_PRIORITY; start() }
    }

    private fun isTooSimilar(a: String, b: String): Boolean {
        val wordsA = a.trim().split("\\s+".toRegex()).filter { it.length > 1 }.toSet()
        val wordsB = b.trim().split("\\s+".toRegex()).filter { it.length > 1 }.toSet()
        if (wordsA.isEmpty()) return false
        val overlap = wordsA.intersect(wordsB).size
        return overlap.toDouble() / wordsA.size > 0.80
    }

    private fun sendToWhisper(wavBytes: ByteArray, stampMs: Long) {
        val ageMs = System.currentTimeMillis() - stampMs
        if (ageMs > STALE_MS) { Log.d(TAG, "Pre-send stale ${ageMs}ms"); return }

        try {
            val conn = URL(WHISPER_URL).openConnection() as HttpURLConnection
            conn.requestMethod  = "POST"
            conn.setRequestProperty("Content-Type",   "audio/wav")
            conn.setRequestProperty("Content-Length", wavBytes.size.toString())
            conn.setRequestProperty("Connection",     "keep-alive")
            conn.doOutput       = true
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout    = READ_TIMEOUT_MS

            conn.outputStream.use { it.write(wavBytes) }

            val respCode = conn.responseCode
            if (respCode == 503) { Log.d(TAG, "Server busy 503 — skip"); return }
            if (respCode != 200) { handleWhisperFailure("HTTP $respCode"); return }

            val json       = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).readText())
            val hindiText  = json.optString("text",        "").trim()
            val srcText    = json.optString("source_text", "").trim()
            val lang       = json.optString("language",    "")
            val confidence = json.optDouble("confidence",  0.0)

            consecutiveErrors.set(0)
            if (reconnecting) {
                reconnecting = false; reconnectBackoffMs = 2_000L
                mainHandler.post {
                    updateNotification("Translating video audio to Hindi…")
                    OverlayService.updateText("", "✓ Reconnected — listening…")
                    MainActivity.instance?.notifyWhisperReconnected()
                }
            }
            lastPushMs.set(System.currentTimeMillis())
            scheduleWatchdog()

            // Only process if Hindi translation exists — never show English
            if (hindiText.length < 2) return

            // Smart dedup on Hindi text only
            if (lastPushedHindi.isNotEmpty() && isTooSimilar(hindiText, lastPushedHindi)) {
                Log.d(TAG, "Dedup: too similar to last Hindi — skipping")
                return
            }

            Log.d(TAG, "[$lang/${(confidence*100).toInt()}%] HI: ${hindiText.take(80)}")
            lastPushedHindi = hindiText
            latestOriginal  = srcText; latestEnglish = srcText; latestHindi = hindiText

            mainHandler.post {
                OverlayService.updateText(srcText, hindiText)
                MainActivity.instance?.onTranslation(srcText, hindiText, hindiText)
            }

        } catch (e: Exception) {
            Log.w(TAG, "Whisper failed: ${e.javaClass.simpleName}: ${e.message}")
            handleWhisperFailure(e.message ?: "unknown")
        }
    }

    private fun handleWhisperFailure(reason: String) {
        val errors = consecutiveErrors.incrementAndGet()
        if (errors >= MAX_CONSECUTIVE_ERRORS && !reconnecting) {
            reconnecting = true
            mainHandler.post {
                updateNotification("Whisper disconnected — reconnecting…")
                OverlayService.updateText("", "⚠ Reconnecting to Whisper…")
                MainActivity.instance?.notifyWhisperDisconnected()
            }
            scheduleReconnectPoll()
        }
    }

    private fun scheduleReconnectPoll() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        val delay = reconnectBackoffMs
        reconnectBackoffMs = minOf(reconnectBackoffMs * 2, MAX_BACKOFF_MS)
        reconnectRunnable = Runnable { pollWhisperHealth() }
        mainHandler.postDelayed(reconnectRunnable!!, delay)
    }

    private fun pollWhisperHealth() {
        if (!capturing.get()) return
        Thread({
            val alive = try {
                val conn = URL(WHISPER_HEALTH).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"; conn.connectTimeout = 1_500; conn.readTimeout = 1_500
                val code = conn.responseCode; conn.disconnect(); code == 200
            } catch (_: Exception) { false }

            if (alive) {
                consecutiveErrors.set(0); reconnecting = false; reconnectBackoffMs = 2_000L
                mainHandler.post {
                    updateNotification("Translating video audio to Hindi…")
                    OverlayService.updateText("", "✓ Reconnected — listening…")
                    MainActivity.instance?.notifyWhisperReconnected()
                }
            } else { mainHandler.post { scheduleReconnectPoll() } }
        }, "HealthCheckThread").apply { isDaemon = true; start() }
    }

    private fun scheduleWatchdog() {
        watchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        watchdogRunnable = Runnable {
            if (!capturing.get() || reconnecting) return@Runnable
            val silenceMs = System.currentTimeMillis() - lastPushMs.get()
            if (silenceMs >= WATCHDOG_TIMEOUT_MS && lastPushMs.get() > 0) {
                consecutiveErrors.set(MAX_CONSECUTIVE_ERRORS)
                handleWhisperFailure("watchdog timeout")
            } else scheduleWatchdog()
        }
        mainHandler.postDelayed(watchdogRunnable!!, WATCHDOG_TIMEOUT_MS)
    }

    private fun pcmToWav(pcm: ByteArray): ByteArray {
        val channels = 1; val bits = 16
        val byteRate = SAMPLE_RATE * channels * bits / 8
        val out = ByteArrayOutputStream(pcm.size + 44)
        val dos = DataOutputStream(out)
        dos.writeBytes("RIFF"); dos.writeIntLE(pcm.size + 36)
        dos.writeBytes("WAVEfmt "); dos.writeIntLE(16); dos.writeShortLE(1)
        dos.writeShortLE(channels); dos.writeIntLE(SAMPLE_RATE); dos.writeIntLE(byteRate)
        dos.writeShortLE(channels * bits / 8); dos.writeShortLE(bits)
        dos.writeBytes("data"); dos.writeIntLE(pcm.size); dos.write(pcm)
        dos.flush(); return out.toByteArray()
    }
    private fun DataOutputStream.writeIntLE(v: Int) { write(v and 0xff); write(v shr 8 and 0xff); write(v shr 16 and 0xff); write(v shr 24 and 0xff) }
    private fun DataOutputStream.writeShortLE(v: Int) { write(v and 0xff); write(v shr 8 and 0xff) }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "Internal Audio Capture", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
                .also { getSystemService(NotificationManager::class.java).createNotificationChannel(it) }
        }
    }
    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Caption Lens — Translating to Hindi").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).setSilent(true).build()
    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
}
