package dev.apkwhispr

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import dev.apkwhispr.BubbleView.State
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Wispr-Flow-style dictation: a pill floats above the keyboard whenever a text field is focused.
 * Tap = start/stop hands-free, hold = push-to-talk, drag = move it up/down.
 * Say "<agent name>, ..." first to talk to the assistant instead of dictating.
 */
class FlowService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var cfg: Cfg
    private lateinit var wm: WindowManager
    private lateinit var bubble: BubbleView
    private lateinit var lp: WindowManager.LayoutParams

    private var recorder: MediaRecorder? = null
    private var recStart = 0L
    private val audio by lazy { File(cacheDir, "rec.m4a") }
    private var target: AccessibilityNodeInfo? = null
    private var imeTop: Int? = null
    private var lastEditable: AccessibilityNodeInfo? = null
    private var pending = false

    override fun onServiceConnected() {
        cfg = Cfg(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        bubble = BubbleView(this).apply { visibility = View.GONE }
        lp = WindowManager.LayoutParams(
            bubble.widthFor(State.IDLE), bubble.h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = bubble.dp(10f)
        }
        wm.addView(bubble, lp)
        setupTouch()
    }

    override fun onDestroy() {
        cancelRec()
        runCatching { wm.removeView(bubble) }
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // ---------------------------------------------------------------- visibility

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                e.source?.takeIf { it.isEditable }?.let { lastEditable = it }
                // The keyboard usually slides in after focus lands; re-check once it has settled.
                main.postDelayed(check, 450)
                main.postDelayed(check, 1100)
            }
        }
        // Throttle (not debounce) so a constant stream of content changes can't starve the check.
        if (!pending) {
            pending = true
            main.postDelayed(check, 100)
        }
    }

    private val check = Runnable {
        pending = false
        if (!::bubble.isInitialized) return@Runnable
        val ime = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        imeTop = ime?.let { Rect().also(it::getBoundsInScreen).top }
        if (bubble.state != State.IDLE) return@Runnable
        val f = focusedEditable()
        val show = ime != null && f != null && !f.isPassword
        if (BuildConfig.DEBUG || Log.isLoggable(TAG, Log.DEBUG))
            Log.d(TAG, "check ime=${ime != null} focus=${f?.packageName}/${f?.className} show=$show")
        bubble.visibility = if (show) View.VISIBLE else View.GONE
        if (show) place()
    }

    /** Focused editable node, trying the global lookup, then each app window, then the last one we saw. */
    private fun focusedEditable(): AccessibilityNodeInfo? {
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }?.let { return it }
        for (w in windows) {
            if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            w.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }?.let { return it }
        }
        return lastEditable?.takeIf { it.refresh() && it.isEditable && it.isFocused }
    }

    private fun place() {
        val screenH = resources.displayMetrics.heightPixels
        val anchor = imeTop ?: (screenH * 0.6f).toInt()
        val off = cfg.bubbleOffset.takeIf { it != Int.MIN_VALUE } ?: -(bubble.h + bubble.dp(64f))
        lp.y = (anchor + off).coerceIn(bubble.dp(48f), screenH - bubble.h)
        runCatching { wm.updateViewLayout(bubble, lp) }
    }

    private fun setState(s: State) {
        bubble.state = s
        val from = lp.width
        val to = bubble.widthFor(s)
        android.animation.ValueAnimator.ofInt(from, to).apply {
            duration = 180
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { lp.width = it.animatedValue as Int; runCatching { wm.updateViewLayout(bubble, lp) } }
            start()
        }
        if (s == State.IDLE) main.postDelayed(check, 200)
    }

    // ---------------------------------------------------------------- touch

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downY = 0f
        var startY = 0
        var dragging = false
        var holding = false
        val longPress = Runnable {
            if (bubble.state == State.IDLE && startRec()) holding = true
        }
        bubble.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY; startY = lp.y; dragging = false; holding = false
                    if (bubble.state == State.IDLE) main.postDelayed(longPress, 300)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!holding && bubble.state == State.IDLE && abs(e.rawY - downY) > slop) {
                        dragging = true; main.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        lp.y = (startY + (e.rawY - downY)).toInt()
                        runCatching { wm.updateViewLayout(bubble, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    when {
                        dragging -> cfg.bubbleOffset = lp.y - (imeTop ?: (resources.displayMetrics.heightPixels * 0.6f).toInt())
                        holding -> stopRec()
                        bubble.state == State.IDLE -> startRec()
                        bubble.state == State.REC -> stopRec()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    if (holding) cancelRec()
                }
            }
            true
        }
    }

    // ---------------------------------------------------------------- recording

    private fun startRec(): Boolean {
        if (cfg.key.isBlank()) return fail("Add your Groq API key in the Whispr app")
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return fail("Grant microphone access in the Whispr app")
        target = focusedEditable()
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(32_000)
            r.setOutputFile(audio.path)
            r.prepare()
            r.start()
        } catch (t: Throwable) {
            r.release()
            return fail("Mic unavailable: ${t.message}")
        }
        recorder = r
        recStart = SystemClock.uptimeMillis()
        bubble.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        setState(State.REC)
        main.post(meter)
        return true
    }

    private val meter = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            runCatching { bubble.pushLevel(r.maxAmplitude) }
            main.postDelayed(this, 50)
        }
    }

    /** Stops recording; returns true if a usable clip was captured. */
    private fun endRec(): Boolean {
        val r = recorder ?: return false
        recorder = null
        main.removeCallbacks(meter)
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        return ok && SystemClock.uptimeMillis() - recStart > 400
    }

    private fun cancelRec() {
        endRec()
        if (::bubble.isInitialized) setState(State.IDLE)
    }

    private fun stopRec() {
        if (!endRec()) { setState(State.IDLE); return }
        bubble.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        setState(State.BUSY)

        // Snapshot the field context on the main thread before going async.
        val node = liveTarget()
        val field = node?.let { fieldText(it) }.orEmpty()
        val sel = node?.let { selection(it, field) }
        val selected = sel?.let { field.substring(it.first, it.second) }.orEmpty()
        val app = node?.packageName?.toString().orEmpty()

        thread {
            val result = runCatching { process(field, selected, app) }
            main.post {
                setState(State.IDLE)
                result.onSuccess { if (it.isNotBlank()) insert(it) }
                    .onFailure { fail(it.message ?: "Something went wrong") }
            }
        }
    }

    private fun process(field: String, selected: String, app: String): String {
        val key = cfg.key
        val name = cfg.agentName
        val hint = (listOf(name) + cfg.dictionary).joinToString(", ")
        val raw = Groq.transcribe(key, audio, cfg.sttModel, cfg.language, hint)
        if (raw.isBlank() || raw.lowercase().trim('.', '!', ' ') in HALLUCINATIONS) return ""
        val command = wakeCommand(raw, name)
        return when {
            command != null -> Groq.chat(key, cfg.llmModel, Prompts.assistant(cfg.assistantPrompt, name),
                Prompts.assistantInput(command, app, field, selected))
            cfg.cleanup -> Groq.chat(key, cfg.llmModel, Prompts.cleanup(cfg.cleanupPrompt, cfg.dictionary),
                "<transcript>$raw</transcript>").removeSurrounding("<transcript>", "</transcript>").trim()
            else -> raw
        }
    }

    /** "Hey Whispr, write a haiku" -> "write a haiku"; null when not addressed to the agent. */
    private fun wakeCommand(text: String, name: String): String? {
        val m = Regex("^\\s*(?:hey|ok|okay)?[\\s,]*${Regex.escape(name)}\\b[\\s,.:!?-]*", RegexOption.IGNORE_CASE)
            .find(text) ?: return null
        return text.substring(m.range.last + 1).trim().ifEmpty { null }
    }

    // ---------------------------------------------------------------- insertion

    private fun liveTarget(): AccessibilityNodeInfo? =
        focusedEditable() ?: target?.takeIf { it.refresh() && it.isEditable }

    private fun fieldText(n: AccessibilityNodeInfo): String =
        if (n.isShowingHintText) "" else n.text?.toString().orEmpty()

    private fun selection(n: AccessibilityNodeInfo, text: String): Pair<Int, Int> {
        var s = n.textSelectionStart
        var e = n.textSelectionEnd
        if (s < 0 || e < 0 || s > text.length || e > text.length) { s = text.length; e = s }
        return if (s <= e) s to e else e to s
    }

    private fun insert(raw: String) {
        val node = liveTarget()
        if (node == null) {
            copy(raw)
            fail("No text field — copied to clipboard")
            return
        }
        val cur = fieldText(node)
        val (s, e) = selection(node, cur)
        // Wispr-style smart spacing when appending after existing text.
        val needsSpace = s > 0 && !cur[s - 1].isWhitespace() && raw.first().let { it.isLetterOrDigit() || it == '(' }
        val text = if (needsSpace) " $raw" else raw
        val updated = cur.substring(0, s) + text + cur.substring(e)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            val caret = s + text.length
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret)
            })
        } else {
            // Fallback for editors that reject SET_TEXT (some web/rich editors): paste.
            copy(text)
            if (!node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) fail("Couldn't type here — copied to clipboard")
        }
    }

    private fun copy(text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Whispr", text))
    }

    private fun fail(msg: String): Boolean {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        return false
    }

    companion object {
        private const val TAG = "wisprdroid"
        /** Whisper's classic outputs on silence. */
        private val HALLUCINATIONS = setOf("thank you", "thanks for watching", "you", "bye", "thank you for watching")
    }
}
