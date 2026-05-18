package com.example.nihongolens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * MainActivity
 *
 * Manages the Flutter MethodChannel bridge and whisper server health monitoring.
 *
 * Reconnect additions:
 *   • notifyWhisperDisconnected() — tells Flutter UI to show "Whisper Unreachable" card
 *   • notifyWhisperReconnected()  — tells Flutter UI to show "Speech Model Ready" card
 *   • Periodic background health check every 30 s while capture is NOT running,
 *     so the status card stays accurate when the user is on the home screen.
 */
class MainActivity : FlutterActivity() {

    companion object {
        @Volatile var instance: MainActivity? = null

        private const val REQ_MEDIA_PROJECTION = 200
        private const val REQ_AUDIO_PERMISSION  = 100
        private const val TAG                   = "MainActivity"

        private const val WHISPER_HEALTH_URL = "http://127.0.0.1:8765/ready"

        /** Interval for background health polling when capture is idle (ms). */
        private const val IDLE_HEALTH_POLL_MS = 30_000L
    }

    private val CHANNEL = "overlay_channel"
    private var methodChannel: MethodChannel? = null

    @Volatile private var pendingProjectionResult: MethodChannel.Result? = null

    private val healthExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler    = Handler(Looper.getMainLooper())

    /** Background idle-poll runnable — runs only while capture is stopped. */
    private var idlePollRunnable: Runnable? = null

    // ── Flutter method channel ─────────────────────────────────────────────────

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        instance = this

        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        methodChannel?.setMethodCallHandler { call, result ->
            when (call.method) {

                "hasOverlayPermission" ->
                    result.success(Settings.canDrawOverlays(this))

                "requestOverlayPermission" -> {
                    if (!Settings.canDrawOverlays(this)) {
                        startActivity(Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        ))
                        result.success(false)
                    } else {
                        result.success(true)
                    }
                }

                "hasAudioPermission" ->
                    result.success(
                        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED
                    )

                "requestAudioPermission" ->
                    requestAudioThenProjection(result)

                "checkAccessibilityEnabled" -> result.success(true)
                "openAccessibilitySettings" -> result.success(true)

                // ── Whisper server readiness checks ──────────────────────────

                "isModelReady" -> checkWhisperReady { ready ->
                    runOnUiThread { result.success(ready) }
                }

                "getModelStatus" -> checkWhisperReady { ready ->
                    runOnUiThread {
                        result.success(if (ready) "ready" else "not_downloaded")
                    }
                }

                "startModelDownload" -> {
                    result.success(true)
                    checkAndNotifyWhisperReady()
                }

                // ── Overlay ──────────────────────────────────────────────────

                "startOverlay" -> {
                    val i = Intent(this, OverlayService::class.java)
                    startForegroundServiceCompat(i)
                    result.success(true)
                }

                "stopOverlay" -> {
                    stopService(Intent(this, OverlayService::class.java))
                    result.success(true)
                }

                // ── Speech capture ────────────────────────────────────────────

                "startSpeechCapture" -> {
                    stopIdlePoll()   // SpeechCaptureService manages its own reconnect now
                    requestAudioThenProjection(result)
                }

                "stopSpeechCapture" -> {
                    stopService(Intent(this, SpeechCaptureService::class.java))
                    result.success(true)
                    startIdlePoll()  // resume idle polling while capture is stopped
                }

                "isSpeechCaptureRunning" ->
                    result.success(SpeechCaptureService.isRunning)

                "setTargetLanguage" -> {
                    SpeechCaptureService.targetLanguage = "hindi"
                    result.success(true)
                }

                "getLatestTranslation" ->
                    result.success(mapOf(
                        "original" to SpeechCaptureService.latestOriginal,
                        "english"  to SpeechCaptureService.latestEnglish,
                        "hindi"    to SpeechCaptureService.latestHindi
                    ))

                else -> result.notImplemented()
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndNotifyWhisperReady()
    }

    override fun onResume() {
        super.onResume()
        instance = this
        // Re-check whisper status every time the user comes back to the app
        if (!SpeechCaptureService.isRunning) {
            checkAndNotifyWhisperReady()
        }
    }

    override fun onPause() {
        super.onPause()
        // Keep idle polling running in background so status card is fresh on resume
    }

    override fun onDestroy() {
        pendingProjectionResult?.success(false)
        pendingProjectionResult = null
        stopIdlePoll()
        healthExecutor.shutdownNow()
        instance = null
        super.onDestroy()
    }

    // ── Idle health polling (when capture is not running) ──────────────────────

    private fun startIdlePoll() {
        stopIdlePoll()
        val runnable = object : Runnable {
            override fun run() {
                if (!SpeechCaptureService.isRunning) {
                    checkAndNotifyWhisperReady()
                    mainHandler.postDelayed(this, IDLE_HEALTH_POLL_MS)
                }
            }
        }
        idlePollRunnable = runnable
        mainHandler.postDelayed(runnable, IDLE_HEALTH_POLL_MS)
    }

    private fun stopIdlePoll() {
        idlePollRunnable?.let { mainHandler.removeCallbacks(it) }
        idlePollRunnable = null
    }

    // ── Whisper server health checks ──────────────────────────────────────────

    private fun checkWhisperReady(onResult: (Boolean) -> Unit) {
        healthExecutor.submit {
            val ready = try {
                val conn = URL(WHISPER_HEALTH_URL).openConnection() as HttpURLConnection
                conn.requestMethod  = "GET"
                conn.connectTimeout = 3_000
                conn.readTimeout    = 3_000
                conn.connect()
                val code = conn.responseCode
                conn.disconnect()
                code == 200
            } catch (_: Exception) {
                false
            }
            onResult(ready)
        }
    }

    private fun checkAndNotifyWhisperReady() {
        checkWhisperReady { ready ->
            runOnUiThread {
                if (ready) {
                    Log.d(TAG, "whisper_server.py is ready")
                    methodChannel?.invokeMethod("onModelReady", null)
                } else {
                    Log.w(TAG, "whisper_server.py not reachable on port 8765")
                    methodChannel?.invokeMethod(
                        "onModelError",
                        mapOf("message" to
                            "Whisper server not running.\n" +
                            "Start it with:\n  python3 whisper_server.py\n" +
                            "Then tap RETRY.")
                    )
                }
            }
        }
    }

    // ── Called by SpeechCaptureService to update Flutter UI on reconnect ───────

    /**
     * Called by SpeechCaptureService when whisper becomes unreachable.
     * Updates the Flutter model-status card to show the error state.
     */
    fun notifyWhisperDisconnected() {
        runOnUiThread {
            Log.w(TAG, "Notifying Flutter: whisper disconnected")
            methodChannel?.invokeMethod(
                "onModelError",
                mapOf("message" to
                    "Whisper server disconnected.\n" +
                    "Reconnecting automatically…\n" +
                    "Tap RETRY if this persists.")
            )
        }
    }

    /**
     * Called by SpeechCaptureService when whisper comes back online.
     * Updates the Flutter model-status card to show ready state.
     */
    fun notifyWhisperReconnected() {
        runOnUiThread {
            Log.d(TAG, "Notifying Flutter: whisper reconnected")
            methodChannel?.invokeMethod("onModelReady", null)
        }
    }

    /** Called from SpeechCaptureService to push a translation to the Flutter UI. */
    fun onTranslation(original: String, english: String, hindi: String) {
        runOnUiThread {
            methodChannel?.invokeMethod("onTranslation", mapOf(
                "original" to original,
                "english"  to english,
                "hindi"    to hindi
            ))
        }
    }

    // ── Permission + projection flow ──────────────────────────────────────────

    private fun requestAudioThenProjection(result: MethodChannel.Result) {
        if (!Settings.canDrawOverlays(this)) {
            result.success(false); return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            requestMediaProjection(result)
        } else {
            deliverPendingFailure()
            pendingProjectionResult = result
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQ_AUDIO_PERMISSION
            )
        }
    }

    private fun requestMediaProjection(result: MethodChannel.Result) {
        deliverPendingFailure()
        pendingProjectionResult = result
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(mgr.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION)
        } catch (e: Exception) {
            Log.e(TAG, "createScreenCaptureIntent failed: ${e.message}")
            pendingProjectionResult = null
            result.success(false)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO_PERMISSION) {
            val pending = pendingProjectionResult
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pending != null) {
                    pendingProjectionResult = null
                    requestMediaProjection(pending)
                }
            } else {
                pendingProjectionResult = null
                pending?.success(false)
            }
        }
    }

    @Deprecated("Required for API compatibility below 33")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQ_MEDIA_PROJECTION) {
            val pending = pendingProjectionResult
            pendingProjectionResult = null

            if (resultCode == Activity.RESULT_OK && data != null) {
                Log.d(TAG, "MediaProjection granted — starting SpeechCaptureService")
                val i = Intent(this, SpeechCaptureService::class.java).apply {
                    putExtra(SpeechCaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(SpeechCaptureService.EXTRA_RESULT_DATA, data)
                }
                startForegroundServiceCompat(i)
                pending?.success(true)
            } else {
                Log.w(TAG, "MediaProjection denied (resultCode=$resultCode)")
                pending?.success(false)
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun startForegroundServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun deliverPendingFailure() {
        val stale = pendingProjectionResult
        if (stale != null) {
            pendingProjectionResult = null
            try { stale.success(false) } catch (_: Exception) {}
        }
    }
}
