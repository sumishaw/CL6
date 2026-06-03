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
 * OverlayService — Hindi subtitle overlay
 *
 * FIFO + Token architecture:
 *  - tokenCounter: monotonically increasing, one per pushed translation
 *  - expectedToken: only items with token >= expectedToken are shown
 *  - clearQueue(): advances expectedToken past all pending → stale items silently skipped
 *  - advance(): always sets showing=false when queue empty → next item kicks immediately
 *  - One readRunnable timer at a time, carries capturedToken for stale-check
 *
 * Timing tuned for beam_size=3 CT2 (5-8s translation):
 *  READ_MS_NORMAL  = 4s  — comfortable reading time per subtitle
 *  READ_MS_BACKLOG = 2s  — catch up when 3+ items queued
 *  SILENCE_MS      = 8s  — fade after speech ends
 */
class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "nihongo_overlay"
        const val NOTIF_ID   = 1

        @Volatile var latestOriginal = ""
        @Volatile var latestHindi    = ""
        @Volatile private var pushCallback:  ((String, String) -> Unit)? = null
        @Volatile private var clearCallback: (() -> Unit)?               = null

        fun updateText(original: String, hindi: String) {
            latestOriginal = original; latestHindi = hindi
            pushCallback?.invoke(original, hindi)
        }
        fun clearQueue() { clearCallback?.invoke() }
    }

    private val tokenCounter = AtomicLong(0)
    private var expectedToken = 0L

    data class Item(val token: Long, val text: String)
    private val queue = ArrayDeque<Item>()

    private var currentText = ""
    private var showing     = false

    private val READ_MS_NORMAL  = 4_000L
    private val READ_MS_BACKLOG = 2_000L
    private val SILENCE_MS      = 8_000L

    private var readRunnable:    Runnable? = null
    private var silenceRunnable: Runnable? = null

    private var windowManager: WindowManager?              = null
    private var textView:      TextView?                   = null
    private var overlayView:   View?                       = null
    private var params:        WindowManager.LayoutParams? = null
    private val handler        = Handler(Looper.getMainLooper())

    @Volatile private var running   = true
    @Volatile private var viewAdded = false

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, buildNotification())

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        handler.post { if (running) buildOverlay() }
        pushCallback  = { _, hindi -> handler.post { onPush(hindi) } }
        clearCallback = { handler.post { onClear() } }
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int) = START_STICKY
    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        pushCallback = null; clearCallback = null
        handler.removeCallbacksAndMessages(null)
        queue.clear()
        if (viewAdded) {
            try { windowManager?.removeView(overlayView) } catch (_: Exception) {}
            viewAdded = false
        }
        super.onDestroy()
    }

    // ── Queue ─────────────────────────────────────────────────────────────────

    private fun onPush(hindi: String) {
        if (hindi.isBlank()) return
        val t = hindi.trim()
        // Skip exact duplicate when queue is empty — no point showing same text again
        if (t == currentText && queue.isEmpty()) { reschedSilence(); return }

        val token = tokenCounter.incrementAndGet()
        queue.addLast(Item(token, t))   // FIFO
        reschedSilence()
        if (!showing) advance()          // kick display if idle
    }

    private fun onClear() {
        // Advance expectedToken past all pending — stale items skipped in advance()
        expectedToken = tokenCounter.get() + 1
        queue.clear()
        cancelTimer()
        showing = false   // allow next onPush to kick display immediately
        android.util.Log.d("Overlay", "cleared expectedToken=$expectedToken")
    }

    // ── Display loop ──────────────────────────────────────────────────────────

    private fun advance() {
        cancelTimer()

        // Drain stale items
        while (queue.isNotEmpty() && queue.first().token < expectedToken)
            queue.removeFirst()

        if (queue.isEmpty()) {
            showing = false   // ← CRITICAL: lets next onPush() kick display
            return
        }

        val item = queue.removeFirst()
        if (item.token < expectedToken) { showing = false; return }

        currentText = item.text
        showing     = true
        display(item.text)

        val cap    = item.token
        val waitMs = if (queue.size >= 3) READ_MS_BACKLOG else READ_MS_NORMAL
        readRunnable = Runnable {
            readRunnable = null
            if (!running) return@Runnable
            if (cap < expectedToken) { showing = false; return@Runnable }
            advance()
        }
        handler.postDelayed(readRunnable!!, waitMs)
    }

    private fun cancelTimer() {
        readRunnable?.let { handler.removeCallbacks(it) }
        readRunnable = null
    }

    private fun display(text: String) {
        val tv = textView ?: return
        tv.animate().cancel()
        tv.alpha = 0f; tv.text = text
        tv.animate().alpha(1f).setDuration(150).start()
    }

    private fun reschedSilence() {
        silenceRunnable?.let { handler.removeCallbacks(it) }
        silenceRunnable = Runnable {
            if (!running || queue.isNotEmpty()) return@Runnable
            cancelTimer()
            textView?.animate()?.alpha(0f)?.setDuration(400)?.withEndAction {
                currentText = ""; showing = false
            }?.start()
        }
        handler.postDelayed(silenceRunnable!!, SILENCE_MS)
    }

    // ── Overlay window ────────────────────────────────────────────────────────

    private fun buildOverlay() {
        try {
            val sw = resources.displayMetrics.widthPixels
            val tv = TextView(this).apply {
                typeface  = Typeface.DEFAULT_BOLD
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(Color.WHITE)
                setShadowLayer(10f, 0f, 2f, Color.BLACK)
                maxLines  = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.argb(190, 0, 0, 0))
                }
                setPadding(dp(14), dp(10), dp(14), dp(10))
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
                    MotionEvent.ACTION_DOWN -> { sx = ev.rawX; sy = ev.rawY; ix = p.x; iy = p.y }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = ix + (ev.rawX - sx).toInt()
                        p.y = iy - (ev.rawY - sy).toInt()
                        if (viewAdded) try { windowManager?.updateViewLayout(overlayView, p) }
                        catch (_: Exception) {}
                    }
                }
                true
            }

            windowManager?.addView(overlayView, params)
            viewAdded = true
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "build: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            NotificationChannel(CHANNEL_ID, "Caption Lens Overlay", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
                .also { getSystemService(NotificationManager::class.java).createNotificationChannel(it) }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Caption Lens Active")
            .setContentText("Hindi subtitle overlay running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true).setSilent(true).build()
}
