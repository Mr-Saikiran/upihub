package dev.upihub

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat

/**
 * Dims the camera preview outside the scan window, draws its corner brackets
 * and a sweeping line. The window is centred and sized by `@dimen/scan_frame`,
 * the same rect BarcodeView decodes from.
 */
class ScannerOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val frameSize = resources.getDimension(R.dimen.scan_frame)
    private val radius = 28f * resources.displayMetrics.density
    private val bracket = 36f * resources.displayMetrics.density
    private val accent = ContextCompat.getColor(context, R.color.icon_bg_start)

    private val scrim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.scrim_camera) }
    private val corners = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frame = RectF()
    private val hole = Path()
    private val bracketPath = Path()
    private var sweep = 0f
    private var detected = false

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2200
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { sweep = it.animatedValue as Float; invalidate() }
    }

    /** Shows (or clears) the "code found" state: green brackets, line hidden. */
    fun setDetected(value: Boolean) {
        detected = value
        corners.color = if (value) ContextCompat.getColor(context, R.color.brand_secondary) else Color.WHITE
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val size = minOf(frameSize, w * 0.8f, h * 0.8f)
        frame.set((w - size) / 2f, (h - size) / 2f, (w + size) / 2f, (h + size) / 2f)
        hole.reset()
        hole.fillType = Path.FillType.EVEN_ODD
        hole.addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW)
        hole.addRoundRect(frame, radius, radius, Path.Direction.CW)
        line.shader = LinearGradient(frame.left, 0f, frame.right, 0f,
            intArrayOf(Color.TRANSPARENT, accent, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        buildBrackets()
    }

    private fun buildBrackets() {
        val r = radius
        val b = bracket
        val f = frame
        bracketPath.reset()
        // Top-left, top-right, bottom-right, bottom-left: each an "L" with a rounded corner.
        bracketPath.moveTo(f.left, f.top + b); bracketPath.lineTo(f.left, f.top + r)
        bracketPath.quadTo(f.left, f.top, f.left + r, f.top); bracketPath.lineTo(f.left + b, f.top)
        bracketPath.moveTo(f.right - b, f.top); bracketPath.lineTo(f.right - r, f.top)
        bracketPath.quadTo(f.right, f.top, f.right, f.top + r); bracketPath.lineTo(f.right, f.top + b)
        bracketPath.moveTo(f.right, f.bottom - b); bracketPath.lineTo(f.right, f.bottom - r)
        bracketPath.quadTo(f.right, f.bottom, f.right - r, f.bottom); bracketPath.lineTo(f.right - b, f.bottom)
        bracketPath.moveTo(f.left + b, f.bottom); bracketPath.lineTo(f.left + r, f.bottom)
        bracketPath.quadTo(f.left, f.bottom, f.left, f.bottom - r); bracketPath.lineTo(f.left, f.bottom - b)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(hole, scrim)
        canvas.drawPath(bracketPath, corners)
        if (!detected) {
            val inset = radius / 2
            val y = frame.top + inset + (frame.height() - 2 * inset) * sweep
            canvas.drawRect(frame.left + inset, y - 1.5f * resources.displayMetrics.density,
                frame.right - inset, y + 1.5f * resources.displayMetrics.density, line)
        }
    }
}
