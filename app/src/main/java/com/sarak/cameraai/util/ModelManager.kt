package com.sarak.cameraai.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Makes sure the MediaPipe pose model is on the device.
 *
 * Preferred path: the model is bundled in `assets/` by the CI build.
 * Fallback: download it once and cache it in `filesDir`.
 */
object ModelManager {

    const val LITE = "pose_landmarker_lite.task"
    const val FULL = "pose_landmarker_full.task"

    private const val TAG = "ModelManager"
    private const val BASE = "https://storage.googleapis.com/mediapipe-models/pose_landmarker/"

    fun fileNameFor(variant: String): String = if (variant == "full") FULL else LITE

    fun urlFor(variant: String): String = if (variant == "full") {
        BASE + "pose_landmarker_full/float16/latest/pose_landmarker_full.task"
    } else {
        BASE + "pose_landmarker_lite/float16/latest/pose_landmarker_lite.task"
    }

    fun isAssetBundled(context: Context, variant: String): Boolean = try {
        context.assets.open(fileNameFor(variant)).use { it.read() > 0 }
    } catch (t: Throwable) {
        false
    }

    fun cachedFile(context: Context, variant: String): File =
        File(File(context.filesDir, "models"), fileNameFor(variant))

    fun isReady(context: Context, variant: String): Boolean =
        isAssetBundled(context, variant) || cachedFile(context, variant).let { it.exists() && it.length() > 1024 }

    /**
     * Blocks until the model is available locally. Returns the cached file, or
     * null when the model is bundled as an asset (nothing to return).
     */
    fun ensure(context: Context, variant: String, onProgress: (Int) -> Unit = {}): File? {
        if (isAssetBundled(context, variant)) return null
        val target = cachedFile(context, variant)
        if (target.exists() && target.length() > 1024) return target

        onProgress(0)
        val dir = target.parentFile ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        val tmp = File(dir, target.name + ".part")

        var connection: HttpURLConnection? = null
        try {
            var url = URL(urlFor(variant))
            var redirects = 0
            while (redirects < 5) {
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                }
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: break
                    url = URL(url, location)
                    connection.disconnect()
                    redirects++
                    continue
                }
                if (code != 200) throw IllegalStateException("HTTP $code")
                val total = connection.contentLength.toLong()
                connection.inputStream.use { input ->
                    FileOutputStream(tmp).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var written = 0L
                        var lastPct = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            written += read
                            if (total > 0) {
                                val pct = (written * 100 / total).toInt()
                                if (pct != lastPct && pct % 5 == 0) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                        output.flush()
                    }
                }
                break
            }
            if (!tmp.exists() || tmp.length() < 1024) throw IllegalStateException("empty download")
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) throw IllegalStateException("rename failed")
            onProgress(100)
            Log.i(TAG, "model ready: ${target.absolutePath} (${target.length()} bytes)")
            return target
        } catch (t: Throwable) {
            Log.w(TAG, "model download failed", t)
            if (tmp.exists()) tmp.delete()
            throw t
        } finally {
            connection?.disconnect()
        }
    }

    fun ensureAsync(context: Context, variant: String, onDone: (Boolean, String?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        Thread {
            try {
                ensure(context, variant)
                main.post { onDone(true, null) }
            } catch (t: Throwable) {
                main.post { onDone(false, t.message ?: "download failed") }
            }
        }.start()
    }

    @Suppress("unused")
    fun moviesDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "CameraAI")

    @Suppress("unused")
    fun uriOf(context: Context, file: File): Uri = Uri.fromFile(file)
}
