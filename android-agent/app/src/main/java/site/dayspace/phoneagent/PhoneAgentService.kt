package site.dayspace.phoneagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One on-screen element, with its center in real screen pixels. */
data class UiElement(val id: Int, val realX: Int, val realY: Int, val line: String)

/** A screenshot prepared for the model. */
data class Shot(val base64Jpeg: String, val width: Int, val height: Int)

/**
 * The accessibility service: the agent's eyes and hands.
 * It takes screenshots, reads the UI tree, performs gestures and typing,
 * and shows a small floating panel with the status and a Stop button.
 */
class PhoneAgentService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: PhoneAgentService? = null
            private set

        /** Largest edge we send to the model; keeps images under the API's resize limit. */
        private const val MAX_EDGE = 1280.0
        private const val MAX_PIXELS = 1_150_000.0
    }

    private val main = Handler(Looper.getMainLooper())

    private var overlay: LinearLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var statusView: TextView? = null
    private var stopButton: Button? = null
    private var confirmRow: LinearLayout? = null

    @Volatile private var confirmLatch: CountDownLatch? = null
    @Volatile private var confirmResult = false

    @Volatile var lastScreenshotError: String? = null
        private set

    // ---------------------------------------------------------------- lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        AgentRunner.stop()
        main.post { removeOverlay() }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        removeOverlay()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- threading helper

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var result: T? = null
        var error: Throwable? = null
        main.post {
            try {
                result = block()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        latch.await(5, TimeUnit.SECONDS)
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    // ---------------------------------------------------------------- geometry

    /** Real screen size in pixels (current rotation). */
    fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        val b = wm.currentWindowMetrics.bounds
        return Pair(b.width(), b.height())
    }

    /** Factor from real pixels to model-image pixels. */
    fun modelScale(): Double {
        val (w, h) = screenSize()
        return min(1.0, min(MAX_EDGE / max(w, h), sqrt(MAX_PIXELS / (w.toDouble() * h))))
    }

    fun toReal(v: Int): Float = (v / modelScale()).toFloat()

    private fun toModel(v: Int): Int = (v * modelScale()).roundToInt()

    // ---------------------------------------------------------------- overlay

    fun showOverlay() = onMain {
        if (overlay != null) return@onMain
        val dp = resources.displayMetrics.density
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 16 * dp
                setColor(Color.argb(225, 25, 25, 30))
            }
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 4
            maxWidth = (280 * dp).toInt()
            text = "Claude מתחיל…"
        }
        val stop = Button(this).apply {
            text = "⏹ עצור"
            setOnClickListener { AgentRunner.stop() }
        }
        val confirm = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            addView(Button(this@PhoneAgentService).apply {
                text = "✔ אשר"
                setOnClickListener { answerConfirm(true) }
            })
            addView(Button(this@PhoneAgentService).apply {
                text = "✖ דחה"
                setOnClickListener { answerConfirm(false) }
            })
        }
        panel.addView(status)
        panel.addView(confirm)
        panel.addView(stop)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = (12 * dp).toInt()
            y = (90 * dp).toInt()
        }
        getSystemService(WindowManager::class.java).addView(panel, params)
        overlay = panel
        overlayParams = params
        statusView = status
        stopButton = stop
        confirmRow = confirm
    }

    fun removeOverlay() {
        val v = overlay ?: return
        try {
            getSystemService(WindowManager::class.java).removeView(v)
        } catch (_: Exception) {
        }
        overlay = null
        overlayParams = null
        statusView = null
        stopButton = null
        confirmRow = null
    }

    fun hideOverlayLater(delayMs: Long) {
        main.postDelayed({ if (!AgentRunner.running) removeOverlay() }, delayMs)
    }

    fun setStatus(text: String) {
        main.post { statusView?.text = text }
    }

    /**
     * Makes the panel invisible and lets touches pass through it, so it is
     * neither in screenshots nor in the way of injected gestures.
     */
    private fun setOverlayGhost(ghost: Boolean) = onMain {
        val v = overlay ?: return@onMain
        val p = overlayParams ?: return@onMain
        v.alpha = if (ghost) 0f else 1f
        p.flags = if (ghost) p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        try {
            getSystemService(WindowManager::class.java).updateViewLayout(v, p)
        } catch (_: Exception) {
        }
    }

    private fun <T> ghosted(block: () -> T): T {
        setOverlayGhost(true)
        Thread.sleep(120)
        try {
            return block()
        } finally {
            setOverlayGhost(false)
        }
    }

    /** Shows a question with Approve / Decline buttons and waits for the answer. */
    fun askUser(question: String, timeoutSec: Long = 300): Boolean {
        val latch = CountDownLatch(1)
        confirmLatch = latch
        confirmResult = false
        onMain {
            statusView?.text = "❓ $question"
            confirmRow?.visibility = View.VISIBLE
        }
        latch.await(timeoutSec, TimeUnit.SECONDS)
        onMain { confirmRow?.visibility = View.GONE }
        confirmLatch = null
        return confirmResult
    }

    private fun answerConfirm(ok: Boolean) {
        confirmResult = ok
        confirmLatch?.countDown()
    }

    /** Releases a pending question (used by Stop). */
    fun cancelQuestion() = answerConfirm(false)

    // ---------------------------------------------------------------- screen

    private fun rawScreenshot(): Bitmap? {
        for (attempt in 0 until 3) {
            val latch = CountDownLatch(1)
            var bmp: Bitmap? = null
            var code = -1
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    try {
                        val hb = result.hardwareBuffer
                        val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                        bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        hb.close()
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    code = errorCode
                    latch.countDown()
                }
            })
            latch.await(6, TimeUnit.SECONDS)
            bmp?.let { lastScreenshotError = null; return it }
            when (code) {
                ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> {
                    lastScreenshotError = "the current app blocks screenshots (secure screen)"
                    return null
                }
                ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> Thread.sleep(600)
                else -> {
                    lastScreenshotError = "screenshot failed (code $code)"
                    Thread.sleep(400)
                }
            }
        }
        return null
    }

    /** Screenshot scaled to the model's coordinate space, as base64 JPEG. */
    fun captureForModel(): Shot? {
        val raw = ghosted { rawScreenshot() } ?: return null
        val s = modelScale()
        val w = (raw.width * s).roundToInt()
        val h = (raw.height * s).roundToInt()
        val scaled = if (w == raw.width && h == raw.height) raw else Bitmap.createScaledBitmap(raw, w, h, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 72, out)
        if (scaled !== raw) scaled.recycle()
        raw.recycle()
        return Shot(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), w, h)
    }

    fun currentPackage(): String = try {
        rootInActiveWindow?.packageName?.toString() ?: "unknown"
    } catch (_: Exception) {
        "unknown"
    }

    /** Lists visible, meaningful UI elements (buttons, fields, texts). */
    fun collectElements(limit: Int = 120): List<UiElement> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                w.root?.let { roots.add(it) }
            }
        } catch (_: Exception) {
        }
        if (roots.isEmpty()) rootInActiveWindow?.let { roots.add(it) }

        val result = ArrayList<UiElement>()
        val rect = Rect()
        val (sw, sh) = screenSize()

        fun childText(n: AccessibilityNodeInfo, depth: Int): String? {
            if (depth > 3) return null
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                val t = c.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: c.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                    ?: childText(c, depth + 1)
                if (t != null) return t
            }
            return null
        }

        fun visit(n: AccessibilityNodeInfo) {
            if (result.size >= limit) return
            if (!n.isVisibleToUser) return
            n.getBoundsInScreen(rect)
            val clickable = n.isClickable || n.isLongClickable
            val editable = n.isEditable
            val scrollable = n.isScrollable
            val own = n.text?.toString()?.takeIf { it.isNotBlank() }
                ?: n.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                ?: n.hintText?.toString()?.takeIf { it.isNotBlank() }
            val onScreen = rect.width() > 0 && rect.height() > 0 &&
                rect.right > 0 && rect.bottom > 0 && rect.left < sw && rect.top < sh
            if (onScreen && (clickable || editable || scrollable || own != null)) {
                val label = (own ?: if (clickable) childText(n, 0) else null)
                    ?.replace('\n', ' ')?.take(80) ?: ""
                val kind = when {
                    editable -> "input"
                    n.isCheckable -> if (n.isChecked) "checkbox[on]" else "checkbox[off]"
                    clickable -> "button"
                    scrollable -> "scrollable"
                    else -> "text"
                }
                val cx = rect.centerX().coerceIn(0, sw - 1)
                val cy = rect.centerY().coerceIn(0, sh - 1)
                val id = result.size + 1
                val focused = if (n.isFocused) " focused" else ""
                result.add(
                    UiElement(id, cx, cy, "[$id] $kind \"$label\" (${toModel(cx)},${toModel(cy)})$focused")
                )
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                visit(c)
            }
        }
        for (r in roots) visit(r)
        return result
    }

    // ---------------------------------------------------------------- actions

    private fun gesture(path: Path, durationMs: Long): Boolean = ghosted {
        val latch = CountDownLatch(1)
        var ok = false
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val sent = onMain {
            dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    ok = true
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            }, null)
        }
        if (sent) latch.await(durationMs + 3000, TimeUnit.MILLISECONDS)
        sent && ok
    }

    fun tapReal(x: Float, y: Float): Boolean =
        gesture(Path().apply { moveTo(x, y) }, 60)

    fun longPressReal(x: Float, y: Float): Boolean =
        gesture(Path().apply { moveTo(x, y) }, 900)

    fun swipeReal(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean =
        gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, durationMs.coerceIn(80, 3000))

    fun scroll(direction: String): Boolean {
        val (w, h) = screenSize()
        val cx = w / 2f
        val cy = h / 2f
        val dy = h * 0.3f
        val dx = w * 0.35f
        return when (direction) {
            // "down" = reveal content further down, so the finger moves up.
            "down" -> swipeReal(cx, cy + dy, cx, cy - dy, 450)
            "up" -> swipeReal(cx, cy - dy, cx, cy + dy, 450)
            "right" -> swipeReal(cx + dx, cy, cx - dx, cy, 350)
            "left" -> swipeReal(cx - dx, cy, cx + dx, cy, 350)
            else -> false
        }
    }

    private fun allRoots(): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                w.root?.let { roots.add(it) }
            }
        } catch (_: Exception) {
        }
        rootInActiveWindow?.let { roots.add(0, it) }
        return roots
    }

    private fun findInput(): AccessibilityNodeInfo? {
        val roots = allRoots()
        for (r in roots) {
            val f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (f != null && f.isEditable) return f
        }
        var first: AccessibilityNodeInfo? = null
        fun search(n: AccessibilityNodeInfo) {
            if (first != null) return
            if (n.isEditable && n.isVisibleToUser) {
                first = n; return
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { search(it) }
        }
        for (r in roots) search(r)
        return first
    }

    fun typeText(text: String): Boolean {
        val node = findInput() ?: return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun pressEnter(): Boolean {
        val node = findInput() ?: return false
        return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    fun globalAction(action: Int): Boolean = performGlobalAction(action)

    fun openApp(name: String): String {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = pm.queryIntentActivities(launcher, 0)
        val q = name.trim().lowercase()
        fun label(i: android.content.pm.ResolveInfo) = i.loadLabel(pm).toString()
        val match = apps.firstOrNull { label(it).lowercase() == q || it.activityInfo.packageName.lowercase() == q }
            ?: apps.firstOrNull { label(it).lowercase().contains(q) || it.activityInfo.packageName.lowercase().contains(q) }
        if (match == null) {
            val names = apps.map { label(it) }.distinct().sorted().take(200).joinToString(", ")
            return "No app matching \"$name\". Installed apps: $names"
        }
        val pkg = match.activityInfo.packageName
        val intent = pm.getLaunchIntentForPackage(pkg)
            ?: Intent(launcher).setClassName(pkg, match.activityInfo.name)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            startActivity(intent)
            "Opened ${label(match)} ($pkg)"
        } catch (e: Exception) {
            "Could not open ${label(match)}: ${e.message}"
        }
    }
}
