package com.example.myapplication

import kotlin.math.pow

// Data classes defining the input and output for the mapping function.
data class EmotionVector(val arousal: Float, val valence: Float, val dominance: Float)
data class MappedEmotion(val label: String, val description: String)

/**
 * Maps a 3D emotion vector (Arousal, Valence, Dominance) to a specific MappedEmotion.
 *
 * REVISION V41: SURGICAL TUNING OF THE GRAPH MODEL.
 * This version makes two precise adjustments to the successful V40 model to fix the
 * final remaining issues. It raises the Anger/Disgust arousal boundary and lowers
 * the Surprise arousal threshold to match user performance.
 */
fun mapVectorToEmotion(vector: EmotionVector): MappedEmotion {
    val (arousal, valence, dominance) = vector

    // --- PRIORITY 1: THE CLEAREST SEPARATORS ON THE GRAPH ---

    // A) Is it Positive? If so, it can only be Joy.
    if (valence > 0.65f) {
        return createMappedEmotion("Joy")
    }

    // B) Is it truly Neutral?
    // A "safe zone" around the center of the graph (0.5, 0.5, 0.5).
    if (arousal in 0.4f..0.6f && valence in 0.4f..0.6f && dominance in 0.4f..0.6f) {
        return createMappedEmotion("Neutral")
    }

    // C) Is it Surprise?
    // THE FIX: Lowered the arousal threshold to be less strict and catch more gasps.
    if (arousal > 0.7f && valence in 0.3f..0.7f) { // Lowered from 0.75f
        return createMappedEmotion("Surprise")
    }

    // --- PRIORITY 2: THE NEGATIVE EMOTIONS (Valence < 0.6) ---
    // The next clearest split on the graph is Dominance.

    // A) Is it a "POWERFUL" Negative Emotion? (High-Dominance Zone)
    if (dominance > 0.55f) {
        // THE FIX: Raised the arousal boundary to give Disgust more room.
        // Anger now requires more energy, preventing Disgust from being misclassified.
        if (arousal > 0.65f) { // Raised from 0.6f
            return createMappedEmotion("Anger") // High energy, high power
        } else {
            return createMappedEmotion("Disgust") // Lower energy, high power
        }
    }
    // B) Is it a "POWERLESS" Negative Emotion? (Low-Dominance Zone)
    else { // dominance <= 0.55f
        // Fear has higher arousal than Sadness.
        if (arousal > 0.45f) {
            return createMappedEmotion("Fear") // High energy, low power (frantic)
        } else {
            return createMappedEmotion("Sadness") // Low energy, low power (dejected)
        }
    }
}

/**
 * A helper function to create the MappedEmotion object with its description.
 */
private fun createMappedEmotion(label: String): MappedEmotion {
    val description = when (label) {
        "Joy" -> "A positive emotional state."
        "Anger" -> "A powerful and energetic negative emotion."
        "Sadness" -> "A powerless and low-energy negative emotion. The 'given-up' state."
        "Fear" -> "A powerless but energetic negative emotion. The 'frantic' state."
        "Disgust" -> "A powerful but lower-energy negative emotion, like annoyance or scorn."
        "Surprise" -> "A high-energy, neutral event, like a gasp or exclamation."
        "Neutral" -> "No single, strong emotion is being expressed."
        else -> "An unclassified emotional state."
    }
    return MappedEmotion(label, description)
}

