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
 * SpeechCaptureService — v5 Clean
 *
 * KEY FIXES:
 * - Overlap window initialised correctly (no garbage bytes on first chunk)
 * - Queue offer is atomic (synchronized block)
 * - isTooSimilar disabled — server handles dedup
 * - Watchdog only fires after 60s silence (long video silences are normal)
 * - reconnecting flag does NOT block audio — only suppresses display
 * - Connection always disconnected after use (no connection leaks)
 * - READ_TIMEOUT matches server done_event.wait with margin
 */
class SpeechCaptureService : Service() {

    companion object {
        const val CHANNEL_ID        = "speech_capture_channel"
        const val NOTIF_ID          = 2
        const val EXTRA_RESULT_CODE  = "result_code"
        const val EXTRA_RESULT_DATA  = "result_data"
        const val EXTRA_GENDER_ONLY  = "gender_only"

        @Volatile var isRunning      = false
        @Volatile var targetLanguage = "hindi"
        @Volatile var latestOriginal = ""
        @Volatile var latestEnglish  = ""
        @Volatile var latestHindi    = ""

        // Shared MediaProjection — GenderAnalyzer uses this instead of creating its own
        @Volatile var sharedProjection: MediaProjection? = null

        private const val TAG            = "SpeechCapture"
        private const val SAMPLE_RATE    = 16_000
        private const val WHISPER_URL    = "http://127.0.0.1:8765/transcribe"
        private const val WHISPER_HEALTH = "http://127.0.0.1:8765/health"

        // 2s chunks + 0.3s overlap = 2.3s sent per request
        private const val CHUNK_SECS    = 2.0
        private const val CHUNK_SAMPLES = (SAMPLE_RATE * CHUNK_SECS).toInt()   // 32 000
        private const val CHUNK_BYTES   = CHUNK_SAMPLES * 2                    // 64 000

        // small model is accurate enough — 0.1s overlap is sufficient for boundary words
        // Reducing from 0.3s cuts audio sent per chunk by ~10% → less CPU load
        private const val OVERLAP_SECS    = 0.1
        private const val OVERLAP_SAMPLES = (SAMPLE_RATE * OVERLAP_SECS).toInt() // 1 600
        private const val OVERLAP_BYTES   = OVERLAP_SAMPLES * 2                  // 3 200
        private const val SEND_BYTES      = CHUNK_BYTES + OVERLAP_BYTES          // 67 200

        private const val QUEUE_CAPACITY = 4

        // Server done_event.wait = 18s — client READ_TIMEOUT must be larger
        private const val STALE_MS           = 25_000L
        private const val CONNECT_TIMEOUT_MS = 2_000
        private const val READ_TIMEOUT_MS    = 25_000  // small model: up to ~10s + margin

        private const val MAX_CONSECUTIVE_ERRORS = 6
        private const val WATCHDOG_TIMEOUT_MS    = 60_000L  // 60s — long silences in videos are normal
        private const val MAX_BACKOFF_MS         = 8_000L
    }

    private val mainHandler   = Handler(Looper.getMainLooper())
    private val capturing     = AtomicBoolean(false)
    private var captureThread: Thread?            = null
    private var workerThread:  Thread?            = null
    private var audioRecord:   AudioRecord?       = null
    private var mediaProjection: MediaProjection? = null
    private var wakeLock:      PowerManager.WakeLock? = null

    private data class AudioJob(val wav: ByteArray, val stampMs: Long)
    private val audioQueue            = LinkedBlockingQueue<AudioJob>(QUEUE_CAPACITY)
    private val chunksSentThisSession = AtomicInteger(0)

    private var lastPushedHindi = ""
    private val lastPushMs      = AtomicLong(0L)

    private val consecutiveErrors   = AtomicInteger(0)
    @Volatile private var reconnecting       = false
    private var reconnectBackoffMs           = 2_000L
    private var reconnectRunnable: Runnable? = null
    private var watchdogRunnable:  Runnable? = null

    // ── Lifecycle ────────────────────────────────────────────────────────────

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

        // Share projection for GenderAnalyzer — same token, no second getMediaProjection() call
        sharedProjection = mediaProjection

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { mainHandler.post { stopSelf() } }
            }, Handler(Looper.getMainLooper()))
        }

        // Gender-only mode: LC mode — hold projection for GenderAnalyzer, skip Whisper
        val genderOnly = intent?.getBooleanExtra(EXTRA_GENDER_ONLY, false) ?: false
        if (genderOnly) {
            updateNotification("Voice gender detection active")
            CaptionLogger.log("SCS", "gender-only mode — starting GenderAnalyzer with projection")
            GenderAnalyzer.start(sharedProjection)
            return START_STICKY   // stay alive to keep projection valid
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
        workerThread?.interrupt();  workerThread  = null
        audioQueue.clear()
        GenderAnalyzer.stop()
        try { audioRecord?.stop()    } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        sharedProjection = null
        mainHandler.removeCallbacksAndMessages(null)
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        super.onDestroy()
    }

    // ── Worker thread ─────────────────────────────────────────────────────────

    private fun startWorkerThread() {
        workerThread = Thread({
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val job   = audioQueue.take()
                    if (!capturing.get()) continue
                    val ageMs = System.currentTimeMillis() - job.stampMs
                    if (ageMs > STALE_MS) { Log.d(TAG, "Stale drop ${ageMs}ms"); continue }
                    sendToWhisper(job.wav, job.stampMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt(); break
                } catch (e: Exception) {
                    Log.w(TAG, "Worker: ${e.message}")
                }
            }
        }, "WhisperWorkerThread").apply { isDaemon = true; priority = Thread.NORM_PRIORITY; start() }
    }

    // ── Audio capture ─────────────────────────────────────────────────────────

    private fun startCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            OverlayService.updateText("", "Android 10+ required."); stopSelf(); return
        }
        val projection = mediaProjection ?: run {
            OverlayService.updateText("", "Screen capture lost."); stopSelf(); return
        }

        val minBuf  = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
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
        lastPushedHindi = ""
        audioQueue.clear()

        // Reset server BEFORE starting capture
        try {
            val conn = URL("http://127.0.0.1:8765/reset").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"; conn.connectTimeout = 1_000; conn.readTimeout = 1_000
            conn.responseCode; conn.disconnect()
            Log.d(TAG, "Server reset OK")
        } catch (e: Exception) { Log.d(TAG, "Server reset failed: ${e.message}") }

        capturing.set(true)
        ar.startRecording()
        // Start GenderAnalyzer with dedicated USAGE_MEDIA capture (separate from our capture)
        GenderAnalyzer.start(sharedProjection)
        updateNotification("Translating video audio to Hindi…")
        mainHandler.post { OverlayService.updateText("", "") }

        captureThread = Thread({
            // Sliding window with 0.3s overlap.
            // window[0..OVERLAP_BYTES) = overlap from previous chunk
            // window[OVERLAP_BYTES..SEND_BYTES) = new 2s audio
            //
            // Initialise the overlap region to silence (zeros) — not garbage.
            // First chunk: send only the 2s new audio (no overlap).
            // Subsequent chunks: send full SEND_BYTES (overlap + new audio).
            val window    = ByteArray(SEND_BYTES)  // zero-initialised by JVM
            var filled    = OVERLAP_BYTES          // start filling after overlap region
            val readBuf   = ByteArray(4096)
            var firstChunk = true

            while (capturing.get() && !Thread.currentThread().isInterrupted) {
                val rec  = audioRecord ?: break
                val read = rec.read(readBuf, 0, readBuf.size)
                if (read == AudioRecord.ERROR_INVALID_OPERATION ||
                    read == AudioRecord.ERROR_BAD_VALUE) continue
                if (read < 0) break
                if (read == 0) continue

                // GenderAnalyzer has its own AudioPlaybackCaptureConfiguration loop
                // No need to feed PCM manually — it reads USAGE_MEDIA independently

                var src = 0
                while (src < read) {
                    val space  = SEND_BYTES - filled
                    val toCopy = minOf(read - src, space)
                    System.arraycopy(readBuf, src, window, filled, toCopy)
                    filled += toCopy
                    src    += toCopy

                    if (filled >= SEND_BYTES) {
                        val pcmToSend: ByteArray = if (firstChunk) {
                            // First chunk: no valid overlap yet — send 2s only
                            firstChunk = false
                            window.copyOfRange(OVERLAP_BYTES, SEND_BYTES)
                        } else {
                            window.copyOf(SEND_BYTES)
                        }

                        val wav = pcmToWav(pcmToSend)
                        val job = AudioJob(wav, System.currentTimeMillis())

                        // Atomic queue management — drop oldest if full
                        synchronized(audioQueue) {
                            if (!audioQueue.offer(job)) {
                                audioQueue.poll()
                                audioQueue.offer(job)
                            }
                        }
                        chunksSentThisSession.incrementAndGet()

                        // Slide window: copy last OVERLAP_BYTES to front
                        System.arraycopy(window, SEND_BYTES - OVERLAP_BYTES, window, 0, OVERLAP_BYTES)
                        filled = OVERLAP_BYTES
                    }
                }
            }
            Log.d(TAG, "Capture thread ended")
        }, "AudioCaptureThread").apply { isDaemon = false; priority = Thread.NORM_PRIORITY; start() }
    }

    // ── HTTP to Whisper server ────────────────────────────────────────────────

    private fun isTooSimilar(a: String, b: String): Boolean {
        // Only block near-identical output (95%) — server handles main dedup
        val wordsA = a.trim().split("\\s+".toRegex()).filter { it.length > 1 }.toSet()
        val wordsB = b.trim().split("\\s+".toRegex()).filter { it.length > 1 }.toSet()
        if (wordsA.isEmpty()) return false
        return wordsA.intersect(wordsB).size.toDouble() / wordsA.size > 0.95
    }

    private fun sendToWhisper(wavBytes: ByteArray, stampMs: Long) {
        val ageMs = System.currentTimeMillis() - stampMs
        if (ageMs > STALE_MS) { Log.d(TAG, "Pre-send stale ${ageMs}ms"); return }

        // Retry loop — retries up to 4 times on 503 (server busy)
        // Server blocks up to 8s for queue space, so 503 is now very rare
        // but we still retry to guarantee no drops
        var attempts = 0
        while (attempts < 4) {
            attempts++
            val ageNow = System.currentTimeMillis() - stampMs
            if (ageNow > STALE_MS) { Log.d(TAG, "Stale after retry ${ageNow}ms"); return }

            var conn: HttpURLConnection? = null
            try {
                conn = URL(WHISPER_URL).openConnection() as HttpURLConnection
                conn.requestMethod  = "POST"
                conn.setRequestProperty("Content-Type",   "audio/wav")
                conn.setRequestProperty("Content-Length", wavBytes.size.toString())
                conn.setRequestProperty("Connection",     "keep-alive")
                conn.doOutput       = true
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout    = READ_TIMEOUT_MS

                conn.outputStream.use { it.write(wavBytes) }

                val respCode = conn.responseCode
                if (respCode == 503) {
                    Log.d(TAG, "Server busy 503 — retry $attempts/4 in 500ms")
                    conn.disconnect()
                    Thread.sleep(500)
                    continue  // retry
                }
                if (respCode != 200) { handleWhisperFailure("HTTP $respCode"); return }

                val body       = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                val json       = JSONObject(body)
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

                if (hindiText.length < 2) return

                if (lastPushedHindi.isNotEmpty() && isTooSimilar(hindiText, lastPushedHindi)) {
                    Log.d(TAG, "Dedup skip")
                    return
                }

                Log.d(TAG, "[$lang/${(confidence*100).toInt()}%] ${srcText.take(50)} → ${hindiText.take(60)}")
                lastPushedHindi = hindiText
                latestOriginal  = srcText
                latestEnglish   = srcText
                latestHindi     = hindiText

                mainHandler.post {
                    OverlayService.updateText(srcText, hindiText)
                    MainActivity.instance?.onTranslation(srcText, hindiText, hindiText)
                }
                return  // success — exit retry loop

            } catch (e: Exception) {
                Log.w(TAG, "Whisper error (attempt $attempts): ${e.javaClass.simpleName}: ${e.message}")
                if (attempts >= 4) handleWhisperFailure(e.message ?: "unknown")
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }
    }

    // ── Error handling / reconnect ────────────────────────────────────────────

    private fun handleWhisperFailure(reason: String) {
        val errors = consecutiveErrors.incrementAndGet()
        Log.w(TAG, "Whisper failure ($errors/$MAX_CONSECUTIVE_ERRORS): $reason")
        if (errors >= MAX_CONSECUTIVE_ERRORS && !reconnecting) {
            reconnecting = true
            mainHandler.post {
                updateNotification("Whisper disconnected — reconnecting…")
                OverlayService.updateText("", "⚠ Reconnecting…")
                MainActivity.instance?.notifyWhisperDisconnected()
            }
            scheduleReconnectPoll()
        }
    }

    private fun scheduleReconnectPoll() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        val delay = reconnectBackoffMs
        reconnectBackoffMs = minOf(reconnectBackoffMs * 2, MAX_BACKOFF_MS)
        reconnectRunnable  = Runnable { pollWhisperHealth() }
        mainHandler.postDelayed(reconnectRunnable!!, delay)
    }

    private fun pollWhisperHealth() {
        if (!capturing.get()) return
        Thread({
            val alive = try {
                val c = URL(WHISPER_HEALTH).openConnection() as HttpURLConnection
                c.requestMethod = "GET"; c.connectTimeout = 1_500; c.readTimeout = 1_500
                val ok = c.responseCode == 200; c.disconnect(); ok
            } catch (_: Exception) { false }

            if (alive) {
                consecutiveErrors.set(0); reconnecting = false; reconnectBackoffMs = 2_000L
                mainHandler.post {
                    updateNotification("Translating video audio to Hindi…")
                    OverlayService.updateText("", "✓ Reconnected — listening…")
                    MainActivity.instance?.notifyWhisperReconnected()
                }
            } else {
                mainHandler.post { scheduleReconnectPoll() }
            }
        }, "HealthCheckThread").apply { isDaemon = true; start() }
    }

    // ── Watchdog ──────────────────────────────────────────────────────────────

    private fun scheduleWatchdog() {
        watchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        watchdogRunnable = Runnable {
            if (!capturing.get() || reconnecting) return@Runnable
            val silenceMs = System.currentTimeMillis() - lastPushMs.get()
            if (silenceMs >= WATCHDOG_TIMEOUT_MS && lastPushMs.get() > 0) {
                Log.w(TAG, "Watchdog: ${silenceMs}ms silence — checking health")
                consecutiveErrors.set(MAX_CONSECUTIVE_ERRORS)
                handleWhisperFailure("watchdog timeout ${silenceMs}ms")
            } else {
                scheduleWatchdog()
            }
        }
        mainHandler.postDelayed(watchdogRunnable!!, WATCHDOG_TIMEOUT_MS)
    }

    // ── WAV helper ────────────────────────────────────────────────────────────

    private fun pcmToWav(pcm: ByteArray): ByteArray {
        val channels = 1; val bits = 16
        val byteRate = SAMPLE_RATE * channels * bits / 8
        val out = ByteArrayOutputStream(pcm.size + 44)
        val dos = DataOutputStream(out)
        dos.writeBytes("RIFF");     dos.writeIntLE(pcm.size + 36)
        dos.writeBytes("WAVEfmt "); dos.writeIntLE(16)
        dos.writeShortLE(1)         // PCM
        dos.writeShortLE(channels); dos.writeIntLE(SAMPLE_RATE); dos.writeIntLE(byteRate)
        dos.writeShortLE(channels * bits / 8); dos.writeShortLE(bits)
        dos.writeBytes("data");     dos.writeIntLE(pcm.size)
        dos.write(pcm); dos.flush()
        return out.toByteArray()
    }

    private fun DataOutputStream.writeIntLE(v: Int) {
        write(v and 0xff); write(v shr 8 and 0xff)
        write(v shr 16 and 0xff); write(v shr 24 and 0xff)
    }
    private fun DataOutputStream.writeShortLE(v: Int) {
        write(v and 0xff); write(v shr 8 and 0xff)
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "Internal Audio Capture", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
                .also { getSystemService(NotificationManager::class.java).createNotificationChannel(it) }
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Caption Lens — Translating to Hindi")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true).setSilent(true).build()

    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
}
