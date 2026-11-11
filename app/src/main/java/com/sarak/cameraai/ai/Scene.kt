package com.sarak.cameraai.ai

import android.graphics.Bitmap

/** Immutable-ish snapshot handed from the analysis thread to the UI. */
class Scene {
    @Volatile
    var people: List<Person> = emptyList()
        private set

    /** Source frame size the normalised coordinates refer to. */
    @Volatile
    var sourceWidth: Int = 0

    @Volatile
    var sourceHeight: Int = 0

    /** 0 = back camera, 1 = front. */
    @Volatile
    var lensFacing: Int = 0

    @Volatile
    var rotationDegrees: Int = 0

    @Volatile
    var frameStampMs: Long = 0L

    @Volatile
    var inferenceMs: Long = 0L

    @Volatile
    var preview: Bitmap? = null

    fun publish(
        list: List<Person>,
        width: Int,
        height: Int,
        facing: Int,
        rotation: Int,
        stampMs: Long,
        inference: Long,
    ) {
        people = list
        sourceWidth = width
        sourceHeight = height
        lensFacing = facing
        rotationDegrees = rotation
        frameStampMs = stampMs
        inferenceMs = inference
    }

    fun clear() {
        people = emptyList()
    }

    val count: Int get() = people.size
}
