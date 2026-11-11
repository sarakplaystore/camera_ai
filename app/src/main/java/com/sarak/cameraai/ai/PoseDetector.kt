package com.sarak.cameraai.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.sarak.cameraai.util.ModelManager
import java.io.File

/**
 * Thin wrapper over MediaPipe's PoseLandmarker (BlazePose).
 *
 * Runs in [RunningMode.VIDEO] on the analysis thread. The analyzer feeds frames
 * in order, so results are synchronous and no timestamp bookkeeping leaks out.
 */
class PoseDetector(
    private val context: Context,
    private val maxPeople: Int = 5,
    private val minConfidence: Float = 0.45f,
    private val variant: String = ModelManager.LITE,
) {
    private var landmarker: PoseLandmarker? = null
    private var lastTimestampMs = 0L

    val isReady: Boolean get() = landmarker != null

    /** True when the landmarker was opened on the GPU delegate. */
    @Volatile
    var usingGpu: Boolean = false
        private set

    /**
     * Opens the landmarker. Tries the GPU delegate first (several times faster,
     * which lets the app analyse more frames and react sooner) and falls back to
     * CPU on devices/drivers where it is unavailable.
     */
    fun setup() {
        close()
        landmarker = try {
            PoseLandmarker.createFromOptions(context, options(Delegate.GPU)).also {
                usingGpu = true
            }
        } catch (t: Throwable) {
            Log.i(TAG, "GPU delegate unavailable, using CPU", t)
            PoseLandmarker.createFromOptions(context, options(Delegate.CPU)).also {
                usingGpu = false
            }
        }
    }

    private fun options(delegate: Delegate): PoseLandmarker.PoseLandmarkerOptions =
        PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions(delegate))
            .setRunningMode(RunningMode.VIDEO)
            .setNumPoses(maxPeople.coerceIn(1, 5))
            // Detection/presence control *whether a person is found at all*.
            // Keeping these lower than the tracking confidence is what lets us
            // pick up people who are small, partly occluded or in poor light.
            .setMinPoseDetectionConfidence((minConfidence * 0.9f).coerceIn(0.1f, 0.9f))
            .setMinPosePresenceConfidence((minConfidence * 0.9f).coerceIn(0.1f, 0.9f))
            .setMinTrackingConfidence(0.35f)
            .setOutputSegmentationMasks(false)
            .build()

    private fun baseOptions(delegate: Delegate): BaseOptions {
        val builder = BaseOptions.builder().setDelegate(delegate)
        if (ModelManager.isAssetBundled(context, variant)) {
            builder.setModelAssetPath(ModelManager.fileNameFor(variant))
        } else {
            val file: File = ModelManager.cachedFile(context, variant)
            builder.setModelAssetPath(file.absolutePath)
        }
        return builder.build()
    }

    /**
     * @param timestampMs monotonic timestamps, strictly increasing — MediaPipe
     *   drops frames whose timestamp does not advance.
     */
    fun detect(bitmap: Bitmap, timestampMs: Long): List<Person> {
        val detector = landmarker ?: return emptyList()
        val ts = if (timestampMs > lastTimestampMs) timestampMs else lastTimestampMs + 1
        lastTimestampMs = ts
        val image = BitmapImageBuilder(bitmap).build()
        val result = try {
            detector.detectForVideo(image, ts)
        } catch (t: Throwable) {
            Log.w(TAG, "detect failed", t)
            return emptyList()
        }
        if (result.landmarks().isEmpty()) return emptyList()

        val out = ArrayList<Person>(result.landmarks().size)
        val scores = result.landmarks()
        for (i in scores.indices) {
            val points = scores[i]
            if (points.size < KP.COUNT) continue
            val raw = FloatArray(KP.COUNT * 4)
            var minX = 1f
            var minY = 1f
            var maxX = 0f
            var maxY = 0f
            var visible = 0
            var confidenceSum = 0f
            for (k in 0 until KP.COUNT) {
                val lm = points[k]
                raw[k * 4] = lm.x()
                raw[k * 4 + 1] = lm.y()
                raw[k * 4 + 2] = lm.z()
                raw[k * 4 + 3] = lm.visibility().orElse(1f)
                val v = raw[k * 4 + 3]
                if (v >= 0.5f) {
                    visible++
                    confidenceSum += v
                    if (lm.x() < minX) minX = lm.x()
                    if (lm.y() < minY) minY = lm.y()
                    if (lm.x() > maxX) maxX = lm.x()
                    if (lm.y() > maxY) maxY = lm.y()
                }
            }
            // A person at the far end of a room or half behind a door will not
            // have many confident joints. Requiring only the head OR a shoulder
            // keeps them tracked instead of dropping them.
            if (visible < MIN_VISIBLE_POINTS) continue
            val hasHead = raw[KP.NOSE * 4 + 3] >= 0.35f ||
                    raw[KP.LEFT_EYE * 4 + 3] >= 0.35f ||
                    raw[KP.RIGHT_EYE * 4 + 3] >= 0.35f
            val hasShoulder = raw[KP.LEFT_SHOULDER * 4 + 3] >= 0.35f ||
                    raw[KP.RIGHT_SHOULDER * 4 + 3] >= 0.35f
            if (!hasHead && !hasShoulder) continue
            val pad = 0.02f
            val person = Person(
                score = (confidenceSum / visible).coerceIn(0f, 1f),
                // ^ visibility average; the bounding box below is what the UI shows
                raw = raw,
                left = (minX - pad).coerceIn(0f, 1f),
                top = (minY - pad).coerceIn(0f, 1f),
                right = (maxX + pad).coerceIn(0f, 1f),
                bottom = (maxY + pad).coerceIn(0f, 1f),
            )
            person.cx = listOf(
                raw[KP.LEFT_SHOULDER * 4], raw[KP.RIGHT_SHOULDER * 4],
                raw[KP.LEFT_HIP * 4], raw[KP.RIGHT_HIP * 4],
            ).let { xs -> xs.filter { it in 0f..1f }.ifEmpty { listOf((minX + maxX) / 2) }.average().toFloat() }
            person.cy = listOf(
                raw[KP.LEFT_SHOULDER * 4 + 1], raw[KP.RIGHT_SHOULDER * 4 + 1],
                raw[KP.LEFT_HIP * 4 + 1], raw[KP.RIGHT_HIP * 4 + 1],
            ).let { ys -> ys.filter { it in 0f..1f }.ifEmpty { listOf((minY + maxY) / 2) }.average().toFloat() }
            out += person
        }
        return out
    }

    fun close() {
        try {
            landmarker?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "close failed", t)
        }
        landmarker = null
        lastTimestampMs = 0L
    }

    companion object {
        private const val TAG = "PoseDetector"
        private const val MIN_VISIBLE_POINTS = 4
    }
}
