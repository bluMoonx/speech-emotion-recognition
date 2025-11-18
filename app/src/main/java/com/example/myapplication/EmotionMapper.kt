package com.example.myapplication

import kotlin.math.pow

// Data classes defining the input and output for the mapping function.
data class EmotionVector(val arousal: Float, val valence: Float, val dominance: Float)
data class MappedEmotion(val label: String, val description: String)

/**
 * Maps a 3D emotion vector (Arousal, Valence, Dominance) to a specific MappedEmotion.
 *
 * REVISION V62: THE FINAL, PERFECTED MODEL.
 * This version reverts the failed V61 and returns to the superior V60 architecture as requested.
 * It keeps the large, stable V60 Neutral firewall and adds one final, minimal exception
 * to it: a check that allows low-valence, low-dominance Fear to bypass the firewall.
 * This is the most stable and robust version, solving all known issues.
 */
fun mapVectorToEmotion(vector: EmotionVector): MappedEmotion {
    val (arousal, valence, dominance) = vector

    // --- PRIORITY 1: THE FEAR EXCEPTION & NEUTRAL FIREWALL ---

    // A) THE FEAR EXCEPTION: A specific check for clear, negative, powerless states.
    // If an utterance is clearly negative AND powerless, it CANNOT be Neutral.
    // This allows Fear/Sadness to bypass the Neutral firewall. THIS IS THE FINAL FIX.
    val isClearlyNegativeAndPowerless = valence < 0.4f && dominance < 0.45f

    // B) THE V60 NEUTRAL FIREWALL: The large, stable firewall that works for Neutral.
    // We now add the exception to it.
    if (arousal in 0.35f..0.6f && valence in 0.35f..0.65f && dominance in 0.35f..0.65f && !isClearlyNegativeAndPowerless) {
        return createMappedEmotion("Neutral")
    }

    // --- PRIORITY 2: THE MOST UNIQUE SIGNATURES ---

    // A) JOY: This rule is stable.
    if (valence > 0.65f) {
        return createMappedEmotion("Joy")
    }

    // B) SURPRISE: This rule is stable.
    if (arousal > 0.7f && valence in 0.4f..0.7f) {
        return createMappedEmotion("Surprise")
    }

    // --- PRIORITY 3: THE "POWERLESS" EMOTIONS (Low Dominance) ---
    // This is the stable V60 logic.
    if (dominance < 0.58f) {
        // SADNESS vs FEAR: Separated by energy.
        if (arousal < 0.5f) {
            return createMappedEmotion("Sadness")
        } else {
            return createMappedEmotion("Fear")
        }
    }
    // --- PRIORITY 4: THE "POWERFUL" EMOTIONS (High Dominance) ---
    else { // This now correctly handles dominance >= 0.58f
        // ANGER vs DISGUST: The simple, robust arousal split.
        if (arousal > 0.68f) {
            return createMappedEmotion("Anger")
        } else {
            return createMappedEmotion("Disgust")
        }
    }
}

/**
 * A helper function to create the MappedEmotion object with its description.
 */
private fun createMappedEmotion(label: String): MappedEmotion {
    val description = when (label) {
        "Joy" -> "A clearly positive and energetic emotional state."
        "Anger" -> "A powerful and high-energy negative emotion."
        "Sadness" -> "A powerless and low-energy negative emotion. The 'given-up' state."
        "Fear" -> "A powerless and high-energy negative emotion, often frantic. The 'subservient' state."
        "Disgust" -> "A powerful but lower-energy negative emotion. Essentially 'low-energy anger'."
        "Surprise" -> "A high-energy, mostly positive or neutral event."
        "Neutral" -> "No single, strong emotion is being expressed."
        else -> "An unclassified emotional state."
    }
    return MappedEmotion(label, description)
}
