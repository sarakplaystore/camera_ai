package com.sarak.cameraai.record

import com.sarak.cameraai.settings.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The promise this app makes: once a take opens it runs for at least
 * [Config.minRecordSeconds] — 10 minutes by default — no matter what happens in
 * front of the camera.
 */
class RecordPolicyTest {

    private val tenMinutes = 10 * 60 * 1000L

    private fun policy(
        trigger: Int = Config.TRIGGER_SECOND_PERSON,
        minSeconds: Int = 600,
        graceSeconds: Int = 30,
        maxSeconds: Int = 3600,
        extend: Boolean = true,
    ) = RecordPolicy(
        Config(
            triggerMode = trigger,
            minRecordSeconds = minSeconds,
            stopGraceSeconds = graceSeconds,
            maxRecordSeconds = maxSeconds,
            extendWhilePresent = extend,
        ),
    )

    @Test
    fun secondPersonStartsATake() {
        val p = policy()
        assertEquals(RecordPolicy.Action.NONE, p.onFrame(0, listOf(1), 1, false, 0))
        assertEquals(RecordPolicy.Action.START, p.onFrame(1000, listOf(1, 2), 2, false, 0))
    }

    @Test
    fun aSinglePersonDoesNotStartWhenSomeoneMustJoin() {
        val p = policy()
        repeat(50) { i ->
            assertEquals(
                RecordPolicy.Action.NONE,
                p.onFrame(i * 500L, listOf(1), 1, false, 0),
            )
        }
    }

    @Test
    fun clipNeverStopsBeforeTheMinimumEvenWhenEveryoneLeaves() {
        val p = policy()
        var now = 0L
        p.onFrame(now, listOf(1, 2), 2, false, 0)
        p.onFrame(now, listOf(1, 2), 2, true, 0)

        // From 10 s in, the frame is empty. The take must survive to 10 minutes.
        while (now < tenMinutes - 1000) {
            now += 500
            val ids = if (now < 10_000) listOf(1, 2) else emptyList()
            val action = p.onFrame(now, ids, ids.size, true, now)
            assertEquals("stopped early at $now", RecordPolicy.Action.NONE, action)
        }

        // Past the minimum, with nobody in frame and the grace window elapsed.
        now = tenMinutes + 31_000
        assertEquals(RecordPolicy.Action.STOP, p.onFrame(now, emptyList(), 0, true, now))
    }

    @Test
    fun keepsRollingWhilePeopleStay() {
        val p = policy()
        var now = 0L
        p.onFrame(now, listOf(1, 2), 2, true, 0)
        // People stay for 40 minutes; a 60 minute cap is not reached.
        while (now < 40 * 60 * 1000L) {
            now += 1000
            val action = p.onFrame(now, listOf(1, 2), 2, true, 0)
            assertEquals("stopped while people present at $now", RecordPolicy.Action.NONE, action)
        }
        assertTrue(p.progress(now) == 100)
    }

    @Test
    fun honoursTheMaximumClipLength() {
        val p = policy(maxSeconds = 60)
        var now = 0L
        p.onFrame(now, listOf(1, 2), 2, true, 0)
        now = 61_000
        assertEquals(RecordPolicy.Action.STOP, p.onFrame(now, listOf(1, 2), 2, true, now))
    }

    @Test
    fun rotatesLongSegments() {
        val p = policy()
        val at = 5 * 60 * 1000L
        p.onFrame(0, listOf(1, 2), 2, true, 0)
        assertEquals(
            RecordPolicy.Action.ROTATE,
            p.onFrame(at, listOf(1, 2), 2, true, VideoRecorder.MAX_SEGMENT_MS),
        )
    }

    @Test
    fun newPersonModeTriggersOnAnyNewcomer() {
        val p = policy(trigger = Config.TRIGGER_NEW_PERSON)
        assertEquals(RecordPolicy.Action.START, p.onFrame(0, listOf(7), 1, false, 0))
    }

    @Test
    fun doesNotRetriggerForTheSameGroupAfterAStop() {
        val p = policy(trigger = Config.TRIGGER_NEW_PERSON)
        p.onFrame(0, listOf(1), 1, false, 0)
        p.onStopped()
        // Same track still in frame: no new take.
        assertEquals(RecordPolicy.Action.NONE, p.onFrame(1000, listOf(1), 1, false, 0))
        // A newcomer arrives: new take.
        assertEquals(RecordPolicy.Action.START, p.onFrame(2000, listOf(1, 9), 2, false, 0))
    }

    @Test
    fun manualModeNeverSelfStarts() {
        val p = policy(trigger = Config.TRIGGER_MANUAL)
        repeat(20) { i ->
            assertEquals(
                RecordPolicy.Action.NONE,
                p.onFrame(i * 1000L, listOf(1, 2, 3), 3, false, 0),
            )
        }
    }
}
