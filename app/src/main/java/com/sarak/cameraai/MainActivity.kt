package com.sarak.cameraai

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.sarak.cameraai.capture.CameraService
import com.sarak.cameraai.capture.SceneBridge
import com.sarak.cameraai.capture.Session
import com.sarak.cameraai.databinding.ActivityMainBinding
import com.sarak.cameraai.settings.AppPrefs
import com.sarak.cameraai.settings.Config
import com.sarak.cameraai.util.Format

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private var config: Config = Config()
    private var cameraService: CameraService? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? CameraService.LocalBinder)?.service() ?: return
            cameraService = service
            // The service owns the Preview use case; give it our surface.
            service.attachPreview(binding.previewView.surfaceProvider)
            SceneBridge.publish(service.scene)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cameraService?.attachPreview(null)
            cameraService = null
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 400)
        }
    }

    /** Fast overlay refresh (~20 fps) — cheaper than redrawing on every frame. */
    private val overlayTicker = object : Runnable {
        override fun run() {
            refreshOverlay()
            handler.postDelayed(this, 50)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val camera = result[Manifest.permission.CAMERA] == true ||
                hasPermission(Manifest.permission.CAMERA)
        if (camera) {
            hidePermissionPanel()
            CameraService.start(this)
        } else {
            showPermissionPanel()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        config = AppPrefs.load(this)

        binding.previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        binding.previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        binding.btnSettings.setOnClickListener { openSettings() }
        binding.btnGallery.setOnClickListener { openGallery() }
        binding.btnGrant.setOnClickListener { requestPermissions() }
        binding.btnTorch.setOnClickListener { CameraService.send(this, CameraService.ACTION_TORCH) }
        binding.btnFlip.setOnClickListener {
            if (!Session.recording) CameraService.send(this, CameraService.ACTION_FLIP)
        }
        binding.btnRecord.setOnClickListener {
            if (Session.recording) {
                CameraService.send(this, CameraService.ACTION_TOGGLE_RECORD)
            } else {
                CameraService.send(this, CameraService.ACTION_START)
                handler.postDelayed(
                    { CameraService.send(this, CameraService.ACTION_TOGGLE_RECORD) },
                    600,
                )
            }
        }
        binding.tvHint.setOnClickListener { openSettings() }
        binding.btnWatch.setOnClickListener {
            val enable = !Session.watching
            Session.watching = enable
            cameraService?.setAutoWatch(enable)
            render()
        }

        binding.overlayView.drawBoxes = config.drawBoxes
        binding.overlayView.drawSkeleton = config.drawSkeleton
        SceneBridge.ui = { _ -> onSceneUpdated() }
    }

    override fun onStart() {
        super.onStart()
        config = AppPrefs.load(this)
        if (hasPermission(Manifest.permission.CAMERA)) {
            hidePermissionPanel()
            CameraService.start(this)
            bindService(
                Intent(this, CameraService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE,
            )
        } else {
            showPermissionPanel()
        }
        handler.post(ticker)
        handler.post(overlayTicker)
        render()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(overlayTicker)
        try {
            cameraService?.attachPreview(null)
            unbindService(serviceConnection)
        } catch (_: Throwable) {
            // Not bound (permission denied) — nothing to release.
        }
        cameraService = null
    }

    override fun onDestroy() {
        SceneBridge.ui = null
        super.onDestroy()
    }

    /** Called by the service (main thread) whenever a new analysis frame lands. */
    fun onSceneUpdated() {
        refreshOverlay()
    }

    private fun refreshOverlay() {
        val overlay = binding.overlayView
        overlay.scene = SceneBridge.scene
        overlay.recording = Session.recording
        overlay.invalidate()
    }

    private fun render() {
        binding.tvStatus.text = Session.statusLine.ifEmpty { getString(R.string.status_idle) }
        binding.tvSubStatus.text = Session.subStatusLine
        binding.tvSubStatus.visibility =
            if (Session.subStatusLine.isBlank()) View.GONE else View.VISIBLE
        binding.tvPeopleBadge.text =
            "👤 ${Session.peopleCount} ${if (Session.peopleCount == 1) "person" else "people"}"

        binding.tvRecBadge.visibility = if (Session.recording) View.VISIBLE else View.GONE
        binding.tvElapsed.visibility = if (Session.recording) View.VISIBLE else View.GONE
        binding.tvElapsed.text = Format.clock(Session.elapsedMs)
        binding.progress.visibility = if (Session.recording) View.VISIBLE else View.GONE
        binding.progress.progress = Session.clipProgress

        binding.btnTorch.background = ContextCompat.getDrawable(
            this,
            if (Session.torchOn) R.drawable.bg_circle_active else R.drawable.bg_circle_idle,
        )
        binding.btnWatch.text = if (Session.watching) "Pause watcher" else "Resume watcher"
        binding.btnFlip.alpha = if (Session.recording) 0.4f else 1f
        val front = Session.lensFacing == CameraSelector.LENS_FACING_FRONT
        binding.btnTorch.alpha = if (front) 0.4f else 1f

        if (Session.recording) {
            binding.btnRecord.text = getString(R.string.stop)
            binding.btnRecord.setIconResource(R.drawable.ic_stop)
            binding.btnRecord.setTextColor(ContextCompat.getColor(this, R.color.rec_red))
        } else {
            binding.btnRecord.text = getString(R.string.record)
            binding.btnRecord.setIconResource(R.drawable.ic_dot)
            binding.btnRecord.setTextColor(ContextCompat.getColor(this, R.color.brand))
        }

        // Live accuracy readout: proves whether the model sees people at all,
        // which separates "detector is blind" from "tracker/render is wrong".
        binding.tvBadgeDebug.text = buildString {
            append(if (Session.usingGpu) "GPU" else "CPU")
            append(" · ")
            append("${Session.rawDetections} found")
            append(" · ")
            append("${Session.inferenceMs}ms")
        }
        binding.tvBadgeDebug.visibility = if (Session.cameraReady) View.VISIBLE else View.GONE

        binding.tvHint.text = when {
            Session.error != null -> "⚠ ${Session.error}"
            config.triggerMode == Config.TRIGGER_SECOND_PERSON ->
                "Auto-starts when someone joins · records ≥ ${Format.durationLong(config.minRecordSeconds)}"
            config.triggerMode == Config.TRIGGER_NEW_PERSON ->
                "Auto-starts on any new person · records ≥ ${Format.durationLong(config.minRecordSeconds)}"
            else -> "Manual mode · clips run ≥ ${Format.durationLong(config.minRecordSeconds)}"
        }

        if (!hasPermission(Manifest.permission.CAMERA)) showPermissionPanel()
    }

    private fun showPermissionPanel() {
        binding.permissionPanel.visibility = View.VISIBLE
    }

    private fun hidePermissionPanel() {
        binding.permissionPanel.visibility = View.GONE
    }

    private fun requestPermissions() {
        val list = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.POST_NOTIFICATIONS
        }
        permissionLauncher.launch(list.toTypedArray())
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun openGallery() {
        startActivity(Intent(this, GalleryActivity::class.java))
    }

    /** Re-applies settings after the user returns from the settings screen. */
    fun applyNewConfig() {
        config = AppPrefs.load(this)
        binding.overlayView.drawBoxes = config.drawBoxes
        binding.overlayView.drawSkeleton = config.drawSkeleton
        CameraService.send(this, CameraService.ACTION_RELOAD)
        render()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }
}
