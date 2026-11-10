package com.sarak.cameraai.record

import com.sarak.cameraai.settings.Config

/**
 * Decides when a take starts and stops.
 *
 * The rule the user cares about:
 *
 *   * a new person entering the frame opens a take
 *   * once open, the take is guaranteed to run [Config.minRecordSeconds]
 *     (10 minutes by default) even if everyone walks away
 *   * if people stay, it keeps rolling; when the frame is empty it lingers for
 *     [Config.stopGraceSeconds] and then closes
 *   * [Config.maxRecordSeconds] is a hard ceiling (0 = unlimited)
 *
 * The policy is deliberately pure + synchronous so it can be unit-tested and so
 * the service stays readable.
 */
class RecordPolicy(private var config: Config) {

    enum class Action { NONE, START, STOP, ROTATE }

    private var takeStartMs: Long = 0L
    private var lastPresentMs: Long = 0L
    private var segmentStartMs: Long = 0L
    private var currentlyRecording: Boolean = false
    private var knownIds: MutableSet<Int> = HashSet()

    /** Everyone the tracker had already introduced before this take. */
    private var baselineCount: Int = 0

    var lastTrigger: String = ""
        private set

    var lastReason: String = ""
        private set

    var recordActive: Boolean = false

    /** True while the empty-frame grace timer is running. */
    fun inGrace(now: Long): Boolean =
        currentlyRecording && lastPresentMs > 0 && now - lastPresentMs < graceMs && elapsed(now) >= minMs

    val isRecording: Boolean get() = currentlyRecording

    fun updateConfig(config: Config) {
        this.config = config
    }

    val configNow: Config get() = config

    private val minMs: Long get() = config.minRecordSeconds * 1000L
    private val graceMs: Long get() = config.stopGraceSeconds * 1000L
    private val maxMs: Long get() = config.maxRecordSeconds * 1000L

    fun elapsed(now: Long): Long = if (currentlyRecording) now - takeStartMs else 0L

    /** Seconds still owed to the 10-minute promise. */
    fun remainingMs(now: Long): Long = if (!currentlyRecording) 0L else (minMs - elapsed(now)).coerceAtLeast(0L)

    fun progress(now: Long): Int {
        if (!currentlyRecording || minMs <= 0) return 0
        return ((elapsed(now) * 100) / minMs).toInt().coerceIn(0, 100)
    }

    /**
     * Call once per analysis frame with the *established* track ids currently
     * visible and the current head count.
     *
     * @param ids stable ids of established tracks in frame
     * @param count total people detected in frame (raw)
     */
    fun onFrame(
        now: Long,
        ids: List<Int>,
        count: Int,
        recording: Boolean,
        segmentAgeMs: Long,
    ): Action {
        currentlyRecording = recording
        val present = ids.isNotEmpty()

        if (recording) {
            if (present) lastPresentMs = now
            if (maxMs > 0 && elapsed(now) >= maxMs) {
                lastTrigger = "Max clip length reached"
                return Action.STOP
            }
            if (segmentAgeMs >= VideoRecorder.MAX_SEGMENT_MS) return Action.ROTATE
            if (elapsed(now) >= minMs) {
                val idleFor = if (lastPresentMs == 0L) 0L else now - lastPresentMs
                if (!present && idleFor >= graceMs) {
                    lastTrigger = "Clip length reached, nobody in frame"
                    return Action.STOP
                }
                if (!config.extendWhilePresent && elapsed(now) >= minMs + graceMs && count == 0) {
                    lastTrigger = "Minimum reached"
                    return Action.STOP
                }
            }
            return Action.NONE
        }

        // Not recording: look for a reason to open a take.
        when (config.triggerMode) {
            Config.TRIGGER_MANUAL -> return Action.NONE

            Config.TRIGGER_SECOND_PERSON -> {
                if (count >= 2) {
                    lastTrigger = "Second person entered the frame"
                    knownIds = ids.toMutableSet()
                    beginTake(now, ids)
                    return Action.START
                }
                knownIds = ids.toMutableSet()
                return Action.NONE
            }

            Config.TRIGGER_NEW_PERSON -> {
                val hasUnseen = ids.any { it !in knownIds }
                knownIds = ids.toMutableSet()
                if (ids.isNotEmpty() && hasUnseen) {
                    lastTrigger = if (count >= 2) "New person joined" else "Person appeared"
                    beginTake(now, ids)
                    return Action.START
                }
                return Action.NONE
            }
        }
        return Action.NONE
    }

    fun beginTake(now: Long, ids: List<Int>) {
        currentlyRecording = true
        takeStartMs = now
        segmentStartMs = now
        lastPresentMs = now
        baselineCount = ids.size
        knownIds = ids.toMutableSet()
    }

    fun onSegmentStarted(now: Long) {
        segmentStartMs = now
    }

    fun onStopped() {
        currentlyRecording = false
        takeStartMs = 0
        lastPresentMs = 0
        knownIds.clear()
    }

    fun resetSeenPeople(ids: List<Int>) {
        knownIds = ids.toMutableSet()
    }
}
