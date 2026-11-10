package com.sarak.cameraai.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.sarak.cameraai.ai.KP
import com.sarak.cameraai.ai.Person
import com.sarak.cameraai.ai.Scene
import kotlin.math.max

/**
 * Draws the tracked people over the camera preview.
 *
 * Android's PreviewView in FILL_CENTER scales/crops the image, so we map the
 * normalised landmark coordinates through the same rect the preview occupies
 * (accounting for rotation and mirroring) instead of naively stretching.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    var scene: Scene? = null

    var drawBoxes: Boolean = true
    var drawSkeleton: Boolean = true

    /** While recording the boxes turn warm so the state is obvious in the viewfinder. */
    var recording: Boolean = false

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        color = Color.parseColor("#FF1DB954")
    }
    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#CC1DB954")
    }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.parseColor("#FF1DB954")
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#B3000000")
    }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(11f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val box = RectF()
    private val matrix = Matrix()

    fun dp(v: Float): Float = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = scene ?: return
        val people = s.people
        if (people.isEmpty() && s.preview == null) return

        val activeColor = if (recording) Color.parseColor("#FFFF3B30") else Color.parseColor("#FF1DB954")
        boxPaint.color = activeColor
        bonePaint.color = activeColor
        headPaint.color = activeColor
        bonePaint.alpha = if (recording) 230 else 210

        val rotated = s.rotationDegrees == 90 || s.rotationDegrees == 270
        val srcW = if (rotated) s.sourceHeight else s.sourceWidth
        val srcH = if (rotated) s.sourceWidth else s.sourceHeight
        if (srcW <= 0 || srcH <= 0) return

        // FILL_CENTER: scale to cover, then centre-crop.
        val scale = max(width.toFloat() / srcW, height.toFloat() / srcH)
        val dx = (width - srcW * scale) * 0.5f
        val dy = (height - srcH * scale) * 0.5f
        val mirror = s.lensFacing == 1

        // The analyzer image is already rotated upright, so normalised coords
        // map straight onto the displayed (rotated) preview rect.
        val imgLeft = dx
        val imgTop = dy
        val imgW = srcW * scale
        val imgH = srcH * scale

        fun px(nx: Float): Float {
            val x = if (mirror) 1f - nx else nx
            return imgLeft + x.coerceIn(0f, 1f) * imgW
        }

        fun py(ny: Float): Float = imgTop + ny.coerceIn(0f, 1f) * imgH

        val minSpan = dp(6f)
        for (p in people) {
            p.id
            val l = px(p.left)
            val t = py(p.top)
            val r = px(p.right)
            val b = py(p.bottom)
            box.set(minOf(l, r), t, maxOf(l, r), b)
            if (box.width() < minSpan || box.height() < minSpan) continue

            if (drawBoxes) {
                // Corner-bracket look instead of a plain rectangle.
                val corner = minOf(box.width(), box.height()) * 0.22f
                drawBracket(canvas, box.left, box.top, corner, 1, 1)
                drawBracket(canvas, box.right, box.top, corner, -1, 1)
                drawBracket(canvas, box.left, box.bottom, corner, 1, -1)
                drawBracket(canvas, box.right, box.bottom, corner, -1, -1)
            }

            if (drawSkeleton) {
                for (pair in KP.CONNECTIONS) {
                    val a = pair[0]
                    val c = pair[1]
                    if (p.visibility(a) < 0.45f || p.visibility(c) < 0.45f) continue
                    val ax = px(p.raw[a * 4])
                    val ay = py(p.raw[a * 4 + 1])
                    val cx = px(p.raw[c * 4])
                    val cy = py(p.raw[c * 4 + 1])
                    canvas.drawLine(ax, ay, cx, cy, bonePaint)
                }
                val radius = dp(2.2f)
                for (k in 0 until KP.COUNT) {
                    if (p.visibility(k) < 0.5f) continue
                    canvas.drawCircle(px(p.raw[k * 4]), py(p.raw[k * 4 + 1]), radius, jointPaint)
                }
            }

            // Head ring
            val nose = p.point(KP.NOSE)
            if (p.visibility(KP.NOSE) > 0.5f) {
                val headR = (box.height() * 0.11f).coerceIn(dp(6f), dp(30f))
                canvas.drawCircle(px(nose[0]), py(nose[1]), headR, headPaint)
            }

            // Label: "P1" + confidence
            val label = buildString {
                append('#').append(if (p.id > 0) p.id else 1)
                append("  ")
                append((p.score * 100).toInt()).append('%')
            }
            labelText.textSize = dp(11f)
            val pad = dp(4f)
            val textW = labelText.measureText(label)
            val textH = labelText.textSize
            val ly = (box.top - textH - pad * 1.6f).coerceAtLeast(0f)
            canvas.drawRoundRect(
                box.left, ly,
                box.left + textW + pad * 2, ly + textH + pad,
                dp(4f), dp(4f), labelBg,
            )
            canvas.drawText(label, box.left + pad, ly + textH, labelText)
        }
    }

    private fun drawBracket(canvas: Canvas, x: Float, y: Float, len: Float, sx: Int, sy: Int) {
        canvas.drawLine(x, y, x + len * sx, y, boxPaint)
        canvas.drawLine(x, y, x, y + len * sy, boxPaint)
    }
}
