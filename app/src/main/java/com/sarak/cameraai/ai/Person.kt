package com.sarak.cameraai.ai

import kotlin.math.abs

/**
 * One detected human in normalised frame coordinates (0..1 on both axes).
 * [x] is the horizontal centre of the shoulders/hips, [y] the top of the head.
 */
class Person(
    var score: Float,
    /** 33 * 4 floats: x, y, z, visibility — normalised. */
    var raw: FloatArray,
    /** Tight box in normalised coordinates. */
    var left: Float,
    var top: Float,
    var right: Float,
    var bottom: Float,
) {
    /** Smoothed centre, used by the tracker to match across frames. */
    var cx: Float = (left + right) * 0.5f
    var cy: Float = (top + bottom) * 0.5f

    /** Filled in by [PersonTracker]. */
    var id: Int = -1

    /** Frames this id has been continuously visible. */
    var seenFrames: Int = 0

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = width * height

    fun copyFrom(other: Person) {
        score = other.score
        raw = other.raw
        left = other.left
        top = other.top
        right = other.right
        bottom = other.bottom
        cx = other.cx
        cy = other.cy
        id = other.id
        seenFrames = other.seenFrames
    }

    fun point(index: Int): FloatArray {
        val b = index * 4
        return floatArrayOf(raw[b], raw[b + 1], raw[b + 3])
    }

    fun visibility(index: Int): Float = raw[index * 4 + 3]

    /**
     * Distance between two detections, normalised so a body moving a little
     * between frames still matches while two people crossing stay separate.
     */
    fun distanceTo(other: Person): Float {
        val dx = cx - other.cx
        val dy = cy - other.cy
        val scale = (((height + other.height) * 0.5f)).coerceAtLeast(0.08f)
        val centre = kotlin.math.sqrt(dx * dx + dy * dy) / scale
        val size = abs(height - other.height) / scale
        return centre + size * 0.5f
    }

    override fun toString(): String =
        "Person(id=$id, score=%.2f, cx=%.2f, h=%.2f)".format(score, cx, height)
}
