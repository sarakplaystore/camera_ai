package com.sarak.cameraai.util

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * Turns a CameraX [ImageProxy] into an upright [Bitmap] for the pose model.
 *
 * This is the single most important step for detection accuracy. CameraX hands
 * back sensor-orientation pixels: on a portrait phone the buffer is rotated 90°
 * relative to what the user sees. Feeding that buffer to BlazePose means the
 * model is asked to find a person lying on their side, which it mostly cannot —
 * the symptom is people only being detected when they happen to be horizontal
 * in sensor space, or not at all.
 *
 * So we always rotate by `imageInfo.rotationDegrees` before inference, and we
 * mirror the selfie camera so the coordinates match the mirrored preview.
 */
object FrameConverter {

    /** True when CameraX can hand us RGBA_8888, i.e. the fast path is available. */
    fun supportsDirectRgba(width: Int, height: Int): Boolean =
        ImageAnalysis.isImageProxyFormatSupported(
            ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888,
            ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888,
            width,
            height,
        ) != null

    /**
     * @return an upright bitmap, or null when the frame cannot be read.
     * The caller owns the returned bitmap and must recycle it.
     */
    fun toUprightBitmap(proxy: ImageProxy, mirror: Boolean): Bitmap? {
        val raw: Bitmap = try {
            proxy.toBitmap()
        } catch (t: Throwable) {
            return null
        }

        val rotation = proxy.imageInfo.rotationDegrees
        val needsRotate = rotation % 360 != 0
        if (!needsRotate && !mirror) return raw

        return try {
            val matrix = Matrix()
            if (needsRotate) matrix.postRotate(rotation.toFloat())
            if (mirror) matrix.postScale(-1f, 1f)
            val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
            if (rotated !== raw) raw.recycle()
            rotated
        } catch (t: Throwable) {
            raw
        }
    }
}
