package com.sarak.cameraai.record

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * One recorded segment as the gallery screen sees it.
 */
data class Clip(
    val name: String,
    val uri: Uri,
    val sizeBytes: Long,
    val dateMs: Long,
    val durationMs: Long,
    val file: File? = null,
) {
    val takeTag: String
        get() = name.removePrefix("CCTV_").substringBeforeLast("_", fallback = name.removeSuffix(".mp4"))
}

/**
 * Lists clips whether they were written through MediaStore (Android 10+) or to
 * the app's external files directory (older devices).
 */
object ClipStore {
    const val RELATIVE_PATH = "Movies/CameraAI"
    private const val TAG = "ClipStore"

    fun privateDir(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "CameraAI")

    fun list(context: Context): List<Clip> {
        val clips = ArrayList<Clip>(16)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            clips += mediaStoreClips(context)
        }
        // Older devices (and any segment written to app storage) come from disk.
        clips += fileClips(context)
        return clips
            .distinctBy { it.uri }
            .sortedByDescending { it.dateMs }
    }

    private fun mediaStoreClips(context: Context): List<Clip> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION,
        )
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("%${RELATIVE_PATH.trimEnd('/')}%")
        val out = ArrayList<Clip>(16)
        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                "${MediaStore.Video.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: continue
                    if (!name.endsWith(".mp4", ignoreCase = true)) continue
                    out += Clip(
                        name = name,
                        uri = ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id,
                        ),
                        sizeBytes = cursor.getLong(sizeCol),
                        dateMs = cursor.getLong(dateCol) * 1000L,
                        durationMs = cursor.getLong(durCol),
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "MediaStore query failed", t)
        }
        return out
    }

    private fun fileClips(context: Context): List<Clip> {
        val dir = privateDir(context)
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.isFile && f.extension.equals("mp4", true) }
            ?.map { file ->
                Clip(
                    name = file.name,
                    uri = FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", file,
                    ),
                    sizeBytes = file.length(),
                    dateMs = file.lastModified(),
                    durationMs = 0L,
                    file = file,
                )
            }
            ?: emptyList()
    }

    fun sizeOf(context: Context, uri: Uri?, name: String): Long {
        if (uri != null && uri != Uri.EMPTY) {
            try {
                context.contentResolver.query(
                    uri, arrayOf(MediaStore.Video.Media.SIZE), null, null, null,
                )?.use { c -> if (c.moveToFirst()) return c.getLong(0) }
            } catch (_: Throwable) {
            }
        }
        val file = File(privateDir(context), name)
        return if (file.exists()) file.length() else 0L
    }

    fun delete(context: Context, clip: Clip): Boolean = try {
        if (clip.file != null) {
            clip.file.delete()
        } else {
            context.contentResolver.delete(clip.uri, null, null) > 0
        }
    } catch (t: Throwable) {
        Log.w(TAG, "delete failed", t)
        false
    }
}
