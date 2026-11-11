package com.sarak.cameraai.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * All user-tunable behaviour for the watcher.
 *
 * The two values that matter most for the product requirement are
 * [minRecordSeconds] (default 10 minutes) and [triggerMode] (default: start
 * recording when a second person joins the frame).
 */
data class Config(
    val autoWatch: Boolean = true,
    val triggerMode: Int = TRIGGER_SECOND_PERSON,
    /** A clip triggered by the policy is never shorter than this. */
    val minRecordSeconds: Int = 600,
    /** Keep rolling past the minimum while somebody is still in frame. */
    val extendWhilePresent: Boolean = true,
    /** Once everyone left, keep rolling this long before stopping. */
    val stopGraceSeconds: Int = 30,
    /** Hard cap per clip. 0 = unlimited. */
    val maxRecordSeconds: Int = 3600,
    val withAudio: Boolean = true,
    val maxPeople: Int = 5,
    /** 0.20 (very sensitive) .. 0.85 (strict). */
    val minDetectionConfidence: Float = 0.45f,
    val modelVariant: String = "lite",
    val analysisFps: Int = 10,
    val drawSkeleton: Boolean = true,
    val drawBoxes: Boolean = true,
) {
    companion object {
        const val TRIGGER_NEW_PERSON = 0
        const val TRIGGER_SECOND_PERSON = 1
        const val TRIGGER_MANUAL = 2

        fun triggerLabel(mode: Int): String = when (mode) {
            TRIGGER_NEW_PERSON -> "Any new person"
            TRIGGER_SECOND_PERSON -> "Someone joins"
            else -> "Manual only"
        }
    }
}

object AppPrefs {
    private const val FILE = "camera_ai_prefs"

    private const val K_AUTO = "auto_watch"
    private const val K_TRIGGER = "trigger_mode"
    private const val K_MIN = "min_record_seconds"
    private const val K_EXTEND = "extend_while_present"
    private const val K_GRACE = "stop_grace_seconds"
    private const val K_MAX = "max_record_seconds"
    private const val K_AUDIO = "with_audio"
    private const val K_MAX_PEOPLE = "max_people"
    private const val K_CONF = "min_detection_confidence"
    private const val K_MODEL = "model_variant"
    private const val K_FPS = "analysis_fps"
    private const val K_SKELETON = "draw_skeleton"
    private const val K_BOXES = "draw_boxes"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): Config {
        val p = prefs(context)
        val d = Config()
        return Config(
            autoWatch = p.getBoolean(K_AUTO, d.autoWatch),
            triggerMode = p.getInt(K_TRIGGER, d.triggerMode),
            minRecordSeconds = p.getInt(K_MIN, d.minRecordSeconds).coerceIn(10, 7200),
            extendWhilePresent = p.getBoolean(K_EXTEND, d.extendWhilePresent),
            stopGraceSeconds = p.getInt(K_GRACE, d.stopGraceSeconds).coerceIn(0, 600),
            maxRecordSeconds = p.getInt(K_MAX, d.maxRecordSeconds).coerceIn(0, 14400),
            withAudio = p.getBoolean(K_AUDIO, d.withAudio),
            maxPeople = p.getInt(K_MAX_PEOPLE, d.maxPeople).coerceIn(1, 5),
            minDetectionConfidence = p.getFloat(K_CONF, d.minDetectionConfidence).coerceIn(0.1f, 0.9f),
            modelVariant = p.getString(K_MODEL, d.modelVariant) ?: d.modelVariant,
            analysisFps = p.getInt(K_FPS, d.analysisFps).coerceIn(3, 20),
            drawSkeleton = p.getBoolean(K_SKELETON, d.drawSkeleton),
            drawBoxes = p.getBoolean(K_BOXES, d.drawBoxes),
        )
    }

    fun save(context: Context, config: Config) {
        prefs(context).edit()
            .putBoolean(K_AUTO, config.autoWatch)
            .putInt(K_TRIGGER, config.triggerMode)
            .putInt(K_MIN, config.minRecordSeconds)
            .putBoolean(K_EXTEND, config.extendWhilePresent)
            .putInt(K_GRACE, config.stopGraceSeconds)
            .putInt(K_MAX, config.maxRecordSeconds)
            .putBoolean(K_AUDIO, config.withAudio)
            .putInt(K_MAX_PEOPLE, config.maxPeople)
            .putFloat(K_CONF, config.minDetectionConfidence)
            .putString(K_MODEL, config.modelVariant)
            .putInt(K_FPS, config.analysisFps)
            .putBoolean(K_SKELETON, config.drawSkeleton)
            .putBoolean(K_BOXES, config.drawBoxes)
            .apply()
    }

    fun minRecordSeconds(context: Context): Int = load(context).minRecordSeconds
}
