// In EmotionMapper.kt

package com.example.myapplication

// Renaming this to avoid conflict with your existing EmotionResult Composable
data class MappedEmotion(val label: String, val description: String)

/**
 * Maps the model's output vector (Arousal, Dominance, Valence) to a human-readable emotion.
 */
fun mapVectorToEmotion(vector: EmotionVector): MappedEmotion {
    val arousal = vector.arousal
    val dominance = vector.dominance
    val valence = vector.valence

    // Define the center of the emotion map
    val midPoint = 0.5f

    // Top-Right Quadrant: High Arousal, High Valence
    if (arousal >= midPoint && valence >= midPoint) {
        return if (dominance >= midPoint) {
            MappedEmotion("Excited", "High energy, positive, and in control.")
        } else {
            MappedEmotion("Happy", "Positive energy and pleasant feelings.")
        }
    }
    // Top-Left Quadrant: High Arousal, Low Valence
    else if (arousal >= midPoint && valence < midPoint) {
        return if (dominance >= midPoint) {
            MappedEmotion("Angry", "High energy, negative, and assertive.")
        } else {
            MappedEmotion("Stressed", "High energy but feeling unpleasant and overwhelmed.")
        }
    }
    // Bottom-Left Quadrant: Low Arousal, Low Valence
    else if (arousal < midPoint && valence < midPoint) {
        return if (dominance < midPoint) {
            MappedEmotion("Sad", "Low energy and feeling unpleasant.")
        } else {
            MappedEmotion("Bored", "Low energy and lacking stimulation.")
        }
    }
    // Bottom-Right Quadrant: Low Arousal, High Valence
    else if (arousal < midPoint && valence >= midPoint) {
        return if (dominance >= midPoint) {
            MappedEmotion("Content", "Calm, pleasant, and in control.")
        } else {
            MappedEmotion("Relaxed", "Low energy and feeling pleasant and at ease.")
        }
    }

    // Default fallback
    return MappedEmotion("Neutral", "Emotion is not strongly expressed in any direction.")
}
