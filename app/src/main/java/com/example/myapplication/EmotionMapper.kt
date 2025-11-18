package com.example.myapplication

import android.util.Log
import androidx.compose.ui.graphics.Color

// Data classes must match the definitions in MainActivity.kt
data class EmotionVector(val arousal: Float, val valence: Float, val dominance: Float)
data class MappedEmotion(val label: String, val description: String, val color: Color)

/**
 * Maps a 3D emotion vector to a MappedEmotion using the "Strict Cubby" model.
 *
 * FINAL REVISION 10.2: Closing the Gaps to Reduce 'Unclassified'
 * This version relaxes the hyper-specific constraints and adds logical fallback cubbies.
 * This ensures that fewer emotional states fall through the cracks and get marked as
 * 'Unclassified', providing a more robust classification.
 */
fun mapVectorToEmotion(vector: EmotionVector): MappedEmotion {
    val (arousal, valence, dominance) = vector

    Log.d("EmotionMapper", "INPUT  -> A: $arousal, V: $valence, D: $dominance")

    val emotionLabel = when {
        // --- PRIORITY 1: THE STRICT NEUTRAL CUBBY (0.40 - 0.60). THIS IS THE ONLY WAY TO BE NEUTRAL. ---
        valence in 0.4f..0.6f && arousal in 0.4f..0.6f && dominance in 0.4f..0.6f -> "Neutral"

        // --- PRIMARY EMOTIONAL CUBBIES (Specific and Prioritized) ---

        // Peak Anger
        valence <= 0.32f && arousal > 0.67f && dominance > 0.67f -> "Anger"

        // Peak Joy
        valence > 0.6f && arousal > 0.6f && dominance > 0.6f -> "Joy"

        // Sadness is the primary low-energy, low-power state.
        valence < 0.5f && arousal < 0.5f && dominance < 0.5f -> "Sadness"

        // --- FIX: Fear rule is relaxed to be broader and more effective. ---
        // It's a high-arousal state that isn't highly controlled.
        valence < 0.48f && arousal > 0.5f && dominance < 0.6f -> "Fear"

        // Surprise is a sharp, very high-arousal reaction.
        valence in 0.4f..0.65f && arousal > 0.65f && dominance > 0.55f -> "Surprise"

        // Disgust is a specific mid-arousal, mid-power, negative state.
        valence < 0.4f && arousal in 0.5f..0.6f && dominance in 0.5f..0.6f -> "Disgust"


        // --- LOGICAL FALLBACK CUBBIES TO PREVENT 'UNCLASSIFIED' ---

        // Fallback for general high-energy negative states -> treat as Fear
        valence < 0.4f && arousal > 0.6f -> "Fear"

        // Fallback for general low-energy negative states -> treat as Disgust
        valence < 0.4f -> "Disgust"

        // Fallback for general high-energy positive states -> treat as Surprise
        arousal > 0.65f -> "Surprise"


        // --- FINAL CATCH. Only truly ambiguous states end up here. ---
        else -> "Unclassified"
    }

    val mappedEmotion = createMappedEmotion(emotionLabel)
    Log.d("EmotionMapper", "MAPPED -> [D:$dominance, A:$arousal, V:$valence] TO -> ${mappedEmotion.label}")
    return mappedEmotion
}

/**
 * Helper function to create the MappedEmotion object with its description and color.
 */
private fun createMappedEmotion(label: String): MappedEmotion {
    val description = when (label) {
        "Joy" -> "A positive and controlled emotional state."
        "Anger" -> "A powerful, aggressive, and high-energy negative state."
        "Sadness" -> "A state of low energy, low positivity, and low control."
        "Fear" -> "A negative state defined by a lack of control."
        "Disgust" -> "A controlled negative reaction to something unpleasant."
        "Surprise" -> "A sharp, sudden reaction of moderate-to-high energy and control."
        "Neutral" -> "A balanced emotional state with no single strong emotion."
        "Unclassified" -> "An ambiguous emotional state that does not fit a clear profile."
        else -> "An unknown state."
    }

    val color = when (label) {
        "Joy" -> Color(0xFFD4AF37)      // Gold
        "Surprise" -> Color(0xFF40E0D0)  // Turquoise
        "Anger" -> Color(0xFFB22222)     // Firebrick Red
        "Disgust" -> Color(0xFF556B2F)   // Dark Olive Green
        "Sadness" -> Color(0xFF191970)   // Midnight Blue
        "Fear" -> Color(0xFF4B0082)      // Indigo
        "Neutral" -> Color.Gray
        "Unclassified" -> Color.LightGray // A new color for the unclassified state
        else -> Color.DarkGray
    }
    return MappedEmotion(label, description, color)
}
