package com.sarak.cameraai

import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.sarak.cameraai.capture.CameraService
import com.sarak.cameraai.settings.AppPrefs
import com.sarak.cameraai.settings.Config
import com.sarak.cameraai.util.Format

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, SettingsFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

/**
 * Settings are stored as a whole [Config] blob rather than one preference key
 * per widget: the service loads that blob on every change, so there is a single
 * source of truth and no chance of half-saved states.
 */
class SettingsFragment : PreferenceFragmentCompat() {

    private val rootView: android.view.View? get() = view

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings, rootKey)
        val ctx = requireContext()
        val cfg = AppPrefs.load(ctx)

        // ---------------------------------------------------------- trigger mode
        findPreference<ListPreference>("trigger_mode")?.apply {
            value = cfg.triggerMode.toString()
            setOnPreferenceChangeListener { pref, new ->
                val mode = (new as? String)?.toIntOrNull() ?: Config.TRIGGER_SECOND_PERSON
                update(cfg) { it.copy(triggerMode = mode) }
                (pref as ListPreference).summary = triggerSummary(mode)
                true
            }
            summary = triggerSummary(cfg.triggerMode)
        }

        // ------------------------------------------- minimum clip length (10 min)
        val minHours = findPreference<SeekBarPreference>("min_record_hours")
        val minMins = findPreference<SeekBarPreference>("min_record_minutes")
        val minSummary = findPreference<Preference>("min_record_summary")

        minHours?.apply {
            min = 0
            max = 3
            value = cfg.minRecordSeconds / 3600
        }
        minMins?.apply {
            min = 0
            max = 59
            value = (cfg.minRecordSeconds % 3600) / 60
        }

        fun pushMinLength() {
            val seconds = ((minHours?.value ?: 0) * 3600) + ((minMins?.value ?: 10) * 60)
            val safe = seconds.coerceIn(10, 7200)
            update(cfg) { it.copy(minRecordSeconds = safe) }
            minSummary?.summary = "Never stops before ${Format.durationLong(safe)}"
        }
        minHours?.setOnPreferenceChangeListener { _, _ ->
            rootView?.post { pushMinLength() }
            true
        }
        minMins?.setOnPreferenceChangeListener { _, _ ->
            rootView?.post { pushMinLength() }
            true
        }
        minSummary?.summary = "Never stops before ${Format.durationLong(cfg.minRecordSeconds)}"

        // ------------------------------------------------------------------ audio
        findPreference<SwitchPreferenceCompat>("with_audio")?.apply {
            isChecked = cfg.withAudio
            setOnPreferenceChangeListener { _, new ->
                update(cfg) { it.copy(withAudio = new as Boolean) }
                true
            }
        }
        findPreference<SwitchPreferenceCompat>("extend_while_present")?.apply {
            isChecked = cfg.extendWhilePresent
            setOnPreferenceChangeListener { _, new ->
                update(cfg) { it.copy(extendWhilePresent = new as Boolean) }
                true
            }
        }
        findPreference<SwitchPreferenceCompat>("draw_skeleton")?.apply {
            isChecked = cfg.drawSkeleton
            setOnPreferenceChangeListener { _, new ->
                update(cfg) { it.copy(drawSkeleton = new as Boolean) }
                true
            }
        }
        findPreference<SwitchPreferenceCompat>("draw_boxes")?.apply {
            isChecked = cfg.drawBoxes
            setOnPreferenceChangeListener { _, new ->
                update(cfg) { it.copy(drawBoxes = new as Boolean) }
                true
            }
        }
        findPreference<SwitchPreferenceCompat>("auto_watch")?.apply {
            isChecked = cfg.autoWatch
            setOnPreferenceChangeListener { _, new ->
                update(cfg) { it.copy(autoWatch = new as Boolean) }
                true
            }
        }

        // ------------------------------------------------------------ grace window
        findPreference<EditTextPreference>("stop_grace_seconds")?.apply {
            setOnBindEditTextListener { et ->
                et.inputType = InputType.TYPE_CLASS_NUMBER
                et.setSelection(et.text?.length ?: 0)
            }
            summary = "${cfg.stopGraceSeconds}s of empty frame before auto-stop"
            setOnPreferenceChangeListener { pref, new ->
                val v = (new as? String)?.toIntOrNull() ?: cfg.stopGraceSeconds
                val safe = v.coerceIn(0, 600)
                update(cfg) { it.copy(stopGraceSeconds = safe) }
                pref.summary = "${safe}s of empty frame before auto-stop"
                true
            }
        }

        // -------------------------------------------------------------- hard cap
        val maxMinutes = findPreference<SeekBarPreference>("max_record_minutes")
        fun pushMax() {
            val minutes = maxMinutes?.value ?: 60
            update(cfg) { it.copy(maxRecordSeconds = minutes * 60) }
            maxMinutes?.summary =
                if (minutes == 0) "No limit — rolls until you stop it"
                else "Never longer than ${Format.durationLong(minutes * 60)}"
        }
        maxMinutes?.apply {
            min = 0
            max = 240
            value = cfg.maxRecordSeconds / 60
            setOnPreferenceChangeListener { _, _ -> rootView?.post { pushMax() }; true }
        }
        maxMinutes?.summary = if (cfg.maxRecordSeconds == 0) "No limit — rolls until you stop it"
        else "Never longer than ${Format.durationLong(cfg.maxRecordSeconds)}"

        // ----------------------------------------------------------- detection
        val conf = findPreference<SeekBarPreference>("min_detection_confidence")
        val people = findPreference<SeekBarPreference>("max_people")

        fun pushConf() {
            val pct = conf?.value ?: 45
            update(cfg) { it.copy(minDetectionConfidence = (pct / 100f).coerceIn(0.1f, 0.9f)) }
            conf?.summary = "$pct% — lower finds people further away and in poor light"
        }
        fun pushPeople() {
            val n = people?.value ?: 5
            update(cfg) { it.copy(maxPeople = n.coerceIn(1, 5)) }
            people?.summary = "Track up to $n people at once"
        }

        conf?.apply {
            min = 20
            max = 85
            value = (cfg.minDetectionConfidence * 100).toInt()
            setOnPreferenceChangeListener { _, _ -> rootView?.post { pushConf() }; true }
            summary = "${(cfg.minDetectionConfidence * 100).toInt()}% — lower finds people further away and in poor light"
        }
        people?.apply {
            min = 1
            max = 5
            value = cfg.maxPeople
            setOnPreferenceChangeListener { _, _ -> rootView?.post { pushPeople() }; true }
            summary = "Track up to ${cfg.maxPeople} people at once"
        }

        findPreference<ListPreference>("analysis_fps")?.apply {
            value = cfg.analysisFps.toString()
            setOnPreferenceChangeListener { pref, new ->
                val fps = (new as? String)?.toIntOrNull() ?: 10
                update(cfg) { it.copy(analysisFps = fps) }
                (pref as ListPreference).summary = "$fps analysed frames per second"
                true
            }
            summary = "${cfg.analysisFps} analysed frames per second"
        }
        findPreference<ListPreference>("model_variant")?.apply {
            value = cfg.modelVariant
            setOnPreferenceChangeListener { pref, new ->
                val variant = new as? String ?: "lite"
                update(cfg) { it.copy(modelVariant = variant) }
                (pref as ListPreference).summary =
                    if (variant == "full") "Most accurate — best for detection range"
                    else "Fastest — good for phones"
                true
            }
            summary = if (cfg.modelVariant == "full") "Most accurate — best for detection range"
            else "Fastest — good for phones"
        }

        findPreference<Preference>("storage_info")?.summary =
            "Clips are saved to Movies/CameraAI on this phone."
        findPreference<Preference>("about_version")?.summary =
            "Version 1.0.0 · MediaPipe BlazePose · CameraX"
    }

    private fun triggerSummary(mode: Int): String = when (mode) {
        Config.TRIGGER_SECOND_PERSON -> "Start when a second person joins the frame"
        Config.TRIGGER_NEW_PERSON -> "Start whenever a new person appears"
        else -> "Only when you press Record"
    }

    private fun update(base: Config, change: (Config) -> Config) {
        val next = change(AppPrefs.load(requireContext()))
        AppPrefs.save(requireContext(), next)
        CameraService.send(requireContext(), CameraService.ACTION_RELOAD)
        base.copy()
    }
}
