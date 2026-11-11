package com.sarak.cameraai.ai

/**
 * Greedy nearest-neighbour tracker. Pose detection has no identity of its own,
 * so we keep a rolling set of tracks and match detections by centre distance.
 *
 * The key product signal — "a *new* person joined the frame" — depends on this:
 * a track only becomes [Track.established] after [establishFrames] consecutive
 * hits, which suppresses one-frame false positives (posters, reflections).
 */
class PersonTracker(
    // A person who turns away or is briefly occluded should keep their id, so
    // we tolerate a good number of missed analyses before dropping the track.
    private val maxMissedFrames: Int = 20,
    // Generous enough to follow somebody walking quickly across frame,
    // tight enough that two people crossing do not swap ids.
    private val matchDistance: Float = 1.6f,
    private val establishFrames: Int = 2,
) {
    class Track(val id: Int) {
        val person: Person = Person(0f, FloatArray(KP.COUNT * 4), 0f, 0f, 0f, 0f)
        var missed: Int = 0
        var lastSeenMs: Long = 0L
        var seenFrames: Int = 0
        var established: Boolean = false
        /** Set once this track has been announced for the current recording. */
        var announced: Boolean = false
    }

    private val tracks = ArrayList<Track>(8)
    private var nextId = 1

    fun active(): List<Track> = tracks

    fun establishedCount(): Int = tracks.count { it.established }

    /**
     * MediaPipe sometimes emits two overlapping boxes for one person (one from
     * the pose detector, one from a partly tracked duplicate). Keeping both
     * double counts people and can falsely arm the "someone joined" trigger, so
     * anything mostly inside a stronger detection is discarded.
     */
    fun dedupe(detections: List<Person>): List<Person> {
        if (detections.size < 2) return detections
        val sorted = detections.sortedByDescending { it.area * it.score }
        val kept = ArrayList<Person>(sorted.size)
        for (candidate in sorted) {
            val duplicate = kept.any { other -> overlap(candidate, other) >= 0.6f }
            if (!duplicate) kept += candidate
        }
        return kept
    }

    private fun overlap(a: Person, b: Person): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val intersection = (right - left) * (bottom - top)
        val smallest = minOf(a.area, b.area).coerceAtLeast(1e-5f)
        return intersection / smallest
    }

    fun update(detections: List<Person>, nowMs: Long): List<Track> {
        val freeTracks = tracks.toMutableList()
        val freeDetections = detections.toMutableList()
        val matched = ArrayList<Track>(detections.size)

        // Greedy: repeatedly take the globally closest remaining pair.
        while (freeTracks.isNotEmpty() && freeDetections.isNotEmpty()) {
            var bestTrack: Track? = null
            var bestDet: Person? = null
            var bestDist = Float.MAX_VALUE
            for (t in freeTracks) {
                for (d in freeDetections) {
                    val dist = t.person.distanceTo(d)
                    if (dist < bestDist) {
                        bestDist = dist
                        bestTrack = t
                        bestDet = d
                    }
                }
            }
            if (bestTrack == null || bestDet == null || bestDist > matchDistance) break
            freeTracks.remove(bestTrack)
            freeDetections.remove(bestDet)

            bestTrack.person.copyFrom(bestDet)
            bestTrack.person.id = bestTrack.id
            bestTrack.missed = 0
            bestTrack.lastSeenMs = nowMs
            bestTrack.seenFrames++
            bestTrack.person.seenFrames = bestTrack.seenFrames
            if (!bestTrack.established && bestTrack.seenFrames >= establishFrames) {
                bestTrack.established = true
            }
            matched += bestTrack
        }

        // Whatever is left over is somebody new.
        for (det in freeDetections) {
            val track = Track(nextId++)
            track.person.copyFrom(det)
            track.person.id = track.id
            track.lastSeenMs = nowMs
            track.seenFrames = 1
            track.established = establishFrames <= 1
            track.person.seenFrames = track.seenFrames
            tracks += track
            matched += track
        }

        // Age out anything that vanished.
        val iterator = tracks.iterator()
        while (iterator.hasNext()) {
            val track = iterator.next()
            if (track !in matched) {
                track.missed++
                if (track.missed > maxMissedFrames) iterator.remove()
            }
        }
        return tracks
    }

    fun reset() {
        tracks.clear()
        nextId = 1
    }
}
