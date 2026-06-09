package com.example.palmlinecheck

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sqrt

data class PalmValidationResult(
    val isHandPresent: Boolean = false,
    val isPalmFacingCamera: Boolean = false,
    val isPalmFlat: Boolean = false,
    val isHandSteady: Boolean = false,
    val isDorsal: Boolean = false,
    val handLabel: String = "",
    val validationMessages: List<ValidationMessage> = emptyList()
) {
    val isValid: Boolean
        get() = isHandPresent && isPalmFacingCamera && isPalmFlat && isHandSteady

    val readyToCapture: Boolean
        get() = isHandPresent && isPalmFacingCamera && isPalmFlat
}

data class ValidationMessage(
    val type: ValidationType,
    val message: String,
    val isPassed: Boolean
)

enum class ValidationType {
    HAND_PRESENT,
    DORSAL_CHECK,
    PALM_FLAT,
    HAND_STEADY
}

class PalmValidator {

    companion object {
        private const val PALM_FLAT_ANGLE_THRESHOLD = 35f
        private const val HAND_STEADY_DRIFT_THRESHOLD = 0.02f
        private const val REQUIRED_STEADY_FRAMES = 5
        // Z-diff threshold: when dorsal faces camera, middle tip z > wrist z by this margin
        private const val DORSAL_Z_DIFF_THRESHOLD = 0.01f
    }

    private val wristPositionHistory = mutableListOf<Pair<Float, Float>>()
    private var steadyFrameCount = 0

    fun validate(result: HandLandmarkerResult?): PalmValidationResult {
        val messages = mutableListOf<ValidationMessage>()

        val isHandPresent = result?.landmarks()?.isNotEmpty() == true
        messages.add(ValidationMessage(
            type = ValidationType.HAND_PRESENT,
            message = if (isHandPresent) "Hand detected ✓" else "No hand detected — place your palm in the frame",
            isPassed = isHandPresent
        ))

        if (!isHandPresent) {
            clearHistory()
            return PalmValidationResult(isHandPresent = false, validationMessages = messages)
        }

        val landmarks = result!!.landmarks().first()

        // Resolve actual hand side.
        // MediaPipe handedness is from the image perspective (mirrored for rear camera):
        // "Left" label → user's actual Right hand, "Right" label → user's actual Left hand.
        @Suppress("DEPRECATION")
        val mpLabel = result.handednesses().firstOrNull()?.firstOrNull()?.categoryName()?.lowercase()
        val isActualRightHand = mpLabel == "left"
        // MediaPipe label is from image perspective = user's actual hand on rear camera
        val handLabel = when (mpLabel) {
            "left"  -> "Left Hand"
            "right" -> "Right Hand"
            else    -> "Unknown Hand"
        }

        // Check 2: Dorsal detection (back of hand vs palm)
        val isDorsal = detectDorsal(landmarks, isActualRightHand)
        val isPalmFacing = !isDorsal
        messages.add(ValidationMessage(
            type = ValidationType.DORSAL_CHECK,
            message = if (isPalmFacing) "Palm side facing camera ✓"
                      else             "Back of hand detected — flip your hand",
            isPassed = isPalmFacing
        ))

        // Check 3: Palm flat and parallel to camera
        val isPalmFlat = checkPalmFlat(landmarks)
        messages.add(ValidationMessage(
            type = ValidationType.PALM_FLAT,
            message = if (isPalmFlat) "Palm is flat ✓" else "Keep your palm flat and parallel to camera",
            isPassed = isPalmFlat
        ))

        // Check 4: Hand steady
        val isHandSteady = checkHandSteady(landmarks)
        messages.add(ValidationMessage(
            type = ValidationType.HAND_STEADY,
            message = if (isHandSteady) "Hand is steady ✓"
                      else             "Hold your hand still ($steadyFrameCount/$REQUIRED_STEADY_FRAMES)",
            isPassed = isHandSteady
        ))

        return PalmValidationResult(
            isHandPresent  = isHandPresent,
            isPalmFacingCamera = isPalmFacing,
            isPalmFlat     = isPalmFlat,
            isHandSteady   = isHandSteady,
            isDorsal       = isDorsal,
            handLabel      = handLabel,
            validationMessages = messages
        )
    }

    /**
     * Detects whether the dorsal (back) side of the hand faces the camera.
     *
     * Two signals (ported from reference HandDetectionHelper.detectDorsalSide):
     *
     * 1. Z-depth: when dorsal faces camera the middle fingertip is further from the
     *    camera than the wrist (middleTipZ − wristZ > threshold). When palm faces
     *    camera the fingertip is closer (smaller z) or at the same depth.
     *
     * 2. Thumb-side: for a palm-facing right hand the thumb tip is to the LEFT of
     *    the pinky tip (thumbX < pinkyX). If the thumb is on the wrong side the
     *    hand is rotated / showing its back.
     *
     * Both conditions together give a reliable dorsal signal.
     */
    private fun detectDorsal(landmarks: List<NormalizedLandmark>, isRightHand: Boolean): Boolean {
        if (landmarks.size < 21) return false

        val wristZ     = landmarks[0].z()   // WRIST
        val middleTipZ = landmarks[12].z()  // MIDDLE_TIP
        val thumbTipX  = landmarks[4].x()   // THUMB_TIP
        val pinkyTipX  = landmarks[20].x()  // PINKY_TIP

        val zDiff = middleTipZ - wristZ

        // Palm-facing orientation: right hand → thumb left of pinky; left hand → thumb right of pinky
        val thumbOnPalmSide = if (isRightHand) thumbTipX < pinkyTipX
                              else             thumbTipX > pinkyTipX

        return zDiff > DORSAL_Z_DIFF_THRESHOLD && !thumbOnPalmSide
    }

    private fun checkPalmFlat(landmarks: List<NormalizedLandmark>): Boolean {
        val wrist     = landmarks[0]
        val indexMcp  = landmarks[5]
        val middleMcp = landmarks[9]
        val ringMcp   = landmarks[13]
        val pinkyMcp  = landmarks[17]

        // MCP joints should form a roughly horizontal line
        val mcpYValues = listOf(indexMcp.y(), middleMcp.y(), ringMcp.y(), pinkyMcp.y())
        val avgMcpY = mcpYValues.average().toFloat()
        val maxYDeviation = mcpYValues.map { abs(it - avgMcpY) }.maxOrNull() ?: 0f
        val isHorizontallyAligned = maxYDeviation < 0.08f

        // Palm should point roughly upward (fingers above wrist)
        val dx = middleMcp.x() - wrist.x()
        val dy = middleMcp.y() - wrist.y()
        val angleFromVertical = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).toFloat()
        val isVerticallyAligned = abs(angleFromVertical) < PALM_FLAT_ANGLE_THRESHOLD

        // Key landmarks should share a similar depth (parallel to camera plane)
        val zValues = listOf(wrist.z(), indexMcp.z(), middleMcp.z(), ringMcp.z(), pinkyMcp.z())
        val avgZ = zValues.average().toFloat()
        val maxZDeviation = zValues.map { abs(it - avgZ) }.maxOrNull() ?: 0f
        val isParallelToCamera = maxZDeviation < 0.1f

        return isHorizontallyAligned && isVerticallyAligned && isParallelToCamera
    }

    private fun checkHandSteady(landmarks: List<NormalizedLandmark>): Boolean {
        val wrist = landmarks[0]
        wristPositionHistory.add(Pair(wrist.x(), wrist.y()))
        while (wristPositionHistory.size > REQUIRED_STEADY_FRAMES) {
            wristPositionHistory.removeAt(0)
        }

        if (wristPositionHistory.size < REQUIRED_STEADY_FRAMES) {
            steadyFrameCount = wristPositionHistory.size
            return false
        }

        val firstPosition = wristPositionHistory.first()
        var maxDrift = 0f
        for (position in wristPositionHistory) {
            val drift = sqrt(
                (position.first - firstPosition.first).pow(2) +
                (position.second - firstPosition.second).pow(2)
            )
            maxDrift = maxOf(maxDrift, drift)
        }

        val isSteady = maxDrift < HAND_STEADY_DRIFT_THRESHOLD
        if (isSteady) {
            steadyFrameCount = REQUIRED_STEADY_FRAMES
        } else if (maxDrift > HAND_STEADY_DRIFT_THRESHOLD * 2) {
            wristPositionHistory.clear()
            steadyFrameCount = 0
        }
        return isSteady
    }

    fun clearHistory() {
        wristPositionHistory.clear()
        steadyFrameCount = 0
    }

    fun reset() {
        clearHistory()
    }
}
