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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * SpeechCaptureService — realtime subtitle pipeline with auto-reconnect
 *
 * Audio pipeline (sliding-window, minimal latency):
 *   1. Accumulate 1.5 s of 16 kHz mono PCM
 *   2. Send to whisper_server.py immediately
 *   3. Slide forward by 1.0 s (keep 0.5 s overlap for context continuity)
 *   4. Two parallel whisper workers so a slow chunk never blocks the next one
 *   5. De-duplicate consecutive identical results before pushing to overlay
 *
 * Reconnect strategy:
 *   • Every failed whisper call increments a consecutive-error counter.
 *   • At 3 consecutive errors → enter reconnect mode, pause sending chunks.
 *   • Poll /health every 2 s → 4 s → 8 s (exponential backoff, capped at 8 s).
 *   • Once /health returns 200 → resume immediately, notify Flutter UI.
 *   • Watchdog: if no success for 15 s → force reconnect check automatically.
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

        private const val TAG         = "SpeechCapture"
        private const val SAMPLE_RATE = 16_000
        private const val WHISPER_URL    = "http://127.0.0.1:8765/transcribe"
        private const val WHISPER_HEALTH = "http://127.0.0.1:8765/health"

        // ── Audio timing ──────────────────────────────────────────────────────
        private const val CHUNK_SECS    = 1.5
        private const val STRIDE_SECS   = 1.0

        private const val CHUNK_SAMPLES  = (SAMPLE_RATE * CHUNK_SECS).toInt()   // 24 000
        private const val STRIDE_SAMPLES = (SAMPLE_RATE * STRIDE_SECS).toInt()  // 16 000
        private const val CHUNK_BYTES    = CHUNK_SAMPLES  * 2                   // 48 000
        private const val STRIDE_BYTES   = STRIDE_SAMPLES * 2                   // 32 000

        // ── Reconnect thresholds ──────────────────────────────────────────────
        private const val MAX_CONSECUTIVE_ERRORS = 3       // failures before reconnect mode
        private const val WATCHDOG_TIMEOUT_MS    = 15_000L // no success → force check
        private const val MAX_BACKOFF_MS         = 8_000L  // cap on health-poll interval
    }

    private val mainHandler   = Handler(Looper.getMainLooper())
    private val capturing     = AtomicBoolean(false)
    private var captureThread: Thread?            = null
    private var audioRecord:   AudioRecord?       = null
    private var mediaProjection: MediaProjection? = null
    private var wakeLock:      PowerManager.WakeLock? = null

    // Two workers so a slow chunk never blocks the next one
    private val whisperExecutor = Executors.newFixedThreadPool(2)

    private var lastPushedHindi = ""
    private val lastPushMs      = AtomicLong(0L)

    // ── Reconnect state ───────────────────────────────────────────────────────
    private val consecutiveErrors  = AtomicInteger(0)
    @Volatile private var reconnecting      = false
    private var reconnectBackoffMs          = 2_000L
    private var reconnectRunnable: Runnable? = null
    private var watchdogRunnable:  Runnable? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                buildNotification("Initialising…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, buildNotification("Initialising…"))
        }

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        @Suppress("WakelockTimeout")
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CaptionLens::SpeechCapture"
        ).also { it.acquire(60 * 60 * 1000L) }

        Log.d(TAG, "onCreate — foreground started, wakeLock acquired")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.e(TAG, "onStartCommand received null intent — stopping")
            stopSelf(); return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData: Intent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else
                @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            Log.e(TAG, "No valid MediaProjection token")
            stopSelf(); return START_NOT_STICKY
        }

        try {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed: ${e.message}")
            stopSelf(); return START_NOT_STICKY
        }

        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection is null after getMediaProjection()")
            stopSelf(); return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped externally")
                    mainHandler.post { stopSelf() }
                }
            }, Handler(Looper.getMainLooper()))
        }

        startCapture()
        scheduleWatchdog()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        isRunning    = false
        capturing.set(false)
        reconnecting = false

        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        watchdogRunnable?.let  { mainHandler.removeCallbacks(it) }

        captureThread?.interrupt()
        captureThread = null

        try { audioRecord?.stop() }    catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null

        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null

        whisperExecutor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)

        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null

        super.onDestroy()
    }

    // ── Audio capture — sliding-window loop ───────────────────────────────────

    private fun startCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            OverlayService.updateText("", "Android 10 or newer required.")
            stopSelf(); return
        }

        val projection = mediaProjection ?: run {
            Log.e(TAG, "MediaProjection null at capture start")
            OverlayService.updateText("", "Screen capture lost — tap STOP then START again.")
            stopSelf(); return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "getMinBufferSize error: $minBuf")
            OverlayService.updateText("", "Audio init failed — tap STOP then START.")
            stopSelf(); return
        }
        val bufSize = maxOf(minBuf * 4, CHUNK_BYTES * 2)

        val captureConfig = android.media.AudioPlaybackCaptureConfiguration
            .Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val ar = try {
            AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord.Builder failed: ${e.message}")
            OverlayService.updateText("", "Audio setup failed: ${e.message}")
            stopSelf(); return
        }

        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord state=${ar.state} — not initialized")
            ar.release()
            OverlayService.updateText("", "Audio init failed — tap STOP then START.")
            stopSelf(); return
        }
        audioRecord = ar

        capturing.set(true)
        ar.startRecording()
        updateNotification("Translating video audio to Hindi…")
        OverlayService.updateText("", "Listening to video audio…")
        Log.d(TAG, "Capture started — chunk=${CHUNK_SECS}s stride=${STRIDE_SECS}s buf=$bufSize")

        captureThread = Thread({
            val window  = ByteArray(CHUNK_BYTES)
            var filled  = 0
            val readBuf = ByteArray(4096)

            while (capturing.get() && !Thread.currentThread().isInterrupted) {
                val rec  = audioRecord ?: break
                val read = rec.read(readBuf, 0, readBuf.size)

                if (read == AudioRecord.ERROR_INVALID_OPERATION ||
                    read == AudioRecord.ERROR_BAD_VALUE
                ) {
                    Log.e(TAG, "AudioRecord.read error: $read")
                    break
                }
                if (read <= 0) continue

                var src = 0
                while (src < read) {
                    val space  = CHUNK_BYTES - filled
                    val toCopy = minOf(read - src, space)
                    System.arraycopy(readBuf, src, window, filled, toCopy)
                    filled += toCopy
                    src    += toCopy

                    if (filled >= CHUNK_BYTES) {
                        // Skip sending while reconnecting — don't flood a recovering server
                        if (!reconnecting && !whisperExecutor.isShutdown) {
                            val payload = window.copyOf(CHUNK_BYTES)
                            whisperExecutor.submit { sendToWhisper(payload) }
                        }

                        val overlap = CHUNK_BYTES - STRIDE_BYTES
                        System.arraycopy(window, STRIDE_BYTES, window, 0, overlap)
                        filled = overlap
                    }
                }
            }
            Log.d(TAG, "Capture thread ended")
        }, "AudioCaptureThread").apply {
            isDaemon = false
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    // ── Whisper HTTP call ─────────────────────────────────────────────────────

    private fun sendToWhisper(pcmBytes: ByteArray) {
        try {
            val wavBytes = pcmToWav(pcmBytes)

            val conn = URL(WHISPER_URL).openConnection() as HttpURLConnection
            conn.requestMethod  = "POST"
            conn.setRequestProperty("Content-Type",   "audio/wav")
            conn.setRequestProperty("Content-Length", wavBytes.size.toString())
            conn.doOutput       = true
            conn.connectTimeout = 4_000   // fast-fail if Termux crashed
            conn.readTimeout    = 12_000  // tiny model; give ample headroom

            conn.outputStream.use { it.write(wavBytes) }

            val respCode = conn.responseCode
            if (respCode != 200) {
                Log.w(TAG, "Whisper HTTP $respCode")
                handleWhisperFailure("HTTP $respCode")
                return
            }

            val body      = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val json      = JSONObject(body)
            val hindiText = json.optString("text",        "").trim()
            val srcText   = json.optString("source_text", "").trim()
            val lang      = json.optString("language",    "")
            val confidence = json.optDouble("confidence",  0.0)

            // ── SUCCESS — reset error state ───────────────────────────────────
            consecutiveErrors.set(0)
            if (reconnecting) {
                reconnecting       = false
                reconnectBackoffMs = 2_000L
                Log.d(TAG, "Whisper reconnected successfully")
                mainHandler.post {
                    updateNotification("Translating video audio to Hindi…")
                    OverlayService.updateText("", "✓ Reconnected — listening…")
                    MainActivity.instance?.notifyWhisperReconnected()
                }
            }
            lastPushMs.set(System.currentTimeMillis())
            scheduleWatchdog()   // reset watchdog on each success

            if (hindiText.length < 2 || hindiText == lastPushedHindi) return

            Log.d(TAG,
                "Whisper [$lang / ${(confidence * 100).toInt()}%] → HI: ${hindiText.take(60)}")

            lastPushedHindi = hindiText
            latestOriginal  = srcText
            latestEnglish   = srcText
            latestHindi     = hindiText

            mainHandler.post {
                OverlayService.updateText(srcText, hindiText)
                MainActivity.instance?.onTranslation(srcText, hindiText, hindiText)
            }

        } catch (e: Exception) {
            Log.w(TAG, "Whisper call failed: ${e.javaClass.simpleName}: ${e.message}")
            handleWhisperFailure(e.message ?: "unknown")
        }
    }

    // ── Reconnect logic ───────────────────────────────────────────────────────

    /** Increment error counter; enter reconnect mode at threshold. */
    private fun handleWhisperFailure(reason: String) {
        val errors = consecutiveErrors.incrementAndGet()
        Log.w(TAG, "Whisper error #$errors: $reason")

        if (errors >= MAX_CONSECUTIVE_ERRORS && !reconnecting) {
            reconnecting = true
            Log.w(TAG, "Entering reconnect mode after $errors consecutive errors")
            mainHandler.post {
                updateNotification("Whisper disconnected — reconnecting…")
                OverlayService.updateText("", "⚠ Reconnecting to Whisper…")
                MainActivity.instance?.notifyWhisperDisconnected()
            }
            scheduleReconnectPoll()
        }
    }

    /** Schedule next /health poll with exponential backoff: 2 s → 4 s → 8 s. */
    private fun scheduleReconnectPoll() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }

        val delay = reconnectBackoffMs
        reconnectBackoffMs = minOf(reconnectBackoffMs * 2, MAX_BACKOFF_MS)

        val runnable = Runnable { pollWhisperHealth() }
        reconnectRunnable = runnable
        mainHandler.postDelayed(runnable, delay)
        Log.d(TAG, "Next whisper health poll in ${delay}ms")
    }

    /** Ping /health; resume on 200, schedule next poll on failure. */
    private fun pollWhisperHealth() {
        if (!capturing.get()) return
        whisperExecutor.submit {
            val alive = try {
                val conn = URL(WHISPER_HEALTH).openConnection() as HttpURLConnection
                conn.requestMethod  = "GET"
                conn.connectTimeout = 3_000
                conn.readTimeout    = 3_000
                val code = conn.responseCode
                conn.disconnect()
                code == 200
            } catch (_: Exception) { false }

            if (alive) {
                consecutiveErrors.set(0)
                reconnecting       = false
                reconnectBackoffMs = 2_000L
                Log.d(TAG, "Whisper health check: server back online")
                mainHandler.post {
                    updateNotification("Translating video audio to Hindi…")
                    OverlayService.updateText("", "✓ Reconnected — listening…")
                    MainActivity.instance?.notifyWhisperReconnected()
                }
            } else {
                mainHandler.post { scheduleReconnectPoll() }
            }
        }
    }

    /**
     * Watchdog: if no successful translation arrives for WATCHDOG_TIMEOUT_MS
     * (default 15 s), force a reconnect check — covers silent hangs where
     * whisper is alive but returning empty bodies or timing out mid-response.
     */
    private fun scheduleWatchdog() {
        watchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            if (!capturing.get() || reconnecting) return@Runnable
            val silenceMs = System.currentTimeMillis() - lastPushMs.get()
            if (silenceMs >= WATCHDOG_TIMEOUT_MS && lastPushMs.get() > 0) {
                Log.w(TAG, "Watchdog fired — no translation for ${silenceMs}ms")
                consecutiveErrors.set(MAX_CONSECUTIVE_ERRORS)
                handleWhisperFailure("watchdog timeout")
            } else {
                scheduleWatchdog()
            }
        }
        watchdogRunnable = runnable
        mainHandler.postDelayed(runnable, WATCHDOG_TIMEOUT_MS)
    }

    // ── PCM → WAV ─────────────────────────────────────────────────────────────

    private fun pcmToWav(pcm: ByteArray): ByteArray {
        val channels    = 1
        val bitsPerSamp = 16
        val byteRate    = SAMPLE_RATE * channels * bitsPerSamp / 8
        val dataLen     = pcm.size
        val riffChunkSz = dataLen + 36

        val out = ByteArrayOutputStream(riffChunkSz + 8)
        val dos = DataOutputStream(out)

        dos.writeBytes("RIFF")
        dos.writeIntLE(riffChunkSz)
        dos.writeBytes("WAVE")
        dos.writeBytes("fmt ")
        dos.writeIntLE(16)
        dos.writeShortLE(1)
        dos.writeShortLE(channels)
        dos.writeIntLE(SAMPLE_RATE)
        dos.writeIntLE(byteRate)
        dos.writeShortLE(channels * bitsPerSamp / 8)
        dos.writeShortLE(bitsPerSamp)
        dos.writeBytes("data")
        dos.writeIntLE(dataLen)
        dos.write(pcm)
        dos.flush()
        return out.toByteArray()
    }

    private fun DataOutputStream.writeIntLE(v: Int) {
        write(v         and 0xff)
        write(v shr  8  and 0xff)
        write(v shr 16  and 0xff)
        write(v shr 24  and 0xff)
    }
    private fun DataOutputStream.writeShortLE(v: Int) {
        write(v        and 0xff)
        write(v shr 8  and 0xff)
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(
                CHANNEL_ID,
                "Internal Audio Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
             .also { getSystemService(NotificationManager::class.java)
                         .createNotificationChannel(it) }
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Caption Lens — Translating to Hindi")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }
}
