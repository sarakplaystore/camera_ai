package com.sarak.cameraai.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.sarak.cameraai.ai.KP
import com.sarak.cameraai.ai.Scene
import kotlin.math.max

/**
 * Draws the tracked people over the camera preview.
 *
 * The analyzer hands us normalised landmark coordinates in the *upright image*
 * space. PreviewView in FILL_CENTER cover-crops the sensor image, which for a
 * portrait phone is rotated by [Scene.rotationDegrees] relative to the sensor.
 * We therefore map through the same rectangle the preview covers, then mirror
 * horizontally for the selfie camera.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    var scene: Scene? = null

    var drawBoxes: Boolean = true
    var drawSkeleton: Boolean = true

    /** While recording the lines turn red so the state is unmistakable. */
    var recording: Boolean = false

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
    }
    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#B3000000")
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val box = RectF()

    fun dp(v: Float): Float = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = scene ?: return
        if (s.people.isEmpty()) return
        val srcW = s.sourceWidth
        val srcH = s.sourceHeight
        if (srcW <= 0 || srcH <= 0) return
        if (width <= 0 || height <= 0) return

        val accent = if (recording) RECORD_COLOR else TRACK_COLOR
        boxPaint.color = accent
        bonePaint.color = accent
        headPaint.color = accent
        bonePaint.alpha = if (recording) 235 else 210
        labelPaint.textSize = dp(11f)

        // Rotation-aware: swap which axis the image covers.
        val rotated = s.rotationDegrees == 90 || s.rotationDegrees == 270
        val imgW = if (rotated) srcH else srcW
        val imgH = if (rotated) srcW else srcH

        val scale = max(width.toFloat() / imgW, height.toFloat() / imgH)
        val drawnW = imgW * scale
        val drawnH = imgH * scale
        val offsetX = (width - drawnW) * 0.5f
        val offsetY = (height - drawnH) * 0.5f
        val mirror = s.lensFacing == 1

        fun px(nx: Float): Float {
            val x = if (mirror) 1f - nx else nx
            return offsetX + x.coerceIn(0f, 1f) * drawnW
        }

        fun py(ny: Float): Float = offsetY + ny.coerceIn(0f, 1f) * drawnH

        for (p in s.people) {
            val l = px(p.left)
            val t = py(p.top)
            val r = px(p.right)
            val b = py(p.bottom)
            box.set(minOf(l, r), t, maxOf(l, r), b)
            if (box.width() < dp(8f) || box.height() < dp(8f)) continue

            if (drawBoxes) {
                val corner = minOf(box.width(), box.height()) * 0.24f
                bracket(canvas, box.left, box.top, corner, 1, 1)
                bracket(canvas, box.right, box.top, corner, -1, 1)
                bracket(canvas, box.left, box.bottom, corner, 1, -1)
                bracket(canvas, box.right, box.bottom, corner, -1, -1)
            }

            if (drawSkeleton) {
                for (pair in KP.CONNECTIONS) {
                    val a = pair[0]
                    val c = pair[1]
                    if (p.visibility(a) < 0.45f || p.visibility(c) < 0.45f) continue
                    canvas.drawLine(
                        px(p.raw[a * 4]), py(p.raw[a * 4 + 1]),
                        px(p.raw[c * 4]), py(p.raw[c * 4 + 1]),
                        bonePaint,
                    )
                }
                val radius = dp(2.2f)
                for (k in 0 until KP.COUNT) {
                    if (p.visibility(k) < 0.5f) continue
                    canvas.drawCircle(px(p.raw[k * 4]), py(p.raw[k * 4 + 1]), radius, jointPaint)
                }
            }

            val noseIndex = KP.NOSE * 4
            if (p.visibility(KP.NOSE) > 0.5f) {
                val headR = (box.height() * 0.12f).coerceIn(dp(7f), dp(28f))
                canvas.drawCircle(px(p.raw[noseIndex]), py(p.raw[noseIndex + 1]), headR, headPaint)
            }

            val label = "#${if (p.id > 0) p.id else 1}  ${(p.score * 100).toInt()}%"
            val pad = dp(4f)
            val textW = labelPaint.measureText(label)
            val textH = labelPaint.textSize
            val ly = (box.top - textH - pad * 1.2f).coerceAtLeast(0f)
            canvas.drawRoundRect(
                box.left, ly, box.left + textW + pad * 2, ly + textH + pad,
                dp(4f), dp(4f), labelBg,
            )
            canvas.drawText(label, box.left + pad, ly + textH, labelPaint)
        }
    }

    private fun bracket(canvas: Canvas, x: Float, y: Float, len: Float, sx: Int, sy: Int) {
        canvas.drawLine(x, y, x + len * sx, y, boxPaint)
        canvas.drawLine(x, y, x, y + len * sy, boxPaint)
    }

    private companion object {
        const val TRACK_COLOR = "#FF1DB954"
        val TRACK_COLOR_INT = Color.parseColor(TRACK_COLOR)
        val RECORD_COLOR = Color.parseColor("#FFFF3B30")
    }
}
