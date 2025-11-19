package com.example.myapplication

import android.util.Log
import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.math.sqrt

// Data classes must match the definitions in MainActivity.kt
// CORRECT ORDER: arousal, valence, dominance
data class EmotionVector(val arousal: Float, val valence: Float, val dominance: Float)
data class MappedEmotion(val label: String, val description: String, val color: Color)

/**
 * Maps a 3D emotion vector to a MappedEmotion using the "Strict Cubby" model.
 *
 * FINAL REVISION 12.2: Profile-Aware Fallback
 * Implements user feedback to make the 'findClosestEmotion' fallback more intelligent.
 * 1. A distance threshold is added. If no emotion is "super close", it defaults to Sadness.
 * 2. Profile constraints are enforced. An input can only map to a high-energy emotion like Anger/Joy
 *    if its own arousal and dominance are also reasonably high, preventing incorrect remapping.
 */
fun mapVectorToEmotion(vector: EmotionVector): MappedEmotion {
    // ... (The top part of the function remains identical)
    val (arousal, valence, dominance) = vector

    Log.d("EmotionMapper", "INPUT  -> A: $arousal, V: $valence, D: $dominance")

    val emotionLabel = when {
        // --- PRIORITY 1: THE STRICT NEUTRAL CUBBY. THIS IS THE ONLY WAY TO BE NEUTRAL. ---
        valence in 0.47f..0.6f && arousal in 0.47f..0.65f && dominance in 0.47f..0.68f -> "Neutral"

        // --- PRIMARY EMOTIONAL CUBBIES (Specific and Prioritized) ---
        valence <= 0.43f && arousal > 0.65f && dominance > 0.67f -> "Anger"
        valence > 0.67f && arousal > 0.55f && dominance > 0.55f -> "Joy"
        valence < 0.45f && arousal < 0.52f && dominance < 0.54f -> "Sadness"
        valence <= 0.42f && arousal in 0.5f..0.65f && dominance in 0.57f..0.69f -> "Disgust"
        valence <= 0.51f && arousal in 0.42f..0.65f && dominance in 0.32f..0.49f -> "Fear"
        valence in 0.40f..0.66f && arousal > 0.71f && dominance > 0.67f -> "Surprise"

        // --- LOGICAL FALLBACKS ---
        arousal > 0.6f && valence < 0.51f && dominance < 0.65f -> "Fear"
        valence < 0.40f && dominance > 0.63f && arousal in 0.5f..0.7f -> "Disgust"
        valence > 0.6f -> "Joy"

        // --- FINAL, INTELLIGENT FALLBACK: Find the closest emotion profile ---
        else -> findClosestEmotion(vector)
    }

    val mappedEmotion = createMappedEmotion(emotionLabel)
    Log.d("EmotionMapper", "MAPPED -> [D:$dominance, A:$arousal, V:$valence] TO -> ${mappedEmotion.label}")
    return mappedEmotion
}

/**
 * Calculates the Euclidean distance between two 3D vectors.
 */
private fun distance(v1: EmotionVector, v2: EmotionVector): Float {
    return sqrt(
        (v1.valence - v2.valence).pow(2) +
                (v1.arousal - v2.arousal).pow(2) +
                (v1.dominance - v2.dominance).pow(2)
    )
}

/**
 * If no specific cubby is matched, this function finds the emotion profile that is
 * mathematically closest and meets profile constraints.
 */
private fun findClosestEmotion(vector: EmotionVector): String {
    // --- FIX: Define "center points" in the CORRECT order: arousal, valence, dominance ---
    val emotionCenters = mapOf(
        "Joy" to EmotionVector(arousal = 0.7f, valence = 0.75f, dominance = 0.65f),
        "Anger" to EmotionVector(arousal = 0.75f, valence = 0.15f, dominance = 0.75f),
        "Sadness" to EmotionVector(arousal = 0.35f, valence = 0.25f, dominance = 0.35f),
        "Fear" to EmotionVector(arousal = 0.55f, valence = 0.35f, dominance = 0.4f),
        "Disgust" to EmotionVector(arousal = 0.55f, valence = 0.35f, dominance = 0.6f),
        "Surprise" to EmotionVector(arousal = 0.75f, valence = 0.5f, dominance = 0.7f)
    )

    // 1. Find the closest emotion center and its distance
    val closestEmotionEntry = emotionCenters.minByOrNull { (_, centerVector) ->
        distance(vector, centerVector)
    }

    // A safe default if the map is ever empty
    if (closestEmotionEntry == null) return "Sadness"

    val closestLabel = closestEmotionEntry.key
    val minDistance = distance(vector, closestEmotionEntry.value)

    // 2. REQUIREMENT: Check if it's "SUPER close". If not, default to a safe emotion.
    val MAX_DISTANCE_THRESHOLD = 0.35f // Tweak this value to define "close"
    if (minDistance > MAX_DISTANCE_THRESHOLD) {
        return "Sadness" // Input is not close to any known archetype.
    }

    // 3. REQUIREMENT: Enforce profile constraints for high/low energy emotions.
    when (closestLabel) {
        "Anger", "Joy", "Surprise" -> {
            // To be a high-energy emotion, BOTH arousal and dominance must be reasonably high.
            if (vector.arousal < 0.5f || vector.dominance < 0.5f) {
                return "Sadness" // Reject remapping to high-energy; default to low-energy.
            }
        }
        "Sadness" -> {
            // To be a low-energy emotion, BOTH arousal and dominance must be reasonably low.
            if (vector.arousal > 0.6f || vector.dominance > 0.6f) {
                return "Fear" // Reject remapping to Sadness; default to a high-energy negative.
            }
        }
    }

    // If all checks pass, return the closest emotion.
    return closestLabel
}

/**
 * Helper function to create the MappedEmotion object with its description and color.
 */
private fun createMappedEmotion(label: String): MappedEmotion {
    // ... (This function remains identical and is correct)
    val description = when (label) {
        "Joy" -> "A positive and controlled emotional state."
        "Anger" -> "A powerful, aggressive, and high-energy negative state."
        "Sadness" -> "A state of low energy, low positivity, and low control."
        "Fear" -> "A negative state defined by a lack of control."
        "Disgust" -> "A controlled negative reaction to something unpleasant."
        "Surprise" -> "A sharp, sudden reaction of moderate-to-high energy and control."
        "Neutral" -> "A balanced emotional state with no single strong emotion."
        else -> "An emotional state was detected."
    }

    val color = when (label) {
        "Joy" -> Color(0xFFD4AF37)      // Gold
        "Surprise" -> Color(0xFF40E0D0)  // Turquoise
        "Anger" -> Color(0xFFB22222)     // Firebrick Red
        "Disgust" -> Color(0xFF556B2F)   // Dark Olive Green
        "Sadness" -> Color(0xFF191970)   // Midnight Blue
        "Fear" -> Color(0xFF4B0082)      // Indigo
        "Neutral" -> Color.Gray
        else -> Color.DarkGray
    }
    return MappedEmotion(label, description, color)
}
