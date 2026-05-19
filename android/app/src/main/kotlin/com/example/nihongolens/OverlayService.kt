package com.example.nihongolens

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.*
import android.util.TypedValue
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat

/**
 * OverlayService — REWRITTEN v4
 *
 * ROOT CAUSES FIXED:
 *
 * FIX 1 — SENTENCES/WORDS DROPPED BY BAD DEDUP:
 *   Old code: words.indexOfLast { it == lastQueued }
 *   If the last word in the queue happened to match ANY word in the new sentence
 *   (e.g. common Hindi words "है", "का", "में", "और"), everything before that
 *   match was silently skipped. For a 10-word sentence, 8 words could be dropped.
 *   Fix: Dedup completely removed. Every translation is shown in full, no word
 *   skipping. The IDENTICAL-text check in SpeechCaptureService already prevents
 *   true duplicates from being sent.
 *
 * FIX 2 — READING PAUSE REPLACES CONTENT:
 *   Old code: During 3s reading pause, new translations replaced the wordQueue
 *   entirely. If 2 translations arrived during the pause (normal at 2s chunks),
 *   the first was lost completely.
 *   Fix: Reading pause removed entirely. Text is shown instantly as a full
 *   sentence, cleared after DISPLAY_MS, then next sentence shown. No word-by-word
 *   ticker — it was the source of timing bugs and lost words.
 *
 * FIX 3 — WORD-BY-WORD TICKER CAUSED DISPLAY GAPS:
 *   Revealing 55ms/word meant a 12-word sentence took 660ms to fully appear.
 *   During that 660ms, a new translation could arrive and corrupt/replace the
 *   queue mid-display. Fast dialogue (Bollywood 5-7 words/sec) was impossible.
 *   Fix: Full sentence displayed instantly. No ticker. No queue corruption.
 *
 * NEW DISPLAY MODEL:
 *   Each translation is shown as complete text immediately.
 *   After DISPLAY_MS (2500ms) it fades and is replaced by the next one.
 *   If a new translation arrives before DISPLAY_MS, it replaces immediately.
 *   Source text shown above Hindi text in smaller grey font.
 *   Silence for SILENCE_MS (5s) fades the overlay out.
 */
class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "nihongo_overlay"
        const val NOTIF_ID   = 1

        @Volatile var latestOriginal = ""
        @Volatile var latestHindi    = ""

        @Volatile private var pushCallback: ((String, String) -> Unit)? = null

        fun updateText(original: String, hindi: String) {
            latestOriginal = original
            latestHindi    = hindi
            pushCallback?.invoke(original, hindi)
        }
    }

    // How long each subtitle stays before fading (if no new one arrives).
    // Matches the 3s chunk duration so every subtitle is readable before
    // the next one arrives.
    private val DISPLAY_MS = 3_500L
    // Fade out after this much silence
    private val SILENCE_MS = 6_000L

    private var windowManager: WindowManager?              = null
    private var overlayView:   View?                       = null
    private var srcTv:         TextView?                   = null   // source language text
    private var hindiTv:       TextView?                   = null   // Hindi translation
    private var params:        WindowManager.LayoutParams? = null
    private val mainHandler    = Handler(Looper.getMainLooper())

    @Volatile private var running   = true
    @Volatile private var viewAdded = false
    @Volatile private var isVisible = false

    private var displayRunnable:  Runnable? = null
    private var silenceRunnable:  Runnable? = null

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        mainHandler.post { if (running) buildOverlay() }

        pushCallback = { original, hindi ->
            mainHandler.post { onNewText(original, hindi) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running      = false
        pushCallback = null
        mainHandler.removeCallbacksAndMessages(null)
        if (viewAdded) {
            try { windowManager?.removeView(overlayView) } catch (_: Exception) {}
            viewAdded = false
        }
        super.onDestroy()
    }

    private fun onNewText(original: String, hindi: String) {
        // Show Hindi if available, fall back to source text so overlay is never blank
        val displayText = when {
            hindi.isNotBlank() -> hindi.trim()
            original.isNotBlank() -> original.trim()  // fallback: show source while CT2 warms up
            else -> return
        }

        displayRunnable?.let { mainHandler.removeCallbacks(it) }
        silenceRunnable?.let { mainHandler.removeCallbacks(it) }

        srcTv?.text   = ""
        hindiTv?.text = displayText

        showOverlay()
        rescheduleSilence()

        displayRunnable = Runnable {
            srcTv?.text   = ""
            hindiTv?.text = ""
        }
        mainHandler.postDelayed(displayRunnable!!, DISPLAY_MS)
    }

    private fun showOverlay() {
        if (!isVisible) {
            overlayView?.apply {
                alpha = 0f
                animate().alpha(1f).setDuration(120).start()
            }
            isVisible = true
        } else {
            // Already visible — ensure full opacity (in case fade-out was in progress)
            overlayView?.animate()?.cancel()
            overlayView?.alpha = 1f
        }
    }

    private fun rescheduleSilence() {
        silenceRunnable?.let { mainHandler.removeCallbacks(it) }
        silenceRunnable = Runnable {
            overlayView?.animate()
                ?.alpha(0f)
                ?.setDuration(800)
                ?.withEndAction {
                    srcTv?.text   = ""
                    hindiTv?.text = ""
                    overlayView?.alpha = 1f
                    isVisible = false
                }
                ?.start()
        }
        mainHandler.postDelayed(silenceRunnable!!, SILENCE_MS)
    }

    private fun buildOverlay() {
        try {
            // Root container — NO background, transparent, text only
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                // No background — pure transparent, text floats over video
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(dp(16), dp(4), dp(16), dp(4))
            }

            // Source language text (small, light grey, strong shadow for readability)
            srcTv = TextView(this).apply {
                text     = ""
                typeface = Typeface.DEFAULT
                setTextColor(Color.parseColor("#DDDDDD"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                maxLines = 2
                // Strong multi-layer shadow so text is readable over any background
                setShadowLayer(8f, 0f, 0f, Color.BLACK)
                paint.isFakeBoldText = false
            }

            // Hindi translation — large, bright white, very strong shadow
            hindiTv = TextView(this).apply {
                text     = ""
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                setLineSpacing(dp(2).toFloat(), 1f)
                maxLines = 3
                // Thick black shadow gives outline effect — readable on any background
                setShadowLayer(12f, 0f, 0f, Color.BLACK)
            }

            container.addView(srcTv,   LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
            container.addView(hindiTv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))

            overlayView = container

            val sw = resources.displayMetrics.widthPixels

            params = WindowManager.LayoutParams(
                sw,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                x = 0
                y = dp(80)
            }

            // Draggable
            var startRawX = 0f; var startRawY = 0f
            var initX = 0;      var initY = 0
            container.setOnTouchListener { _, ev ->
                val p = params ?: return@setOnTouchListener false
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawX = ev.rawX; startRawY = ev.rawY
                        initX = p.x;         initY = p.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = initX + (ev.rawX - startRawX).toInt()
                        p.y = initY - (ev.rawY - startRawY).toInt()
                        if (viewAdded) try {
                            windowManager?.updateViewLayout(overlayView, p)
                        } catch (_: Exception) {}
                    }
                }
                true
            }

            windowManager?.addView(overlayView, params)
            viewAdded = true

        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "buildOverlay error: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "Caption Lens Overlay", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
                .also { getSystemService(NotificationManager::class.java).createNotificationChannel(it) }
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Caption Lens Active")
            .setContentText("Hindi subtitle overlay running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true).setSilent(true).build()
}
