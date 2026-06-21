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
import java.util.concurrent.atomic.AtomicLong

/**
 * OverlayService — Hindi subtitle overlay with speed control
 *
 * Speed modes (holdMs):
 *   0    = Live   — instant display, mirrors UI subtitle exactly
 *   2000 = Fastest
 *   4000 = Fast
 *   6000 = Average  (default)
 *   8000 = Slow
 *  10000 = Slowest
 *
 * FIFO + token — no dropping, no stale display after LC gone.
 */
class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "nihongo_overlay"
        const val NOTIF_ID   = 1

        // holdMs lives in companion — written by setHoldMs, read by service instance
        @Volatile @JvmField var holdMs: Long = 6_000L   // default: Average

        @Volatile var latestOriginal = ""
        @Volatile var latestHindi    = ""

        @Volatile private var instance: OverlayService? = null

        fun updateText(original: String, hindi: String) {
            latestOriginal = original
            latestHindi    = hindi
            instance?.handler?.post { instance?.onNewHindi(hindi) }
        }

        fun clearQueue() {
            instance?.handler?.post { instance?.onClear() }
        }

        fun setHoldMs(ms: Long) {
            val clamped = ms.coerceIn(0, 15_000)
            holdMs = clamped
            if (clamped == 0L) {
                instance?.handler?.post { instance?.switchToLive() }
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    // FIFO queue with tokens
    private val tokenCounter  = AtomicLong(0)
    @Volatile private var expectedToken = 0L
    data class Item(val token: Long, val text: String)
    private val queue = ArrayDeque<Item>()

    // Display state (main thread only)
    private var active       = false
    private var wordRunnable: Runnable? = null
    private var holdRunnable: Runnable? = null
    private var silenceRunnable: Runnable? = null

    // Views
    private var windowManager: WindowManager?              = null
    private var textView:      TextView?                   = null
    private var overlayView:   View?                       = null
    private var params:        WindowManager.LayoutParams? = null

    @Volatile private var alive     = true
    @Volatile private var viewAdded = false

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        handler.post { buildOverlay() }
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int) = START_STICKY
    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        alive = false; instance = null
        handler.removeCallbacksAndMessages(null)
        queue.clear()
        if (viewAdded) {
            try { windowManager?.removeView(overlayView) } catch (_: Exception) {}
            viewAdded = false
        }
        super.onDestroy()
    }

    // ── New translation arrived ───────────────────────────────────────────────

    private fun onNewHindi(hindi: String) {
        if (hindi.isBlank()) return

        // Speak aloud if TTS enabled

        // LIVE mode: show immediately, no queue, no animation
        if (holdMs == 0L) {
            cancelTimers()
            active = false
            queue.clear()
            setTextDirect(hindi.trim())
            reschedSilence()
            return
        }

        // Timed modes: FIFO queue
        val token = tokenCounter.incrementAndGet()
        queue.addLast(Item(token, hindi.trim()))
        reschedSilence()
        if (!active) advance()
    }

    private fun onClear() {
        expectedToken = tokenCounter.get() + 1
        queue.clear()
        cancelTimers()
        active = false
        fadeOut()
    }

    // Called when user switches TO live while something is showing
    private fun switchToLive() {
        cancelTimers()
        active = false
        queue.clear()
    }

    // ── Display loop ──────────────────────────────────────────────────────────

    private fun advance() {
        cancelTimers()

        // Drain stale tokens
        while (queue.isNotEmpty() && queue.first().token < expectedToken)
            queue.removeFirst()

        val item = queue.removeFirstOrNull() ?: run { active = false; return }
        if (item.token < expectedToken) { advance(); return }

        active = true
        val words      = item.text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val totalWords = words.size.coerceAtLeast(1)
        var index      = 0
        var built      = ""
        val snap       = holdMs   // capture speed at sentence start

        // ms per word: words fill in 60% of hold time so 40% remains for reading
        val msPerWord  = ((snap * 0.6) / totalWords).toLong().coerceIn(80, 500)

        fun tick() {
            wordRunnable = null
            if (!alive || item.token < expectedToken) { active = false; fadeOut(); return }

            // If user switched to live mid-sentence, stop word-by-word
            if (holdMs == 0L) { active = false; return }

            if (index >= words.size) {
                // All words shown — hold for remaining time then advance
                val elapsed  = msPerWord * totalWords
                val holdTime = (snap - elapsed).coerceAtLeast(500L)
                holdRunnable = Runnable {
                    holdRunnable = null
                    if (!alive) return@Runnable
                    if (item.token < expectedToken) { active = false; fadeOut(); return@Runnable }
                    fadeOut()
                    active = false
                    if (queue.isNotEmpty()) handler.postDelayed({ advance() }, 120)
                }
                handler.postDelayed(holdRunnable!!, holdTime)
                return
            }

            built = if (built.isEmpty()) words[index] else "$built ${words[index]}"
            index++
            setTextDirect(built)

            wordRunnable = Runnable { tick() }
            handler.postDelayed(wordRunnable!!, msPerWord)
        }

        tick()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun setTextDirect(text: String) {
        val tv = textView ?: return
        tv.text = text
        if (tv.alpha < 0.5f) {
            tv.animate().cancel()
            tv.animate().alpha(1f).setDuration(100).start()
        }
    }

    private fun fadeOut() {
        textView?.animate()?.cancel()
        textView?.animate()?.alpha(0f)?.setDuration(250)
            ?.withEndAction { textView?.text = "" }?.start()
    }

    private fun cancelTimers() {
        wordRunnable?.let { handler.removeCallbacks(it) }
        holdRunnable?.let { handler.removeCallbacks(it) }
        wordRunnable = null; holdRunnable = null
    }

    private fun reschedSilence() {
        silenceRunnable?.let { handler.removeCallbacks(it) }
        silenceRunnable = Runnable {
            if (queue.isEmpty() && !active) fadeOut()
        }
        handler.postDelayed(silenceRunnable!!, 10_000)
    }

    // ── Overlay window ────────────────────────────────────────────────────────

    private fun buildOverlay() {
        try {
            val sw = resources.displayMetrics.widthPixels
            val tv = TextView(this).apply {
                typeface  = Typeface.DEFAULT_BOLD
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTextColor(Color.WHITE)
                setShadowLayer(14f, 1f, 3f, Color.BLACK)
                maxLines   = 2
                background = null
                setPadding(dp(8), dp(4), dp(8), dp(4))
                alpha = 0f; text = ""
            }
            textView = tv; overlayView = tv
            params = WindowManager.LayoutParams(
                (sw * 0.92).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(90) }

            var sx = 0f; var sy = 0f; var ix = 0; var iy = 0
            tv.setOnTouchListener { _, ev ->
                val p = params ?: return@setOnTouchListener false
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> { sx=ev.rawX; sy=ev.rawY; ix=p.x; iy=p.y }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = ix+(ev.rawX-sx).toInt(); p.y = iy-(ev.rawY-sy).toInt()
                        if (viewAdded) try { windowManager?.updateViewLayout(overlayView,p) }
                        catch(_:Exception){}
                    }
                }
                true
            }
            windowManager?.addView(overlayView, params)
            viewAdded = true
        } catch (e: Exception) {
            android.util.Log.e("OverlayService","build: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            NotificationChannel(CHANNEL_ID, "Caption Lens Overlay",
                NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
                .also { getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(it) }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Caption Lens Active")
            .setContentText("Hindi subtitle overlay running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true).setSilent(true).build()
}
