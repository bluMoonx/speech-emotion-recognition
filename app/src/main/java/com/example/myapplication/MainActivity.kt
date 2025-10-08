package com.example.myapplication

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.myapplication.ui.theme.MyApplicationTheme
import androidx.compose.foundation.layout.*


private const val TAG = "ULTRA_DEBUG"
private const val TARGET_SAMPLE_RATE = 16000

// State definitions remain the same
sealed interface AppState {
    object Idle : AppState
    data class Loading(val fileName: String) : AppState // Renamed for clarity
    data class Success(val fileName: String, val fileUri: Uri) : AppState
    data class Detecting(val fileName: String) : AppState
    data class DetectionSuccess(val fileName: String, val result: EmotionVector) : AppState
    data class Failure(val message: String) : AppState
}

data class EmotionVector(val arousal: Float, val valence: Float, val dominance: Float)

class MainActivity : ComponentActivity() {
    private val emotionPredictor by lazy { EmotionPredictor(this) }
    private lateinit var pickAudioLauncher: ActivityResultLauncher<Intent>

// In MainActivity.kt, ADD this function inside the MainActivity class

    private suspend fun detectEmotion(
        uri: Uri,
        onStart: () -> Unit,
        onSuccess: (EmotionVector) -> Unit,
        onFailure: (String) -> Unit
    ) {
        withContext(Dispatchers.Main) { onStart() }

        try {
            // Your existing, correct logic for file processing
            val inputStream = contentResolver.openInputStream(uri)
                ?: throw Exception("Failed to open input stream for URI.")

            val headerInfo = parseWavHeader(inputStream)
                ?: throw Exception("Failed to parse WAV header.")
            val (sampleRate, numChannels, bitsPerSample) = headerInfo

            // --- REPLACE WITH THIS BLOCK ---
            val bytesToRead = 15 * sampleRate * (bitsPerSample / 8) * numChannels
            val audioBytes = ByteArray(bytesToRead)
            val bytesRead = inputStream.read(audioBytes)
            val finalAudioBytes = audioBytes.copyOf(bytesRead)

// Convert the raw audio bytes to a ShortArray based on the bit depth
            val shortArray = when (bitsPerSample) {
                16 -> pcm16BitByteArrayToShortArray(finalAudioBytes)
                    ?: throw Exception("Failed to convert 16-bit audio.")
                32 -> {
                    // Here we need to check if the 32-bit file is Float or Integer.
                    // The WAV header parsing needs to be updated for this, but for now,
                    // we can try to guess or assume float, which is more common.
                    // A simple heuristic: check if the first value is small (likely a float).
                    val isFloat = true // For now, let's assume float.
                    if (isFloat) {
                        pcm32BitFloatByteArrayToShortArray(finalAudioBytes)
                    } else {
                        pcm32BitIntByteArrayToShortArray(finalAudioBytes)
                    }
                }
                // You could add a case for 24-bit audio here if needed
                // 24 -> pcm24BitByteArrayToShortArray(finalAudioBytes)
                else -> throw Exception("Unsupported bit depth: $bitsPerSample-bit. Only 16-bit and 32-bit are supported.")
            }

            if (shortArray.isEmpty()) {
                throw Exception("Audio conversion resulted in an empty array.")
            }
// --- END OF REPLACEMENT BLOCK ---


            val processedAudio =
                preprocessAudioForModel(shortArray, sampleRate, TARGET_SAMPLE_RATE, numChannels)
                    ?: throw Exception("Audio preprocessing returned null.")

            val prediction = emotionPredictor.predict(processedAudio)
                ?: throw Exception("ONNX model failed to return a result.")

            val vector = EmotionVector(
                prediction[0],
                prediction[2],
                prediction[1]
            ) // Arousal, Valence, Dominance
            withContext(Dispatchers.Main) { onSuccess(vector) }

        } catch (e: Exception) {
            Log.e(TAG, "Detection failed", e)
            withContext(Dispatchers.Main) { onFailure(e.message ?: "An unknown error occurred.") }
        }
    }

    // In MainActivity.kt, REPLACE the entire onCreate method with this:

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        var appState: AppState by mutableStateOf(AppState.Idle)
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        // Set up the file picker launcher correctly
        pickAudioLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    result.data?.data?.let { uri ->
                        val fileName = getFileName(uri)
                        // When file is picked, go to the Success state
                        appState = AppState.Success(fileName, uri)
                    } ?: run {
                        // If something goes wrong getting the URI
                        appState = AppState.Failure("Could not retrieve file URI.")
                    }
                }
                // You can add an else block here to handle if the user cancels file picking
            }

        setContent {
            // THIS 'MyApplicationTheme' WRAPPER IS THE FIX for the @Composable error
            MyApplicationTheme {
                FinalAttemptScreen(
                    appState = appState,
                    onOpenFileClick = {
                        // Define an array of possible MIME types for WAV files
                        val mimeTypes = arrayOf("audio/wav", "audio/x-wav")

                        // Create the intent and put the array of types as an extra
                        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "audio/*" // Start with a broad audio category
                            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                        }

                        pickAudioLauncher.launch(intent)
                    },
                    onDetectClick = { fileName, uri ->
                        coroutineScope.launch {
                            // This correctly calls your detectEmotion function
                            detectEmotion(
                                uri = uri,
                                onStart = { appState = AppState.Detecting(fileName) },
                                onSuccess = { vector ->
                                    appState = AppState.DetectionSuccess(fileName, vector)
                                },
                                onFailure = { message -> appState = AppState.Failure(message) }
                            )
                        }
                    }
                )
            }
        }
    }

    @SuppressLint("Range")
    private fun getFileName(uri: Uri): String {
        // This uses the content resolver to query the file's display name
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))
            } else {
                "Unknown File" // Fallback if the cursor is empty
            }
        } ?: "Unknown File" // Fallback if the query fails
    }
}

@Composable
fun FinalAttemptScreen(
    appState: AppState,
    onOpenFileClick: () -> Unit,
    onDetectClick: (String, Uri) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val isButtonEnabled = appState !is AppState.Detecting && appState !is AppState.Loading
        Button(onClick = onOpenFileClick, enabled = isButtonEnabled) {
            Text(if (appState is AppState.Idle) "Open WAV File" else "Open New File")
        }

        Spacer(modifier = Modifier.height(24.dp))

        AnimatedContent(targetState = appState, label = "AppStatus") { state ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (state) {
                    is AppState.Idle -> Text("Select a WAV file to begin.")
                    is AppState.Loading -> {
                        CircularProgressIndicator()
                        Text("Reading ${state.fileName}...")
                    }

                    is AppState.Success -> {
                        Text("✅ Upload Complete! ✅", fontSize = 24.sp)
                        Text("File Ready: ${state.fileName}")
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { onDetectClick(state.fileName, state.fileUri) }) {
                            Text("Detect Emotion")
                        }
                    }

                    is AppState.Detecting -> {
                        CircularProgressIndicator()
                        Text("Detecting emotion in ${state.fileName}...")
                    }

                    is AppState.DetectionSuccess -> {
                        Text("✨ Detection Complete! ✨", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(16.dp))
                        EmotionResult(vector = state.result)
                    }

                    is AppState.Failure -> {
                        Text("❌ Unable to Process ❌", fontSize = 24.sp)
                        Text(
                            "An Error Occurred",
                            fontWeight = FontWeight.Bold,
                            color = Color.Red
                        )
                        Text(state.message)
                    }
                }
            }
        }
    }
}

// In MainActivity.kt, REPLACE the EmotionResult function with this:

@Composable
fun EmotionResult(vector: EmotionVector) {
    // This is where you will call your mapping function from EmotionMapper.kt
    // Make sure you have created the EmotionMapper.kt file from the previous step!
    val mappedEmotion = mapVectorToEmotion(vector)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Display the final glorious emotion label
        Text(
            text = mappedEmotion.label,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF6750A4) // A nice purple color from the Material theme
        )

        // Display the description
        Text(
            text = mappedEmotion.description,
            fontSize = 16.sp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Display the raw values below for debugging/interest
        Text("Raw Values:", fontWeight = FontWeight.SemiBold)
        Row {
            Text("Arousal:   ", fontWeight = FontWeight.SemiBold)
            Text(String.format("%.4f", vector.arousal))
        }
        Row {
            Text("Valence:   ", fontWeight = FontWeight.SemiBold)
            Text(String.format("%.4f", vector.valence))
        }
        Row {
            Text("Dominance: ", fontWeight = FontWeight.SemiBold)
            Text(String.format("%.4f", vector.dominance))
        }
    }
}