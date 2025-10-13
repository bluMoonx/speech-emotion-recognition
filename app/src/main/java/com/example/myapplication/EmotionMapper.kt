// C:/Users/moonw/AndroidStudioProjects/MyApplication/app/src/main/java/com/example/myapplication/EmotionMapper.kt

package com.example.myapplication

/**
 * Maps a 3D emotion vector (Arousal, Valence, Dominance) to a specific MappedEmotion.
 * FINAL-FINAL REVISION: This version uses a single, structured `when` block to eliminate
 * logical fall-through errors and create more precise emotion "pockets".
 */
fun mapVectorToEmotion(vector: EmotionVector): MappedEmotion {
    val (arousal, valence, dominance) = vector

    return when {
        // --- HIGHEST PRIORITY ZONES (CLEAR SIGNALS) ---

        // Ecstatic/Triumphant: Very high energy, very positive.
        arousal > 0.7 && valence > 0.7 -> {
            if (dominance > 0.7) MappedEmotion("Triumphant", "Feeling pride and joy at a great victory. (e.g., 'I DID IT!')")
            else MappedEmotion("Ecstatic", "Overwhelming happiness or joyful excitement. (e.g., 'YUM SO GOOD!')")
        }
        // Infuriated/Panicked: Very high energy, very negative.
        arousal > 0.7 && valence < 0.3 -> {
            if (dominance > 0.7) MappedEmotion("Infuriated", "Extremely angry and impatient. (e.g., 'WHAT THE HELL?!')")
            else MappedEmotion("Panicked", "Sudden uncontrollable fear or anxiety.")
        }
        // Serene/Calm: Very low energy, very positive.
        arousal < 0.25 && valence > 0.7 -> {
            if (dominance < 0.5) MappedEmotion("Serene", "Peaceful and untroubled. (e.g., 'I am zen...')")
            else MappedEmotion("Calm", "Relaxed and free from strong emotion.")
        }
        // Miserable/Sad: Very low energy, very negative.
        arousal < 0.25 && valence < 0.25 -> {
            if (dominance < 0.4) MappedEmotion("Miserable", "Wretchedly unhappy. (e.g., 'Life is miserable...')")
            else MappedEmotion("Sad", "Feeling or showing sorrow.")
        }

        // --- HIGH ENERGY ZONES (SECONDARY PRIORITY) ---

        // Astonished/Excited: High energy, positive.
        arousal > 0.6 && valence > 0.5 -> {
            if (dominance < 0.5) MappedEmotion("Astonished", "Greatly surprised or impressed. (e.g., 'WOWWW!')")
            else MappedEmotion("Excited", "Very enthusiastic and eager.")
        }
        // Angry/Disgusted: High energy, negative.
        arousal > 0.6 && valence < 0.45 -> {
            if (dominance < 0.5) MappedEmotion("Disgusted", "A feeling of revulsion or profound disapproval. (e.g., 'Ew wtf?!')")
            else MappedEmotion("Angry", "A strong feeling of annoyance or displeasure.")
        }

        // --- LOW ENERGY ZONES (SECONDARY PRIORITY) ---

        // Contented/Relieved: Low energy, positive.
        arousal < 0.35 && valence > 0.6 -> {
            if (dominance > 0.6) MappedEmotion("Relieved", "Feeling reassured following anxiety.")
            else MappedEmotion("Contented", "Quietly happy and at ease.")
        }
        // Dejected/Bored/Tired: Low energy, negative.
        arousal < 0.4 && valence < 0.4 -> {
            if (valence < 0.2) MappedEmotion("Dejected", "Sad and depressed; dispirited.")
            else if (arousal < 0.2) MappedEmotion("Bored", "Feeling weary and unoccupied.")
            else MappedEmotion("Tired", "In need of rest. (e.g., 'let me rest...')")
        }

        // --- MID-GROUND / DEFAULT ZONES ---

        // Happy/Amused: Mid energy, positive.
        valence > 0.6 -> {
            if (dominance > 0.6) MappedEmotion("Happy", "Feeling or showing pleasure or contentment.")
            else MappedEmotion("Amused", "Finding something funny or entertaining. (e.g., 'This is easy *smirk*')")
        }
        // Contempt/Annoyed: Mid energy, negative.
        valence < 0.4 -> {
            if (dominance > 0.6) MappedEmotion("Contempt", "The feeling that something is worthless. (e.g., 'Ugh, are you serious??')")
            else MappedEmotion("Annoyed", "Slightly angry or irritated. (e.g., 'This is so bad...')")
        }
        // Disappointed/Concerned: Mid energy, slightly negative.
        valence < 0.55 -> {
            if (dominance < 0.5) MappedEmotion("Concerned", "Worried or troubled.")
            else MappedEmotion("Disappointed", "Sad because one's hopes were not fulfilled.")
        }

        // If nothing else matches, it's Neutral.
        else -> MappedEmotion("Neutral", "No dominant emotion detected.")
    }
}
