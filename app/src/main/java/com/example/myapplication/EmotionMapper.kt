// In EmotionMapper.kt

package com.example.myapplication

data class MappedEmotion(val label: String, val description: String)

/**
 * Maps the model's output vector (Arousal, Dominance, Valence) to a human-readable emotion
 * using a 13-state model to catch more nuanced cases.
 */
fun mapVectorToEmotion(vector: EmotionVector): MappedEmotion {
    val arousal = vector.arousal
    val dominance = vector.dominance
    val valence = vector.valence

    // Define the boundaries. The 'midPoint' is the main separator,
    // while 'spread' creates a "neutral zone" for more nuanced emotions.
    val midPoint = 0.5f
    val spread = 0.15f // Creates a zone from 0.35 to 0.65

    // --- NEW: SPECIAL CASE FOR DESPERATION/PANIC ---
    // This state is characterized by very high arousal and very low valence/dominance.
    // It's a specific kind of fear/anxiety that deserves its own category.
    if (arousal > 0.75f && valence < 0.25f && dominance < 0.35f) {
        return MappedEmotion("Panicked", "A sudden, overwhelming fear or anxiety, often leading to frantic behavior.")
    }


    // Top-Right Quadrant: High Arousal, High Valence (Positive, High Energy)
    if (arousal >= midPoint && valence >= midPoint) {
        return when {
            // High Dominance -> Clearly in control and energetic
            dominance > midPoint + spread -> MappedEmotion("Triumphant", "Powerful, victorious, and full of positive energy.")
            // Low Dominance -> Energetic but less assertive
            dominance < midPoint - spread -> MappedEmotion("Playful", "Lighthearted, spontaneous, and joyfully energetic.")
            // Mid-range Dominance -> The core "Happy/Excited" state
            else -> MappedEmotion("Excited", "Full of positive energy, anticipation, and happiness.")
        }
    }
    // Top-Left Quadrant: High Arousal, Low Valence (Negative, High Energy)
    else if (arousal >= midPoint && valence < midPoint) {
        return when {
            // High Dominance -> Assertive and negative
            dominance > midPoint + spread -> MappedEmotion("Angry", "Feeling hostile, powerful, and ready to assert.")
            // Low Dominance -> Overwhelmed and negative
            dominance < midPoint - spread -> MappedEmotion("Fearful", "Feeling threatened, overwhelmed, and not in control.")
            // Mid-range Dominance -> A general state of high-energy distress
            else -> MappedEmotion("Anxious", "A state of unease, worry, nervousness, and high tension.")
        }
    }
    // Bottom-Left Quadrant: Low Arousal, Low Valence (Negative, Low Energy)
    else if (arousal < midPoint && valence < midPoint) {
        return when {
            // High Dominance -> A controlled, low-energy negativity
            dominance > midPoint + spread -> MappedEmotion("Bored", "Disinterested, underwhelmed, but not necessarily unhappy.")
            // Low Dominance -> A helpless, deep-seated low-energy negativity
            dominance < midPoint - spread -> MappedEmotion("Depressed", "A persistent feeling of deep sadness and helplessness.")
            // Mid-range Dominance -> The core "Sad" state
            else -> MappedEmotion("Sad", "A general feeling of unhappiness and sorrow, but not necessarily helpless.")
        }
    }
    // Bottom-Right Quadrant: Low Arousal, High Valence (Positive, Low Energy)
    else if (arousal < midPoint && valence >= midPoint) {
        return when {
            // High Dominance -> Calm and in control
            dominance > midPoint + spread -> MappedEmotion("Content", "Peacefully satisfied and feeling in control of the situation.")
            // Low Dominance -> Calm and submissive
            dominance < midPoint - spread -> MappedEmotion("Serene", "Deeply calm, tranquil, and at peace with the world.")
            // Mid-range Dominance -> A general state of pleasant calm
            else -> MappedEmotion("Relaxed", "Free from tension and anxiety; calm and at ease.")
        }
    }

    // Default fallback for values that sit exactly in the middle of all axes
    return MappedEmotion("Neutral", "Emotion is not strongly expressed in any direction.")
}

