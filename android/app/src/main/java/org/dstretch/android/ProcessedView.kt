package org.dstretch.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Color
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.os.Handler
import android.os.Looper
import androidx.core.graphics.createBitmap

/** Three bounded bitmap slots; only the UI thread changes/draws the displayed slot. */
class ProcessedView(context: Context) : View(context) {
    private val main = Handler(Looper.getMainLooper())
    private val free = ArrayDeque<Bitmap>()
    private var allocated = 0
    private var current: Bitmap? = null
    private var closed = false
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()
    private var geometry: PreviewGeometry? = null
    internal var onFocusRequested: ((FocusPoint) -> Unit)? = null
    private val controlAreaHeight = 120f * resources.displayMetrics.density
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = Color.WHITE
    }
    private var focusX = 0f
    private var focusY = 0f
    private var showFocus = false
    private var tapX = Float.NaN
    private var tapY = Float.NaN
    private val hideFocus = Runnable { showFocus = false; invalidate() }
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = !closed && geometry != null &&
            onFocusRequested != null && event.y < height - controlAreaHeight
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            tapX = event.x; tapY = event.y
            performClick()
            return true
        }
    })
    init {
        isClickable = true
        contentDescription = "Live DStretch preview. Tap to focus."
    }

    @Synchronized fun acquire(w: Int, h: Int): Bitmap? {
        if (closed) return null
        val candidate = if (free.isNotEmpty()) free.removeFirst() else {
            if (allocated >= 3) return null
            val bitmap = createBitmap(w, h, Bitmap.Config.ARGB_8888)
            allocated++
            return bitmap
        }
        if (candidate.width == w && candidate.height == h) return candidate
        candidate.recycle() // free slots cannot be displayed
        allocated--
        val replacement = createBitmap(w, h, Bitmap.Config.ARGB_8888)
        allocated++
        return replacement
    }
    @Synchronized fun release(bitmap: Bitmap) {
        if (closed) bitmap.recycle() else free.addLast(bitmap)
    }
    internal fun present(bitmap: Bitmap, frameGeometry: PreviewGeometry, onFirstFrame: () -> Unit) {
        main.post {
            synchronized(this) {
                if (closed) { bitmap.recycle(); return@post }
                current?.let { free.addLast(it) }
                current = bitmap
                geometry = frameGeometry
            }
            invalidate()
            onFirstFrame()
        }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = current ?: return
        val crop = geometry?.crop(width, height) ?: return
        destination.set(crop.left, crop.top, crop.left + crop.width, crop.top + crop.height)
        canvas.drawBitmap(bitmap, null, destination, paint)
        if (showFocus) {
            val radius = 24f * resources.displayMetrics.density
            canvas.drawRoundRect(focusX - radius, focusY - radius, focusX + radius, focusY + radius,
                4f * resources.displayMetrics.density, 4f * resources.displayMetrics.density, focusPaint)
        }
    }
    override fun onTouchEvent(event: MotionEvent) = gestures.onTouchEvent(event)
    override fun performClick(): Boolean {
        super.performClick()
        val x = if (tapX.isFinite()) tapX else width / 2f
        val y = if (tapY.isFinite()) tapY else (height - controlAreaHeight) / 2f
        tapX = Float.NaN; tapY = Float.NaN
        if (!closed && y < height - controlAreaHeight) {
            val point = geometry?.focusPoint(width, height, x, y)
            if (point != null && onFocusRequested != null) {
                focusX = x; focusY = y; showFocus = true
                focusPaint.color = Color.WHITE
                main.removeCallbacks(hideFocus)
                main.postDelayed(hideFocus, 1500)
                invalidate()
                onFocusRequested?.invoke(point)
            }
        }
        return true
    }
    internal fun finishFocus(success: Boolean) {
        if (closed || !showFocus) return
        focusPaint.color = if (success) Color.rgb(120, 230, 170) else Color.rgb(255, 195, 100)
        invalidate()
    }
    // Called on UI thread after stopping frame submission. In-flight slots release themselves.
    @Synchronized fun close() {
        closed = true
        onFocusRequested = null
        geometry = null
        main.removeCallbacks(hideFocus)
        current?.recycle(); current = null
        free.forEach { it.recycle() }; free.clear()
    }
}
