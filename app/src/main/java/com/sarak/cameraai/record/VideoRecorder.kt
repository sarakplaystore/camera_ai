package com.sarak.cameraai.record

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.video.FileOutputOptions
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
 * every start/stop. Long takes are spliced into segments of
 * [MAX_SEGMENT_MS] so a multi-hour run never depends on one fragile MP4: when a
 * segment ends the next one starts immediately under the same take tag.
 */
class VideoRecorder(
    private val context: Context,
    private val onEvent: (Event) -> Unit,
) {
    sealed class Event {
        data class Started(val file: File) : Event()
        data class SegmentSaved(val file: File, val durationMs: Long, val bytes: Long) : Event()
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
    /** True when the user pressed stop, so the segment end must not restart. */
    private var stopRequested: Boolean = false

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

    /** Begins a new take: new tag, segment counter reset. */
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
        segment++
        val file = File(
            outputDir(),
            "CCTV_${takeTag.ifEmpty { beginTake() }}_${"%03d".format(segment)}.mp4",
        )
        val prepare = capture.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
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
                handle(event, file)
            }
        } catch (t: Throwable) {
            starting = false
            onEvent(Event.Failed("Could not start recording: ${t.message}"))
            null
        }
        return active != null
    }

    private fun handle(event: VideoRecordEvent, file: File) {
        when (event) {
            is VideoRecordEvent.Start -> {
                starting = false
                activeStartMs = System.currentTimeMillis()
                recordingStartedMs = activeStartMs
                isRecording = true
                onEvent(Event.Started(file))
            }

            is VideoRecordEvent.Finalize -> {
                val duration = System.currentTimeMillis() - activeStartMs
                isRecording = false
                active = null
                starting = false
                val error = event.error
                val ok = error == VideoRecordEvent.Finalize.ERROR_NONE ||
                        error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED ||
                        error == VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE
                if (!ok) Log.w(TAG, "finalize error $error for ${file.name}")
                val bytes = if (file.exists()) file.length() else 0L
                if (bytes > 0) {
                    copyToGallery(file)
                    onEvent(Event.SegmentSaved(file, duration, bytes))
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

    private var rotationWanted = false

    /** Ask for the current segment to close and a fresh one to open. */
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

    /**
     * Mirrors the finished segment into the shared gallery (Movies/CameraAI)
     * on API 29+, and into the public Movies folder on older devices.
     */
    private fun copyToGallery(file: File) {
        ioExecutor.execute {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraAI")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                    val uri = context.contentResolver.insert(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values,
                    ) ?: return@execute
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { input -> input.copyTo(out, 256 * 1024) }
                    }
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                } else {
                    val dir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                        "CameraAI",
                    )
                    if (!dir.exists()) dir.mkdirs()
                    val dest = File(dir, file.name)
                    file.copyTo(dest, overwrite = true)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "gallery copy failed for ${file.name}", t)
            }
        }
    }

    companion object {
        private const val TAG = "VideoRecorder"
        const val MAX_SEGMENT_MS = 4 * 60 * 1000L
    }
}
