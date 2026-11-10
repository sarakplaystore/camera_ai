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
    private val maxMissedFrames: Int = 12,
    private val matchDistance: Float = 1.15f,
    private val establishFrames: Int = 3,
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
