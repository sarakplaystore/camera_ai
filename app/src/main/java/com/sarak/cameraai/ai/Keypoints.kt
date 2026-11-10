package com.sarak.cameraai.ai

/**
 * Landmark indices used by MediaPipe BlazePose (33 points).
 * See https://developers.google.com/mediapipe/solutions/vision/pose_landmarker
 */
object KP {
    const val NOSE = 0
    const val LEFT_EYE_INNER = 1
    const val LEFT_EYE = 2
    const val LEFT_EYE_OUTER = 3
    const val RIGHT_EYE_INNER = 4
    const val RIGHT_EYE = 5
    const val RIGHT_EYE_OUTER = 6
    const val LEFT_EAR = 7
    const val RIGHT_EAR = 8
    const val MOUTH_LEFT = 9
    const val MOUTH_RIGHT = 10
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_ELBOW = 13
    const val RIGHT_ELBOW = 14
    const val LEFT_WRIST = 15
    const val RIGHT_WRIST = 16
    const val LEFT_PINKY = 17
    const val RIGHT_PINKY = 18
    const val LEFT_INDEX = 19
    const val RIGHT_INDEX = 20
    const val LEFT_THUMB = 21
    const val RIGHT_THUMB = 22
    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24
    const val LEFT_KNEE = 25
    const val RIGHT_KNEE = 26
    const val LEFT_ANKLE = 27
    const val RIGHT_ANKLE = 28
    const val LEFT_HEEL = 29
    const val RIGHT_HEEL = 30
    const val LEFT_FOOT_INDEX = 31
    const val RIGHT_FOOT_INDEX = 32

    const val COUNT = 33

    /** Pairs drawn as bones on the overlay. */
    val CONNECTIONS: Array<IntArray> = arrayOf(
        // Face
        intArrayOf(LEFT_EAR, LEFT_EYE_OUTER),
        intArrayOf(LEFT_EYE_OUTER, LEFT_EYE),
        intArrayOf(LEFT_EYE, LEFT_EYE_INNER),
        intArrayOf(LEFT_EYE_INNER, NOSE),
        intArrayOf(NOSE, RIGHT_EYE_INNER),
        intArrayOf(RIGHT_EYE_INNER, RIGHT_EYE),
        intArrayOf(RIGHT_EYE, RIGHT_EYE_OUTER),
        intArrayOf(RIGHT_EYE_OUTER, RIGHT_EAR),
        intArrayOf(MOUTH_LEFT, MOUTH_RIGHT),
        // Torso
        intArrayOf(LEFT_SHOULDER, RIGHT_SHOULDER),
        intArrayOf(LEFT_SHOULDER, LEFT_HIP),
        intArrayOf(RIGHT_SHOULDER, RIGHT_HIP),
        intArrayOf(LEFT_HIP, RIGHT_HIP),
        // Left arm
        intArrayOf(LEFT_SHOULDER, LEFT_ELBOW),
        intArrayOf(LEFT_ELBOW, LEFT_WRIST),
        intArrayOf(LEFT_WRIST, LEFT_PINKY),
        intArrayOf(LEFT_WRIST, LEFT_INDEX),
        intArrayOf(LEFT_WRIST, LEFT_THUMB),
        // Right arm
        intArrayOf(RIGHT_SHOULDER, RIGHT_ELBOW),
        intArrayOf(RIGHT_ELBOW, RIGHT_WRIST),
        intArrayOf(RIGHT_WRIST, RIGHT_PINKY),
        intArrayOf(RIGHT_WRIST, RIGHT_INDEX),
        intArrayOf(RIGHT_WRIST, RIGHT_THUMB),
        // Left leg
        intArrayOf(LEFT_HIP, LEFT_KNEE),
        intArrayOf(LEFT_KNEE, LEFT_ANKLE),
        intArrayOf(LEFT_ANKLE, LEFT_HEEL),
        intArrayOf(LEFT_ANKLE, LEFT_FOOT_INDEX),
        intArrayOf(LEFT_HEEL, LEFT_FOOT_INDEX),
        // Right leg
        intArrayOf(RIGHT_HIP, RIGHT_KNEE),
        intArrayOf(RIGHT_KNEE, RIGHT_ANKLE),
        intArrayOf(RIGHT_ANKLE, RIGHT_HEEL),
        intArrayOf(RIGHT_ANKLE, RIGHT_FOOT_INDEX),
        intArrayOf(RIGHT_HEEL, RIGHT_FOOT_INDEX),
    )

    /**
     * A rough "is this a full body" test used to filter lockers/posters that only
     * yield a couple of confident points.
     */
    fun reliableCoreCount(visibility: FloatArray): Int {
        var n = 0
        for (i in intArrayOf(LEFT_SHOULDER, RIGHT_SHOULDER, LEFT_HIP, RIGHT_HIP, NOSE)) {
            if (visibility[i] >= 0.5f) n++
        }
        return n
    }
}
