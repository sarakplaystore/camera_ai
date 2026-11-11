package com.sarak.cameraai.capture

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.sarak.cameraai.MainActivity
import com.sarak.cameraai.R
import com.sarak.cameraai.ai.PersonTracker
import com.sarak.cameraai.ai.PoseDetector
import com.sarak.cameraai.ai.Scene
import com.sarak.cameraai.record.RecordPolicy
import com.sarak.cameraai.record.VideoRecorder
import com.sarak.cameraai.settings.AppPrefs
import com.sarak.cameraai.settings.Config
import com.sarak.cameraai.util.Format
import com.sarak.cameraai.util.ModelManager
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraService : LifecycleService() {

    private lateinit var config: Config
    private lateinit var policy: RecordPolicy
    private lateinit var recorder: VideoRecorder

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var analysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var torch: Boolean = false

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val detectorLock = Any()
    private var detector: PoseDetector? = null
    private var detectorConfig: Config? = null
    private var preparingModel = AtomicBoolean(false)

    private val tracker = PersonTracker()
    val scene = Scene()

    /** Latest [ImageProxy] buffer size, used by the UI to size the preview. */
    @Volatile private var frameRotation: Int = 0

    private var analyzing = false
    private var lastFrameAt = 0L

    override fun onCreate() {
        super.onCreate()
        config = AppPrefs.load(this)
        policy = RecordPolicy(config)
        recorder = VideoRecorder(this) { event -> onRecorderEvent(event) }
        createChannel()
        startInForeground(buildNotification())
        Session.minRecordSeconds = config.minRecordSeconds
        observeConfigReload()
    }

    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val updated = AppPrefs.load(this)
        val minChanged = updated.minRecordSeconds != config.minRecordSeconds
        val fpsChanged = updated.analysisFps != config.analysisFps
        val peopleChanged = updated.maxPeople != config.maxPeople
        val confChanged = updated.minDetectionConfidence != config.minDetectionConfidence
        val variantChanged = updated.modelVariant != config.modelVariant
        config = updated
        policy.updateConfig(updated)
        Session.minRecordSeconds = updated.minRecordSeconds
        if (peopleChanged || confChanged || variantChanged) destroyDetector() else Unit
        if (fpsChanged && provider != null) {
            // Frame-rate limiting happens in onFrame(); rebinding is not required.
            lastFrameAt = 0L
        }
        if (minChanged) {
            policy.resetSeenPeople(tracker.active().filter { it.established }.map { it.id })
        }
        scheduleUpdate()
    }

    private fun observeConfigReload() {
        AppPrefs.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                Session.error = null
                if (!Session.cameraReady) openCamera()
            }
            ACTION_STOP -> {
                finishAndStop()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_RECORD -> {
                if (recorder.isRecording) stopTake("Stopped manually") else startTake("Manual")
            }
            ACTION_FLIP -> flipCamera()
            ACTION_TORCH -> toggleTorch()
            ACTION_RELOAD -> {
                val previous = config
                config = AppPrefs.load(this)
                policy.updateConfig(config)
                Session.minRecordSeconds = config.minRecordSeconds
                // Rebuilding the camera is only needed when the detector changed;
                // otherwise rebinding would drop a take that is in progress.
                val detectorChanged = previous.modelVariant != config.modelVariant ||
                        previous.maxPeople != config.maxPeople ||
                        previous.minDetectionConfidence != config.minDetectionConfidence
                if (detectorChanged) {
                    destroyDetector()
                    ensureDetectorAsync()
                }
                if (!Session.cameraReady) openCamera()
            }
        }
        return START_STICKY
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Only claim the microphone type when we could actually record audio:
            // on Android 14+ declaring a type you do not use is a hard crash.
            val micGranted = recorder.hasAudioPermission()
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (config.withAudio && micGranted) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            try {
                startForeground(NOTIF_ID, notification, type)
            } catch (t: Throwable) {
                Log.w(TAG, "typed foreground start failed", t)
                startForeground(NOTIF_ID, notification)
            }
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // ---------------------------------------------------------------- camera

    private fun openCamera() {
        if (provider != null) {
            bindUseCases()
            return
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                provider = future.get()
                Session.cameraReady = false
                bindUseCases()
            } catch (t: Throwable) {
                Log.e(TAG, "camera provider failed", t)
                Session.error = "Camera unavailable: ${t.message}"
                notifyUpdate()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases() {
        val camProvider = provider ?: return
        val lens = if (Session.lensFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        val selector = CameraSelector.Builder().requireLensFacing(lens).build()

        val qualitySelector = QualitySelector.fromOrderedList(
            listOf(Quality.FHD, Quality.HD, Quality.SD),
            androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
        )
        val rec = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()
        val capture = VideoCapture.withOutput(rec)
        recorder.bind(capture)
        videoCapture = capture

        val resolution = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    android.util.Size(640, 480),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()

        val analyser = ImageAnalysis.Builder()
            .setResolutionSelector(resolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        analyser.setAnalyzer(analysisExecutor) { proxy -> onFrame(proxy) }
        analysis = analyser

        val previewUseCase = Preview.Builder().build()
        preview = previewUseCase

        try {
            camProvider.unbindAll()
            camera = camProvider.bindToLifecycle(
                this as androidx.lifecycle.LifecycleOwner,
                selector,
                previewUseCase,
                capture,
                analyser,
            )
            Session.cameraReady = true
            Session.lensFacing = lens
            if (config.autoWatch) Session.watching = true
            scheduleUpdate()
            ensureDetectorAsync()
        } catch (t: Throwable) {
            Log.e(TAG, "bind failed", t)
            Session.error = "Could not start camera: ${t.message}"
            notifyUpdate()
        }
    }

    private fun ensureDetectorAsync() {
        if (detector != null) return
        if (preparingModel.get()) return
        preparingModel.set(true)
        val variant = config.modelVariant
        Session.modelState = if (ModelManager.isReady(this, variant)) "bundled" else "downloading"
        notifyUpdate()
        if (ModelManager.isReady(this, variant)) {
            buildDetector()
            preparingModel.set(false)
            return
        }
        ModelManager.ensureAsync(this, variant) { ok, err ->
            preparingModel.set(false)
            if (ok) {
                Session.modelState = "downloaded"
                buildDetector()
            } else {
                Session.modelState = "unavailable"
                Session.error = "Pose model could not be loaded: $err"
            }
            notifyUpdate()
        }
    }

    private fun buildDetector() {
        synchronized(detectorLock) {
            try {
                detector?.close()
                detector = PoseDetector(applicationContext, config.maxPeople, config.minDetectionConfidence, config.modelVariant)
                detector?.setup()
                detectorConfig = config
                Session.error = null
                Session.modelState = "ready"
            } catch (t: Throwable) {
                Log.e(TAG, "detector setup failed", t)
                detector = null
                Session.error = "Pose model failed to load"
            }
        }
        notifyUpdate()
    }

    private fun destroyDetector() {
        synchronized(detectorLock) {
            detector?.close()
            detector = null
            detectorConfig = null
        }
    }

    // -------------------------------------------------------------- analysis

    private fun onFrame(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        val fpsInterval = 1000L / config.analysisFps.coerceAtLeast(1)
        if (now - lastFrameAt < fpsInterval) {
            proxy.close()
            return
        }
        lastFrameAt = now
        frameRotation = proxy.imageInfo.rotationDegrees

        val bitmap = try {
            proxy.toBitmap()
        } catch (t: Throwable) {
            proxy.close()
            return
        }

        val detectorNow = detector
        if (detectorNow == null || !detectorNow.isReady) {
            proxy.close()
            ensureDetectorAsync()
            publishFrame(emptyList(), bitmap.width, bitmap.height, 0)
            bitmap.recycle()
            return
        }

        val started = SystemClock.elapsedRealtime()
        val detections = try {
            detectorNow.detect(bitmap, started)
        } catch (t: Throwable) {
            Log.w(TAG, "detect error", t)
            emptyList()
        }
        val inference = SystemClock.elapsedRealtime() - started
        publishFrame(detections, bitmap.width, bitmap.height, inference)
        bitmap.recycle()
        proxy.close()
    }

    private fun publishFrame(
        detections: List<com.sarak.cameraai.ai.Person>,
        w: Int,
        h: Int,
        inference: Long,
    ) {
        analyzing = true
        val now = SystemClock.elapsedRealtime()

        // Only faces/people big enough to matter; tiny background people are noise.
        val filtered = detections.filter { it.height >= 0.10f && it.score >= config.minDetectionConfidence }
        val tracks = tracker.update(filtered, now)
        val established = tracks.filter { it.established }

        val list = established.map { it.person }
        scene.publish(
            list,
            w,
            h,
            Session.lensFacing,
            frameRotation,
            System.currentTimeMillis(),
            inference,
        )
        SceneBridge.publish(scene)
        Session.peopleCount = list.size
        Session.inferenceMs = inference

        runPolicy(established.map { it.id }, filtered.size, list.size, now)
        notifyUpdate()
    }

    private fun runPolicy(ids: List<Int>, rawCount: Int, established: Int, now: Long) {
        if (!Session.watching) return
        val action = policy.onFrame(
            now = now,
            ids = ids,
            count = established,
            recording = recorder.isRecording,
            segmentAgeMs = if (recorder.isRecording) now - segmentStartElapsed else 0L,
        )
        when (action) {
            RecordPolicy.Action.START -> {
                Session.lastTrigger = policy.lastTrigger
                startTake(policy.lastTrigger)
            }
            RecordPolicy.Action.STOP -> {
                stopTake(policy.lastTrigger.ifEmpty { "Auto stop" })
            }
            RecordPolicy.Action.ROTATE -> {
                recorder.rotate()
            }
            RecordPolicy.Action.NONE -> Unit
        }
    }

    private var segmentStartElapsed = 0L

    // ------------------------------------------------------------- recording

    fun startTake(reason: String) {
        if (recorder.isRecording) return
        val take = recorder.beginTake()
        val ok = recorder.start(config.withAudio)
        if (ok) {
            policy.beginTake(SystemClock.elapsedRealtime(), tracker.active().filter { it.established }.map { it.id })
            Session.lastTrigger = reason
        } else {
            Session.error = "Could not start recording"
        }
        notifyUpdate()
    }

    fun stopTake(reason: String) {
        if (!recorder.isRecording) {
            policy.onStopped()
            return
        }
        recorder.stop()
        policy.onStopped()
        Session.lastTrigger = reason
        notifyUpdate()
    }

    private fun onRecorderEvent(event: VideoRecorder.Event) {
        when (event) {
            is VideoRecorder.Event.Started -> {
                policy.onSegmentStarted(SystemClock.elapsedRealtime())
                segmentStartElapsed = SystemClock.elapsedRealtime()
                Session.recording = true
                Session.clipCount++
                notifyUpdate()
            }
            is VideoRecorder.Event.SegmentSaved -> {
                Session.recording = recorder.isRecording
                Session.lastClipPath = event.displayName
                Session.lastClipMs = event.durationMs
                notifyUpdate()
            }
            is VideoRecorder.Event.Failed -> {
                Session.error = event.reason
                Session.recording = false
                policy.onStopped()
                notifyUpdate()
            }
        }
        updateNotification()
    }

    // ------------------------------------------------------------ controls

    fun flipCamera() {
        if (recorder.isRecording) return
        Session.lensFacing = if (Session.lensFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        tracker.reset()
        scene.clear()
        bindUseCases()
    }

    fun toggleTorch() {
        val cam = camera ?: return
        torch = !torch
        cam.cameraControl.enableTorch(torch)
        Session.torchOn = torch
        notifyUpdate()
    }

    /**
     * Attaches (or detaches) the on-screen surface. CameraX lets us hand the
     * same [Preview] use case a surface provider after binding, so the Activity
     * can come and go without the service being torn down.
     */
    fun attachPreview(provider: Preview.SurfaceProvider?) {
        try {
            preview?.setSurfaceProvider(provider)
        } catch (t: Throwable) {
            Log.w(TAG, "attachPreview failed", t)
        }
    }

    fun setAutoWatch(enabled: Boolean) {
        Session.watching = enabled && Session.cameraReady
        if (!enabled && recorder.isRecording) stopTake("Watcher paused")
        notifyUpdate()
    }

    fun setTorch(on: Boolean) {
        torch = on
        camera?.cameraControl?.enableTorch(on)
        Session.torchOn = on
    }

    // --------------------------------------------------------------- status

    private fun updateStatusSnapshot() {
        val now = SystemClock.elapsedRealtime()
        Session.recording = recorder.isRecording
        if (recorder.isRecording) {
            Session.elapsedMs = policy.elapsed(now)
            Session.requiredMs = config.minRecordSeconds * 1000L
            Session.clipProgress = policy.progress(now)
            Session.inGrace = policy.inGrace(now)
            val left = policy.remainingMs(now)
            Session.statusLine = if (left > 0) {
                "Recording · ${Format.clock(Session.elapsedMs)} / ${Format.durationLong(config.minRecordSeconds)}"
            } else {
                "Recording · ${Format.clock(Session.elapsedMs)} · minimum met"
            }
            Session.subStatusLine = buildString {
                append(if (left > 0) "${Format.clock(left)} to go" else "keeping roll while people present")
                if (Session.peopleCount > 0) append(" · ${Session.peopleCount} in frame")
            }
        } else {
            Session.elapsedMs = 0
            Session.requiredMs = config.minRecordSeconds * 1000L
            Session.clipProgress = 0
            Session.inGrace = false
            Session.statusLine = when {
                !Session.cameraReady -> "Starting camera…"
                !Session.watching -> "Watcher paused"
                else -> "Watching · ${Session.peopleCount} in frame"
            }
            Session.subStatusLine = when {
                !Session.watching -> "Tap to resume"
                config.triggerMode == Config.TRIGGER_SECOND_PERSON ->
                    "Starts when a second person joins · min ${Format.durationLong(config.minRecordSeconds)}"
                config.triggerMode == Config.TRIGGER_NEW_PERSON ->
                    "Starts on a new face · min ${Format.durationLong(config.minRecordSeconds)}"
                else -> "Tap record to start"
            }
        }
    }

    private var lastNotifyAt = 0L

    private fun notifyUpdate() {
        updateStatusSnapshot()
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifyAt < 250) return
        lastNotifyAt = now
        updateNotification()
    }

    private fun scheduleUpdate() {
        updateStatusSnapshot()
        updateNotification()
    }

    // ---------------------------------------------------------- notification

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_service_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_service_desc)
                setShowBadge(false)
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val toggle = PendingIntent.getService(
            this, 1,
            Intent(this, CameraService::class.java).setAction(ACTION_TOGGLE_RECORD),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when {
            recorder.isRecording -> getString(
                R.string.notif_recording,
                Format.clock(policy.elapsed(SystemClock.elapsedRealtime())),
            )
            Session.peopleCount > 0 -> getString(R.string.notif_watching, Session.peopleCount)
            else -> getString(R.string.notif_waiting)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_watch)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                if (recorder.isRecording) R.drawable.ic_stop else R.drawable.ic_rec,
                if (recorder.isRecording) "Stop" else "Record",
                toggle,
            )
            .build()
    }

    private var lastNotificationText: String? = null

    private fun updateNotification() {
        val notification = buildNotification()
        val nm = getSystemService(NotificationManager::class.java) ?: return
        try {
            nm.notify(NOTIF_ID, notification)
        } catch (t: Throwable) {
            Log.w(TAG, "notify failed", t)
        }
    }

    // ------------------------------------------------------------- lifecycle

    override fun onDestroy() {
        try {
            AppPrefs.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        } catch (_: Throwable) {
        }
        if (recorder.isRecording) recorder.stop()
        recorder.release()
        analysis?.clearAnalyzer()
        provider?.unbindAll()
        destroyDetector()
        analysisExecutor.shutdown()
        Session.reset()
        Session.cameraReady = false
        super.onDestroy()
    }

    /** Local binder so the Activity can hand over a preview surface and controls. */
    inner class LocalBinder : android.os.Binder() {
        fun service(): CameraService = this@CameraService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    private fun finishAndStop() {
        SceneBridge.scene = null
        if (recorder.isRecording) recorder.stop()
        policy.onStopped()
        stopSelf()
    }

    companion object {
        private const val TAG = "CameraService"
        const val CHANNEL_ID = "camera_ai_watch"
        const val NOTIF_ID = 42

        const val ACTION_START = "com.sarak.cameraai.START"
        const val ACTION_STOP = "com.sarak.cameraai.STOP"
        const val ACTION_TOGGLE_RECORD = "com.sarak.cameraai.TOGGLE_RECORD"
        const val ACTION_FLIP = "com.sarak.cameraai.FLIP"
        const val ACTION_TORCH = "com.sarak.cameraai.TORCH"
        const val ACTION_RELOAD = "com.sarak.cameraai.RELOAD"

        fun hasCameraPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED

        fun start(context: Context) {
            val intent = Intent(context, CameraService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun send(context: Context, action: String) {
            try {
                context.startService(Intent(context, CameraService::class.java).setAction(action))
            } catch (t: Throwable) {
                Log.w(TAG, "action $action failed", t)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CameraService::class.java).setAction(ACTION_STOP))
        }
    }
}
