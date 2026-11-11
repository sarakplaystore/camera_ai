package com.sarak.cameraai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PersonTrackerTest {

    private fun person(
        x: Float,
        y: Float,
        w: Float = 0.15f,
        h: Float = 0.4f,
        score: Float = 0.9f,
    ): Person {
        val raw = FloatArray(KP.COUNT * 4) { 0.9f }
        return Person(
            score = score,
            raw = raw,
            left = x - w / 2,
            top = y,
            right = x + w / 2,
            bottom = y + h,
        ).also {
            it.cx = x
            it.cy = y + h / 2
        }
    }

    @Test
    fun keepsTheSameIdWhenAPersonMovesAcrossFrames() {
        val tracker = PersonTracker()
        val first = tracker.update(listOf(person(0.2f, 0.2f)), 0)
        val id = first.first().id
        val moved = tracker.update(listOf(person(0.30f, 0.22f)), 100)
        assertEquals(id, moved.first().id)
    }

    @Test
    fun assignesDifferentIdsToTwoPeople() {
        val tracker = PersonTracker()
        val tracks = tracker.update(
            listOf(person(0.2f, 0.2f), person(0.75f, 0.25f)),
            0,
        )
        assertEquals(2, tracks.size)
        assertNotEquals(tracks[0].id, tracks[1].id)
    }

    @Test
    fun keepsTrackThroughAShortOcclusion() {
        val tracker = PersonTracker()
        tracker.update(listOf(person(0.3f, 0.2f)), 0)
        val id = tracker.active().first().id
        tracker.update(emptyList(), 100)
        tracker.update(emptyList(), 200)
        val back = tracker.update(listOf(person(0.32f, 0.21f)), 300)
        assertEquals(id, back.first().id)
    }

    @Test
    fun aTrackBecomesEstablishedAfterTwoFrames() {
        val tracker = PersonTracker()
        assertEquals(0, tracker.establishedCount())
        tracker.update(listOf(person(0.3f, 0.2f)), 0)
        assertEquals(0, tracker.establishedCount())
        tracker.update(listOf(person(0.3f, 0.2f)), 100)
        assertEquals(1, tracker.establishedCount())
    }

    @Test
    fun duplicateOverlappingBoxesAreCollapsedToOne() {
        val tracker = PersonTracker()
        val one = person(0.5f, 0.2f, w = 0.2f, h = 0.5f, score = 0.95f)
        val duplicate = person(0.51f, 0.21f, w = 0.16f, h = 0.45f, score = 0.7f)
        val deduped = tracker.dedupe(listOf(one, duplicate))
        assertEquals(1, deduped.size)
        assertEquals(0.95f, deduped[0].score, 0.001f)
    }

    @Test
    fun twoSeparatePeopleAreBothKept() {
        val tracker = PersonTracker()
        val deduped = tracker.dedupe(
            listOf(person(0.2f, 0.2f), person(0.75f, 0.2f)),
        )
        assertEquals(2, deduped.size)
    }
}
