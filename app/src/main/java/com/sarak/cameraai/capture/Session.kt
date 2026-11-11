package com.sarak.cameraai.capture

/**
 * Cross-component state bridge.
 *
 * CameraX use cases and the MediaPipe landmarker are expensive, undocumented
 * to hand around, and cannot be re-created from a second Activity without
 * fighting the camera HAL for exclusive access. So the [CameraService] is the
 * single owner and publishes a small immutable snapshot here; every screen
 * observes this instead of binding to the service.
 */
object Session {
    @Volatile var cameraReady: Boolean = false
    @Volatile var watching: Boolean = false
    @Volatile var recording: Boolean = false
    @Volatile var peopleCount: Int = 0
    @Volatile var inferenceMs: Long = 0L

    /** Raw detections the model returned last frame (before filtering). */
    @Volatile var rawDetections: Int = 0

    /** True when the landmarker is running on the GPU delegate. */
    @Volatile var usingGpu: Boolean = false
    @Volatile var statusLine: String = ""
    @Volatile var subStatusLine: String = ""
    @Volatile var lastTrigger: String = ""
    @Volatile var minRecordSeconds: Int = 600
    @Volatile var elapsedMs: Long = 0L
    @Volatile var requiredMs: Long = 0L
    @Volatile var clipProgress: Int = 0
    @Volatile var inGrace: Boolean = false
    @Volatile var lensFacing: Int = 0
    @Volatile var torchOn: Boolean = false
    @Volatile var lastClipPath: String? = null
    @Volatile var lastClipMs: Long = 0L
    @Volatile var clipCount: Int = 0
    @Volatile var modelState: String = "idle"
    @Volatile var error: String? = null

    fun reset() {
        watching = false
        recording = false
        peopleCount = 0
        elapsedMs = 0L
        clipProgress = 0
        inGrace = false
        statusLine = ""
        subStatusLine = ""
        error = null
    }
}
