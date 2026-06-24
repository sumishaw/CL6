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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * OverlayService — Hindi subtitle overlay with dedicated FIFO backlog
 *
 * FIFO backlog (unbounded LinkedBlockingQueue):
 *   - Every translation is enqueued — nothing is ever dropped
 *   - Items displayed in order at holdMs pace
 *   - Token system: LC going away (onClear) advances expectedToken,
 *     draining stale items silently
 *
 * Speed modes (holdMs set via setHoldMs):
 *   0     = Live   — show instantly, no hold, no word-by-word
 *   2000  = Fastest
 *   4000  = Fast
 *   6000  = Average (default)
 *   8000  = Slow
 *   10000 = Slowest
 *
 * Word-by-word: words appear at msPerWord = holdMs*0.5/totalWords
 * so sentence fills in first half of hold period, second half is reading time.
 */
class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "nihongo_overlay"
        const val NOTIF_ID   = 1

        @Volatile @JvmField var holdMs: Long = 6_000L

        @Volatile var latestOriginal = ""
        @Volatile var latestHindi    = ""

        @Volatile private var instance: OverlayService? = null

        fun updateText(original: String, hindi: String) {
            latestOriginal = original; latestHindi = hindi
            // Always update subtitle immediately when translation arrives
            // TTS plays asynchronously — subtitle and TTS run in parallel
            instance?.handler?.post { instance?.onNewHindi(hindi) }
        }

        // Called by TTS play worker — subtitle already showing, just ensure visible
        fun showTtsText(hindi: String) {
            // No-op: subtitle already shown by updateText above
            // Kept for future use (e.g. highlight current word)
        }

        // Called by TTS play worker when speech ends
        fun clearTtsText() {
            // No-op: subtitle persists until next translation arrives
            // OverlayService backlog timer handles fade-out via reschedSilence
        }
        fun clearQueue() {
            instance?.handler?.post { instance?.onClear() }
        }
        fun setHoldMs(ms: Long) {
            val v = ms.coerceIn(0, 15_000)
            holdMs = v
            if (v == 0L) instance?.handler?.post { instance?.switchToLive() }
        }
    }

    // ── FIFO backlog — unbounded, never drops sentences ───────────────────────
    private val tokenCounter  = AtomicLong(0)
    @Volatile private var expectedToken = 0L

    data class Item(val token: Long, val text: String)
    private val backlog = LinkedBlockingQueue<Item>()  // FIFO, unbounded

    // Display state (main thread only)
    private var active       = false
    private var wordRunnable: Runnable? = null
    private var holdRunnable: Runnable? = null
    private var silenceRunnable: Runnable? = null

    val handler = Handler(Looper.getMainLooper())

    // Views
    private var windowManager: WindowManager?              = null
    var textView:      TextView?                   = null
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
        backlog.clear()
        if (viewAdded) {
            try { windowManager?.removeView(overlayView) } catch (_: Exception) {}
            viewAdded = false
        }
        super.onDestroy()
    }

    // ── New translation ───────────────────────────────────────────────────────

    private fun onNewHindi(hindi: String) {
        if (hindi.isBlank()) return
        val token = tokenCounter.incrementAndGet()
        backlog.offer(Item(token, hindi.trim()))
        reschedSilence()
        if (!active) advance()
    }

    private fun onClear() {
        expectedToken = tokenCounter.get() + 1
        backlog.clear()
        cancelTimers()
        active = false
        fadeOut()
    }

    private fun switchToLive() {
        cancelTimers(); active = false; backlog.clear()
    }

    // ── Display loop ──────────────────────────────────────────────────────────
    // Shows FULL sentence immediately. Holds until next sentence or holdMs expires.

    private fun advance() {
        cancelTimers()

        // Drain stale tokens
        while (true) {
            val head = backlog.peek() ?: break
            if (head.token >= expectedToken) break
            backlog.poll()
        }

        val item = backlog.poll() ?: run { active = false; return }
        if (item.token < expectedToken) { advance(); return }

        active = true

        // Show FULL sentence immediately — no word-by-word
        setTextDirect(item.text)

        // Hold time: use holdMs, or if Live mode (0), just show until next arrives
        val hold = if (holdMs == 0L) 8_000L else holdMs   // 8s default in Live mode

        holdRunnable = Runnable {
            holdRunnable = null
            if (!alive) return@Runnable
            if (item.token < expectedToken) { active = false; fadeOut(); advance(); return@Runnable }
            // If more items queued, advance immediately; else fade out
            if (backlog.isNotEmpty()) {
                active = false; advance()
            } else {
                fadeOut(); active = false
            }
        }
        handler.postDelayed(holdRunnable!!, hold)
    }
    }

    // ── View helpers ──────────────────────────────────────────────────────────

    private fun setTextDirect(text: String) {
        val tv = textView ?: return
        tv.maxLines = 10   // never truncate — show complete sentence
        tv.text = text
        if (tv.alpha < 0.5f) {
            tv.animate().cancel()
            tv.animate().alpha(1f).setDuration(120).start()
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
        silenceRunnable = Runnable { if (backlog.isEmpty() && !active) fadeOut() }
        handler.postDelayed(silenceRunnable!!, 12_000)
    }

    // ── Overlay window ────────────────────────────────────────────────────────

    private fun buildOverlay() {
        try {
            val sw = resources.displayMetrics.widthPixels
            val tv = TextView(this).apply {
                typeface  = Typeface.DEFAULT_BOLD
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)   // smaller = more words per line
                setTextColor(Color.WHITE)
                setShadowLayer(12f, 1f, 2f, Color.BLACK)
                maxLines   = 3                                  // 3 lines ≈ 35-45 Hindi words
                setLineSpacing(2f, 1.1f)
                // Semi-transparent dark background — words readable over any video
                setBackgroundColor(android.graphics.Color.argb(160, 0, 0, 0))
                setPadding(dp(10), dp(6), dp(10), dp(6))
                alpha = 0f; text = ""
            }
            textView = tv; overlayView = tv
            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,        // full width
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(60) }

            var sx = 0f; var sy = 0f; var ix = 0; var iy = 0
            tv.setOnTouchListener { _, ev ->
                val p = params ?: return@setOnTouchListener false
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> { sx=ev.rawX; sy=ev.rawY; ix=p.x; iy=p.y }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = ix+(ev.rawX-sx).toInt(); p.y = iy-(ev.rawY-sy).toInt()
                        if (viewAdded) try { windowManager?.updateViewLayout(overlayView,p) }
                        catch (_:Exception){}
                    }
                }
                true
            }
            windowManager?.addView(overlayView, params)
            viewAdded = true
        } catch (e: Exception) { android.util.Log.e("OverlayService","build: ${e.message}") }
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
