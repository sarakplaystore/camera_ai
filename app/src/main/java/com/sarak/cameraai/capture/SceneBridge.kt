package com.sarak.cameraai.capture

import com.sarak.cameraai.ai.Scene

/**
 * Hands the live [Scene] (and therefore the preview bitmap / landmarks) from the
 * service to whichever Activity is on screen.
 *
 * This is intentionally a static reference rather than a Binder: there is
 * exactly one camera owner, one foreground service, and one visible screen.
 */
object SceneBridge {
    @Volatile
    var scene: Scene? = null

    /** Set by MainActivity while it is resumed. */
    @Volatile
    var ui: ((android.graphics.Bitmap?) -> Unit)? = null

    fun publish(scene: Scene) {
        this.scene = scene
        ui?.invoke(scene.preview)
    }
}
