package com.example.myapplication

import android.util.Log
import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.math.sqrt

data class EmotionVector(
    val arousal: Float,
    val valence: Float,
    val dominance: Float
) {
    val data: FloatArray = floatArrayOf(arousal, dominance, valence)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EmotionVector
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        return data.contentHashCode()
    }
}

data class MappedEmotion(
    val label: String,
    val description: String,
    val color: Color
)

val emotionSynonyms = mapOf(
    "Joy" to listOf("Happy", "Elated", "Ecstatic", "Relieved", "Excited", "Delighted", "Astonished", "Surprised", "Cheerful", "Grateful", "Triumphant", "Hopeful", "Energetic", "Pleased", "Content", "Optimistic", "Amused", "Humorous", "Inspired", "Powerful", "Brave", "Confident", "Love"),
    "Anger" to listOf("Furious", "Hurt", "Irritated", "Annoyed", "Frustrated", "Enraged", "Shocked", "Disgusted", "Afraid", "Insistent", "Stressed", "Critical", "Offended", "Hostile"),
    "Sadness" to listOf("Upset", "Disappointed", "Depressed", "Gloomy", "Mournful", "Bored", "Quiet", "Miserable", "Guilty", "Drained", "Hurt", "Hopeless", "Helpless"),
    "Fear" to listOf("Worried", "Anxious", "Scared", "Terrified", "Uneasy", "Nervous", "Desperate", "Shocked", "Helpless", "Miserable", "Hopeless", "Hurt", "Surprised", "Tense", "Stressed"),
    "Disgust" to listOf("Grossed Out", "Bored", "Unamused", "Dissatisfied", "Shocked", "Mortified", "Disappointed", "Repulsed", "Irritated", "Annoyed", "Contempt", "Offended", "Critical", "Mean", "Humorous", "Confused"),
    "Surprise" to listOf("Shocked", "Startled", "Disgusted", "Happy", "Ecstatic", "Delighted", "Amazed", "Astonished", "Enraged", "Stressed", "Elated", "Energetic", "Alarmed", "Mortified"),
    "Neutral" to listOf("Calm", "Worried", "Concerned", "Inquisitive", "Indifferent", "Serene", "Contemplative", "Relieved", "Relaxed", "Safe", "Reflective", "Grateful", "Balanced", "Confused", "Pleased", "Intelligent", "Engaged", "Apathetic", "Bored", "Sleepy", "Unamused" )
)

fun mapVectorToEmotion(vector: EmotionVector): MappedEmotion {
    val emotionLabel = mapEmotionWithDecisionWeb(vector)
    return createMappedEmotion(emotionLabel)
}

internal fun mapEmotionWithDecisionWeb(vector: EmotionVector): String {
    val (arousal, valence, dominance) = vector

    return when {
        // High Valence (> 0.53): Shrinking Neutral by lowering the entry gate for positive emotions
        valence > 0.53f -> {
            when {
                arousal > 0.55f && dominance > 0.55f -> {
                    when {
                        (arousal > 0.75f && valence > 0.80f && dominance > 0.70f) ||
                                (arousal in 0.65f..0.75f && valence > 0.70f && dominance in 0.60f..0.75f) ||
                                (arousal in 0.55f..0.65f && valence > 0.65f) -> "Joy"
                        valence in 0.39f..0.57f && arousal > 0.67f -> "Surprise"
                        else -> "Joy"
                    }
                }
                arousal > 0.68f -> "Surprise"
                else -> "Neutral"
            }
        }

        // Low/Mid Valence (< 0.53): Negative and Neutral branch
        valence < 0.53f -> {
            if (arousal > 0.65f && valence in 0.39f..0.43f) return "Surprise"

            when {
                // High Arousal Negative
                arousal > 0.60f -> {
                    if (dominance > 0.60f) {
                        when {
                            (arousal > 0.68f && valence < 0.30f && dominance > 0.69f) ||
                                    (arousal in 0.60f..0.75f && valence < 0.30f && dominance > 0.65f) ||
                                    (valence < 0.12f) -> "Anger"
                            arousal in 0.60f..0.69f && valence in 0.04f..0.44f && dominance in 0.60f..0.69f -> "Disgust"
                            else -> "Disgust"
                        }
                    } else {
                        when {
                            (arousal > 0.63f && valence < 0.38f && dominance > 0.50f) ||
                                    (arousal > 0.70f && valence < 0.30f) ||
                                    (arousal in 0.60f..0.70f && valence < 0.35f) -> "Fear"
                            else -> "Fear"
                        }
                    }
                }
                // Low Arousal branch: Expanded to catch Sadness at V:0.49 A:0.23 D:0.47
                arousal < 0.48f -> {
                    when {
                        // Expanded cubby for Sadness: includes valence up to 0.51 and dominance up to 0.52
                        (arousal < 0.35f && valence < 0.51f && dominance < 0.52f) ||
                                (valence < 0.47f && dominance < 0.52f) -> "Sadness"
                        else -> "Neutral"
                    }
                }
                else -> {
                    when {
                        dominance < 0.58f && valence < 0.33f -> "Fear"
                        dominance > 0.58f && valence < 0.30f -> "Disgust"
                        else -> "Neutral"
                    }
                }
            }
        }

        // Catch-all Neutral zone: Shrink the range to force transitions to other emotions
        else -> {
            if (arousal > 0.72f && valence in 0.41f..0.60f) return "Surprise"

            when {
                arousal > 0.65f -> {
                    when {
                        dominance in 0.50f..0.67f -> "Surprise"
                        dominance > 0.65f -> "Neutral"
                        else -> "Fear"
                    }
                }
                // Shrunk the Neutral core box
                arousal in 0.47f..0.58f && dominance in 0.48f..0.60f -> "Neutral"
                else -> findClosestEmotion(vector)
            }
        }
    }
}

private fun distance(v1: EmotionVector, v2: EmotionVector): Float {
    return sqrt((v1.valence - v2.valence).pow(2) + (v1.arousal - v2.arousal).pow(2) + (v1.dominance - v2.dominance).pow(2))
}

private fun findClosestEmotion(vector: EmotionVector): String {
    val emotionCenters = mapOf(
        "Neutral" to EmotionVector(arousal = 0.54f, valence = 0.52f, dominance = 0.55f),
        "Joy" to EmotionVector(arousal = 0.7f, valence = 0.78f, dominance = 0.65f),
        "Anger" to EmotionVector(arousal = 0.69f, valence = 0.15f, dominance = 0.69f),
        "Sadness" to EmotionVector(arousal = 0.35f, valence = 0.25f, dominance = 0.35f),
        "Fear" to EmotionVector(arousal = 0.54f, valence = 0.39f, dominance = 0.49f),
        "Disgust" to EmotionVector(arousal = 0.57f, valence = 0.32f, dominance = 0.61f),
        "Surprise" to EmotionVector(arousal = 0.68f, valence = 0.59f, dominance = 0.66f)
    )

    val closestEntry = emotionCenters.minByOrNull { distance(vector, it.value) } ?: return "Neutral"
    val minDistance = distance(vector, closestEntry.value)

    // Increased threshold to 0.45 to favor specific emotions over Neutral fallback
    if (minDistance > 0.45f) return "Neutral"

    return when (closestEntry.key) {
        "Anger", "Joy", "Surprise" -> if (vector.arousal < 0.50f || vector.dominance < 0.50f) "Neutral" else closestEntry.key
        "Sadness" -> if (vector.arousal > 0.60f || vector.dominance > 0.60f) "Fear" else "Sadness"
        else -> closestEntry.key
    }
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
