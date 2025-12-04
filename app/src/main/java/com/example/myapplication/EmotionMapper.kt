package com.example.myapplication

import android.util.Log
import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.math.sqrt

// Data classes to match MainActivity.kt
data class EmotionVector(
    val arousal: Float,
    val valence: Float,
    val dominance: Float
) {
    // Keep this for convenience if you use it elsewhere
    val data: FloatArray = floatArrayOf(arousal, dominance, valence)

    // --- ADD THIS OVERRIDE TO FIX THE CRASH ---
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EmotionVector

        // This checks if the CONTENTS of the arrays are the same,
        // which is what we want.
        if (!data.contentEquals(other.data)) return false

        return true
    }

    // --- ALSO ADD THIS. WHENEVER YOU OVERRIDE EQUALS, YOU MUST OVERRIDE HASHCODE ---
    override fun hashCode(): Int {
        return data.contentHashCode()
    }
}

data class MappedEmotion(
    val label: String,
    val description: String,
    val color: Color
)
/**
 * Maps a 3D emotion vector to a MappedEmotion using a nested "Decision Web" model.
 *
 * This version uses a hierarchical decision tree based on user testing feedback.
 * The logic prioritizes Valence, then Arousal, then Dominance to create more
 * nuanced and accurate classifications, especially for ambiguous states.
 *
 * Key Improvements based on testing:
 * - Expands the Neutral zone to be more forgiving.
 * - Raises the Arousal threshold for Surprise to prevent misclassifying energetic neutral speech.
 * - Adds a specific rule to catch low-energy Joy and map it to Neutral instead of Surprise.
 * - Uses Dominance as a final tie-breaker for high-arousal negative emotions (Anger vs. Fear).
 */
val emotionSynonyms = mapOf(
    "Joy" to listOf("Happy", "Elated", "Excited", "Cheerful", "Triumphant", "Pleased"),
    "Anger" to listOf("Furious", "Irritated", "Annoyed", "Frustrated", "Enraged"),
    "Sadness" to listOf("Depressed", "Gloomy", "Mournful", "Bored", "Disappointed"),
    "Fear" to listOf("Anxious", "Scared", "Terrified", "Uneasy", "Nervous", "Desperate"),
    "Disgust" to listOf("Grossed Out", "Repulsed", "Irritated", "Annoyed", "Contempt"),
    "Surprise" to listOf("Shocked", "Startled", "Amazed", "Astonished"),
    "Neutral" to listOf("Calm", "Inquisitive", "Indifferent", "Relaxed")
)
fun mapVectorToEmotion(vector: EmotionVector): MappedEmotion {
    val (arousal, valence, dominance) = vector
    Log.d("EmotionMapper", "INPUT  -> V: $valence, A: $arousal, D: $dominance")

    val emotionLabel = mapEmotionWithDecisionWeb(vector)

    val mappedEmotion = createMappedEmotion(emotionLabel)
    Log.d("EmotionMapper", "MAPPED -> [V:$valence, A:$arousal, D:$dominance] TO -> ${mappedEmotion.label}")
    return mappedEmotion
}
internal fun mapEmotionWithDecisionWeb(vector: EmotionVector): String {
    val (arousal, valence, dominance) = vector

    // --- Top Level Decision: VALENCE (Positive vs. Negative) ---
    return when {

        // =======================================================================================
        // --- BRANCH 1: HIGH VALENCE (> 0.58) -> Domain of Joy & Positive Surprise ---
        // =======================================================================================
        valence > 0.58f -> {
            // This part is working well, no changes needed
            when {
                arousal > 0.55f && dominance > 0.55f -> {
                    when {
                        arousal > 0.75f && valence > 0.80f && dominance > 0.70f -> "Joy"
                        arousal in 0.65f..0.75f && valence > 0.70f && dominance in 0.60f..0.70f -> "Joy"
                        arousal in 0.55f..0.65f && valence > 0.65f -> "Joy"
                        valence in 0.55f..0.65f && arousal > 0.67f -> "Surprise"
                        else -> "Joy"
                    }
                }
                arousal > 0.68f -> "Surprise"
                else -> {
                    if (arousal < 0.50f && valence > 0.60f) "Neutral"
                    else "Neutral"
                }
            }
        }

        // =======================================================================================
        // --- BRANCH 2: LOW VALENCE (< 0.45) -> Domain of Anger, Fear, Sadness, Disgust ---
        // =======================================================================================
        valence < 0.45f -> {
            // *** THE FINAL FIX: A TOP-LEVEL SURPRISE GATE ***
            // Your Surprise data shows Arousal is consistently high (>0.66) while Valence is ambiguous (0.3-0.42).
            // This gate will catch these events FIRST, before they can be misclassified as Disgust or Fear.
            if (arousal > 0.65f && valence in 0.32f..0.43f) {
                return "Surprise"
            }

            // If it's not a surprise, proceed with the existing refined logic.
            when {
                // --- Sub-Branch 2a: HIGH AROUSAL Negative (> 0.60) ---
                arousal > 0.60f -> {
                    when {
                        // --- Anger & Contemptuous Disgust Profile Cluster (High Dominance) ---
                        dominance > 0.60f -> {
                            when {
                                // Profile 1: Explosive Rage
                                arousal > 0.75f && valence < 0.20f && dominance > 0.70f -> "Anger"

                                // Profile 2: Contemptuous Disgust
                                arousal in 0.60f..0.75f && valence in 0.12f..0.31f && dominance > 0.62f -> "Disgust" // Tightened valence

                                // Profile 3: Frustrated Annoyance
                                arousal in 0.60f..0.75f && valence < 0.30f && dominance > 0.65f -> "Anger"

                                // Fallback: If it's this dominant and valence is rock-bottom, it's Anger.
                                else -> if (valence < 0.12f) "Anger" else "Disgust"
                            }
                        }

                        // --- FEAR EXPANSION & DISGUST REDUCTION ---
                        dominance < 0.60f -> {
                            when {
                                // This rule is now safer because the surprise gate above has already run.
                                arousal > 0.63f && valence < 0.38f && dominance > 0.50f -> "Fear"

                                // Kept other Fear profiles
                                arousal > 0.70f && valence < 0.30f -> "Fear"
                                arousal in 0.60f..0.70f && valence < 0.35f -> "Fear"

                                else -> "Fear"
                            }
                        }

                        // Fallback for this branch
                        else -> "Disgust"
                    }
                }

                // --- Sub-Branch 2b: LOW AROUSAL Negative (< 0.45) -> Sadness ---
                arousal < 0.45f -> {
                    when {
                        arousal < 0.35f && valence < 0.29f && dominance < 0.42f -> "Sadness"
                        valence < 0.40f && dominance < 0.50f -> "Sadness"
                        else -> "Neutral"
                    }
                }

                // --- Sub-Branch 2c: MID AROUSAL Negative -> Disgust/Fear Fallback ---
                else -> { // Arousal is between 0.45f and 0.60f
                    when {
                        // Nudged Fear to be slightly more negative
                        dominance < 0.58f && valence < 0.33f -> "Fear"

                        // Disgust now only triggers if dominance is higher in this arousal range.
                        dominance > 0.58f && valence < 0.30f -> "Disgust"

                        else -> "Neutral"
                    }
                }
            }
        }

        // =======================================================================================
        // --- BRANCH 3: MID VALENCE (0.45 - 0.58) -> The True Neutral & Ambiguous Zone ---
        // =======================================================================================
        else -> {
            // Your data `V: 0.5218, A: 0.7748` was misclassified as Neutral.
            // Let's add a specific rule for high-arousal events in the neutral zone.
            if (arousal > 0.72f && valence < 0.55f) {
                return "Surprise"
            }

            when {
                arousal > 0.70f -> {
                    when {
                        dominance in 0.50f..0.67f -> "Surprise"
                        dominance > 0.65f -> "Neutral"
                        else -> "Fear"
                    }
                }
                arousal in 0.40f..0.65f && dominance in 0.45f..0.65f -> "Neutral"
                arousal < 0.40f -> {
                    when {
                        dominance < 0.50f -> "Neutral"
                        else -> "Neutral"
                    }
                }
                else -> "Neutral"
            }
        }
    }
}

private fun distance(v1: EmotionVector, v2: EmotionVector): Float {
    return sqrt(
        (v1.valence - v2.valence).pow(2) +
                (v1.arousal - v2.arousal).pow(2) +
                (v1.dominance - v2.dominance).pow(2)
    )
}

/**
 * If no specific rule is matched, this function finds the emotion profile that is
 * mathematically closest and meets profile constraints.
 */
private fun findClosestEmotion(vector: EmotionVector): String {
    // Center points for each emotion archetype
    val emotionCenters = mapOf(
        "Neutral" to EmotionVector(arousal = 0.54f, valence = 0.52f, dominance = 0.55f),
        "Joy" to EmotionVector(arousal = 0.7f, valence = 0.78f, dominance = 0.65f),
        "Anger" to EmotionVector(arousal = 0.75f, valence = 0.15f, dominance = 0.76f),
        "Sadness" to EmotionVector(arousal = 0.35f, valence = 0.25f, dominance = 0.35f),
        "Fear" to EmotionVector(arousal = 0.54f, valence = 0.39f, dominance = 0.49f),
        "Disgust" to EmotionVector(arousal = 0.57f, valence = 0.17f, dominance = 0.63f),
        "Surprise" to EmotionVector(arousal = 0.68f, valence = 0.59f, dominance = 0.66f)
    )

    // 1. Find the closest emotion center and its distance
    val closestEmotionEntry = emotionCenters.minByOrNull { (_, centerVector) ->
        distance(vector, centerVector)
    }

    val closestLabel = closestEmotionEntry?.key ?: return "Neutral" // Safe default
    val minDistance = distance(vector, closestEmotionEntry.value)

    // 2. Distance Threshold
    val MAX_DISTANCE_THRESHOLD = 0.30f // Slightly more forgiving
    if (minDistance > MAX_DISTANCE_THRESHOLD) {
        return "Neutral"
    }

    // 3. Profile Constraints
    when (closestLabel) {
        "Anger", "Joy", "Surprise" -> {
            if (vector.arousal < 0.50f || vector.dominance < 0.50f) {
                return "Neutral" // Reject remapping to high-energy
            }
        }
        "Sadness" -> {
            // A more logical check: if energy is high, it can't be sadness.
            if (vector.arousal > 0.60f || vector.dominance > 0.60f) {
                return "Fear"
            }
        }
    }
    return closestLabel
}

fun createMappedEmotion(label: String): MappedEmotion {
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
        "Joy" -> Color(0xFFD4AF37)
        "Surprise" -> Color(0xFF40E0D0)
        "Anger" -> Color(0xFFB22222)
        "Disgust" -> Color(0xFF556B2F)
        "Sadness" -> Color(0xFF191970)
        "Fear" -> Color(0xFF4B0082)
        "Neutral" -> Color.Gray
        else -> Color.DarkGray
    }
    return MappedEmotion(label, description, color)
}