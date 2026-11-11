package com.sarak.cameraai.record

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.OutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.sarak.cameraai.util.Format
import java.io.File
import java.util.concurrent.Executors

/**
 * Owns the camera -> encoder pipe for one continuous "take".
 *
 * [VideoCapture] is bound once (cameras are exclusive devices) and reused for
 * every start/stop. A long take is spliced into segments of [MAX_SEGMENT_MS]
 * so a multi-hour run never depends on one fragile MP4: when a segment ends the
 * next one starts immediately under the same take tag.
 *
 * On Android 10+ segments are written straight into the shared gallery
 * (Movies/CameraAI) through MediaStore, so nothing is copied and no space is
 * wasted. On older devices they land in the app's external files directory.
 */
class VideoRecorder(
    private val context: Context,
    private val onEvent: (Event) -> Unit,
) {
    sealed class Event {
        data class Started(val displayName: String) : Event()
        data class SegmentSaved(
            val displayName: String,
            val durationMs: Long,
            val bytes: Long,
            val uri: Uri?,
        ) : Event()

        data class Failed(val reason: String) : Event()
    }

    @Volatile
    var isRecording: Boolean = false
        private set

    @Volatile
    var recordingStartedMs: Long = 0L
        private set

    private val ioExecutor = Executors.newSingleThreadExecutor()

    private var videoCapture: VideoCapture<Recorder>? = null
    private var active: Recording? = null
    private var starting: Boolean = false
    private var activeStartMs: Long = 0L
    private var takeTag: String = ""
    private var segment: Int = 0
    private var wantAudio: Boolean = true
    private var stopRequested: Boolean = false
    private var rotationWanted: Boolean = false

    val currentTakeTag: String get() = takeTag
    val elapsedMs: Long get() = if (isRecording) System.currentTimeMillis() - activeStartMs else 0L

    fun bind(capture: VideoCapture<Recorder>) {
        videoCapture = capture
    }

    fun unbind() {
        videoCapture = null
    }

    fun outputDir(): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "CameraAI").also {
            if (!it.exists()) it.mkdirs()
        }

    /** Begins a new take: fresh tag, segment counter reset. */
    fun beginTake(): String {
        takeTag = Format.stamp()
        segment = 0
        return takeTag
    }

    fun start(withAudio: Boolean): Boolean {
        if (isRecording || starting) return isRecording
        val capture = videoCapture
        if (capture == null) {
            onEvent(Event.Failed("Camera is not ready yet"))
            return false
        }
        wantAudio = withAudio
        stopRequested = false
        if (takeTag.isEmpty()) beginTake()
        segment++
        val name = "CCTV_${takeTag}_${"%03d".format(segment)}.mp4"
        val options = outputOptions(name)

        val prepare = try {
            capture.output.prepareRecording(context, options)
        } catch (t: Throwable) {
            onEvent(Event.Failed("Could not open the encoder: ${t.message}"))
            return false
        }
        val configured = if (withAudio && hasAudioPermission()) {
            try {
                prepare.withAudioEnabled()
            } catch (t: Throwable) {
                Log.w(TAG, "audio unavailable, recording muted", t)
                prepare
            }
        } else {
            prepare
        }
        starting = true
        active = try {
            configured.start(ContextCompat.getMainExecutor(context)) { event ->
                handle(event, name)
            }
        } catch (t: Throwable) {
            starting = false
            onEvent(Event.Failed("Could not start recording: ${t.message}"))
            null
        }
        return active != null
    }

    private fun outputOptions(name: String): OutputOptions {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, ClipStore.RELATIVE_PATH)
            }
            return MediaStoreOutputOptions.Builder(
                context.contentResolver,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            ).setContentValues(values).build()
        }
        return FileOutputOptions.Builder(File(outputDir(), name)).build()
    }

    private fun handle(event: VideoRecordEvent, name: String) {
        when (event) {
            is VideoRecordEvent.Start -> {
                starting = false
                activeStartMs = System.currentTimeMillis()
                recordingStartedMs = activeStartMs
                isRecording = true
                onEvent(Event.Started(name))
            }

            is VideoRecordEvent.Finalize -> {
                isRecording = false
                active = null
                starting = false
                val duration = System.currentTimeMillis() - activeStartMs
                val error = event.error
                val hardFailure = error != VideoRecordEvent.Finalize.ERROR_NONE &&
                        error != VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED &&
                        error != VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE
                if (hardFailure) Log.w(TAG, "finalize error $error for $name")

                val uri = event.outputResults.outputUri
                val reportedMs = runCatching { event.outputResults.durationMs }.getOrDefault(duration)
                val bytes = ClipStore.sizeOf(context, uri, name)
                if (bytes > 0 || (uri != null && uri != Uri.EMPTY)) {
                    onEvent(Event.SegmentSaved(name, reportedMs, bytes, uri))
                } else {
                    onEvent(Event.Failed("Nothing was written (code $error)"))
                }
                // Rotate straight into the next segment for long takes.
                if (!stopRequested && rotationWanted) {
                    rotationWanted = false
                    start(wantAudio)
                }
            }

            else -> Unit
        }
    }

    /** Close the current segment and immediately open a fresh one. */
    fun rotate() {
        val rec = active
        if (rec == null || !isRecording) return
        rotationWanted = true
        try {
            rec.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "rotate stop failed", t)
        }
    }

    fun stop() {
        stopRequested = true
        rotationWanted = false
        val rec = active
        if (rec == null) {
            isRecording = false
            return
        }
        try {
            rec.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "stop failed", t)
        }
    }

    fun release() {
        stopRequested = true
        rotationWanted = false
        stop()
        ioExecutor.shutdown()
    }

    fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "VideoRecorder"

        /** Four minutes keeps any single MP4 small enough to be safe to finalise. */
        const val MAX_SEGMENT_MS = 4 * 60 * 1000L
    }
}
