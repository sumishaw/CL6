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
 * OverlayService — word-by-word live subtitle strip
 *
 * Display contract:
 *   • Incoming Hindi text is split into words and appended one-by-one
 *     to a 2-line TextView (≈80 ms per word → feels like live captions).
 *   • When 2 lines are full → stop appending → 6 s reading pause.
 *   • After the pause: clear both lines, feed queued words, repeat.
 *   • If no new text arrives for SILENCE_MS → fade out the overlay.
 *   • New text during a reading pause is queued and shown after the pause.
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

    // ── Timing ────────────────────────────────────────────────────────────────
    // Word reveal: ~80 ms between words → natural reading pace for Hindi
    private val WORD_INTERVAL_MS  = 80L
    // Reading pause after 2 lines fill up
    private val READ_PAUSE_MS     = 6_000L
    // Silence: if no new translation for this long → fade out
    private val SILENCE_MS        = 4_000L
    // Max lines visible at once
    private val MAX_LINES         = 2

    private var windowManager: WindowManager?              = null
    private var overlayView:   View?                       = null
    private var subtitleTv:    TextView?                   = null
    private var params:        WindowManager.LayoutParams? = null
    private val mainHandler    = Handler(Looper.getMainLooper())

    @Volatile private var running   = true
    @Volatile private var viewAdded = false

    // Word queue — incoming words waiting to be revealed
    private val wordQueue  = ArrayDeque<String>(256)

    // Words currently displayed, split by line
    private val line1Words = StringBuilder()
    private val line2Words = StringBuilder()
    private var onLine2    = false   // whether we're filling line 2

    // State flags
    @Volatile private var isPaused    = false  // in 6-s reading pause
    @Volatile private var isVisible   = false  // overlay alpha > 0

    // Runnables
    private var wordTickRunnable:  Runnable? = null
    private var pauseEndRunnable:  Runnable? = null
    private var silenceRunnable:   Runnable? = null

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        mainHandler.post { if (running) buildOverlay() }

        pushCallback = { _, hindi ->
            mainHandler.post { onNewText(hindi) }
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

    // ── Text ingestion ────────────────────────────────────────────────────────

    /**
     * Called on main thread every time whisper pushes a new translation.
     * Splits into words and enqueues them; restarts the word ticker if idle.
     */
    private fun onNewText(hindi: String) {
        if (hindi.isBlank()) return

        // Deduplicate: strip any words already at the tail of the queue
        val words = hindi.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return

        // Avoid re-queueing the same tail we already have
        val lastQueued = wordQueue.lastOrNull()
        val startIdx = if (lastQueued != null) {
            // Find the overlap: skip words already queued from the end
            val overlap = words.indexOfLast { it == lastQueued }
            if (overlap >= 0) overlap + 1 else 0
        } else 0

        for (i in startIdx until words.size) {
            wordQueue.addLast(words[i])
        }

        // Reschedule silence timer — we just got new content
        rescheduleSilence()

        // Make overlay visible
        showOverlay()

        // Start word ticker if not already running and not in reading pause
        if (!isPaused) {
            scheduleNextWord()
        }
    }

    // ── Word-by-word ticker ───────────────────────────────────────────────────

    private fun scheduleNextWord() {
        wordTickRunnable?.let { mainHandler.removeCallbacks(it) }
        if (!running || isPaused) return

        wordTickRunnable = Runnable {
            if (!running || isPaused) return@Runnable
            if (wordQueue.isEmpty()) return@Runnable   // wait for more words

            val word = wordQueue.removeFirst()
            appendWord(word)
        }
        mainHandler.postDelayed(wordTickRunnable!!, WORD_INTERVAL_MS)
    }

    /**
     * Appends one word to the current display, managing line breaks and the
     * 2-line read-pause cycle.
     */
    private fun appendWord(word: String) {
        if (!onLine2) {
            // Filling line 1
            if (line1Words.isNotEmpty()) line1Words.append(' ')
            line1Words.append(word)

            val l1text = line1Words.toString()
            subtitleTv?.text = l1text

            // Check if line 1 is visually full (TextView wraps it)
            if (isLine1Full()) {
                onLine2 = true
                // Don't trigger pause yet — fill line 2 first
            }
        } else {
            // Filling line 2
            if (line2Words.isNotEmpty()) line2Words.append(' ')
            line2Words.append(word)

            subtitleTv?.text = "${line1Words}\n${line2Words}"

            // Check if line 2 is visually full → trigger reading pause
            if (isLine2Full()) {
                startReadingPause()
                return   // don't schedule next word yet; pause will do it
            }
        }

        // Continue ticking words
        scheduleNextWord()
    }

    // ── Line-full detection ───────────────────────────────────────────────────

    /**
     * Returns true if the TextView's text wraps beyond 1 line.
     * We use StaticLayout-based measurement so it works regardless of whether
     * the view has been laid out yet.
     */
    private fun isLine1Full(): Boolean {
        val tv = subtitleTv ?: return false
        if (tv.width <= 0) return line1Words.length > 28   // rough fallback
        return tv.lineCount > 1
    }

    private fun isLine2Full(): Boolean {
        val tv = subtitleTv ?: return false
        if (tv.width <= 0) return (line1Words.length + line2Words.length) > 56
        return tv.lineCount > MAX_LINES
    }

    // ── Reading pause (6 s) ───────────────────────────────────────────────────

    private fun startReadingPause() {
        isPaused = true
        pauseEndRunnable?.let { mainHandler.removeCallbacks(it) }
        pauseEndRunnable = Runnable {
            // Clear display, reset line state, resume word ticker
            line1Words.clear()
            line2Words.clear()
            onLine2    = false
            isPaused   = false
            subtitleTv?.text = ""

            if (wordQueue.isNotEmpty()) {
                scheduleNextWord()
            }
        }
        mainHandler.postDelayed(pauseEndRunnable!!, READ_PAUSE_MS)
    }

    // ── Silence detection — fade out after SILENCE_MS with no new text ────────

    private fun rescheduleSilence() {
        silenceRunnable?.let { mainHandler.removeCallbacks(it) }
        silenceRunnable = Runnable {
            // Fade out and reset everything
            subtitleTv?.animate()
                ?.alpha(0f)
                ?.setDuration(800)
                ?.withEndAction {
                    line1Words.clear()
                    line2Words.clear()
                    wordQueue.clear()
                    onLine2  = false
                    isPaused = false
                    pauseEndRunnable?.let { mainHandler.removeCallbacks(it) }
                    wordTickRunnable?.let  { mainHandler.removeCallbacks(it) }
                    subtitleTv?.text  = ""
                    subtitleTv?.alpha = 1f
                    isVisible = false
                }
                ?.start()
        }
        mainHandler.postDelayed(silenceRunnable!!, SILENCE_MS)
    }

    private fun showOverlay() {
        if (!isVisible) {
            subtitleTv?.apply {
                alpha = 0f
                animate().alpha(1f).setDuration(150).start()
            }
            isVisible = true
        }
    }

    // ── Overlay construction ──────────────────────────────────────────────────

    private fun buildOverlay() {
        try {
            val container = android.widget.FrameLayout(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
            }

            subtitleTv = TextView(this).apply {
                text     = ""
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setLineSpacing(0f, 1.2f)
                maxLines = MAX_LINES
                gravity  = Gravity.START or Gravity.CENTER_VERTICAL
                setShadowLayer(10f, 1f, 1f, Color.BLACK)
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(dp(12), dp(4), dp(12), dp(4))
            }

            container.addView(
                subtitleTv,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.START or Gravity.CENTER_VERTICAL
                )
            )

            overlayView = container

            val sw = resources.displayMetrics.widthPixels

            params = WindowManager.LayoutParams(
                sw,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
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
            var initX     = 0;  var initY     = 0
            container.setOnTouchListener { _, ev ->
                val p = params ?: return@setOnTouchListener false
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawX = ev.rawX; startRawY = ev.rawY
                        initX     = p.x;     initY     = p.y
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

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(
                CHANNEL_ID,
                "Caption Lens Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
             .also { getSystemService(NotificationManager::class.java)
                         .createNotificationChannel(it) }
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Caption Lens Active")
            .setContentText("Hindi subtitle overlay running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setSilent(true)
            .build()
}
