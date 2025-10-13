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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.isEmpty
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.delay

private const val TAG = "ULTRA_DEBUG"
private const val TARGET_SAMPLE_RATE = 16000

// --- AppState & Data Classes (DEFINED ONCE) ---
sealed interface AppState {
    object Idle : AppState
    object Recording : AppState
    data class Loading(val fileName: String) : AppState
    data class Success(val fileName: String, val fileUri: Uri) : AppState
    data class Detecting(val fileName: String) : AppState
    data class DetectionSuccess(val result: EmotionVector) : AppState
    data class Failure(val message: String) : AppState
    data class PostRecordingReview(val fileName: String, val fileUri: Uri) : AppState
}

data class EmotionVector(val arousal: Float, val valence: Float, val dominance: Float)
data class MappedEmotion(val label: String, val description: String)


// ===================================
//  MainActivity CLASS STARTS HERE
// ===================================
class MainActivity : ComponentActivity() {

    private val emotionPredictor by lazy { EmotionPredictor(this) }
    private val audioPlayer by lazy { AudioPlayer(this) }
    private val audioRecorder by lazy { WavAudioRecorder(this) }

    private lateinit var pickAudioLauncher: ActivityResultLauncher<Intent>
    private lateinit var permissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        var appState: AppState by mutableStateOf(AppState.Idle)
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        permissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                if (isGranted) {
                    appState = AppState.Recording
                } else {
                    appState = AppState.Failure("Microphone permission is required to record audio.")
                }
            }

        pickAudioLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    result.data?.data?.let { uri ->
                        val fileName = getFileName(uri)
                        appState = AppState.Success(fileName, uri)
                    } ?: run {
                        appState = AppState.Failure("Could not retrieve file URI.")
                    }
                }
            }

        setContent {
            MyApplicationTheme {
                MainScreen(
                    appState = appState,
                    amplitude = audioRecorder.amplitude.value,
                    onOpenFileClick = {
                        val mimeTypes = arrayOf("audio/wav", "audio/x-wav")
                        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "audio/*"
                            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                        }
                        pickAudioLauncher.launch(intent)
                    },
                    onRecordAudioClick = {
                        permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    },
                    onStopRecording = {
                        val recordingUri = audioRecorder.stop()
                        if (recordingUri != null) {
                            val fileName = "My Recording.wav"
                            appState = AppState.PostRecordingReview(fileName, recordingUri)
                        } else {
                            appState = AppState.Failure("Failed to save recording.")
                        }
                    },
                    onDetectClick = { fileName, uri ->
                        coroutineScope.launch {
                            detectEmotion(
                                uri = uri,
                                onStart = { appState = AppState.Detecting(fileName) },
                                onSuccess = { vector -> appState = AppState.DetectionSuccess(vector) },
                                onFailure = { message -> appState = AppState.Failure(message) }
                            )
                        }
                    },
                    onStartRecording = { audioRecorder.start() },
                    onPlayRecording = { uri ->
                        // Convert the Uri to a string path that the player can use
                        audioPlayer.play(uri)
                    },
                    onReRecord = { appState = AppState.Recording }
                )
            }
        }
    }

    private suspend fun detectEmotion(
        uri: Uri,
        onStart: () -> Unit,
        onSuccess: (EmotionVector) -> Unit,
        onFailure: (String) -> Unit
    ) {
        withContext(Dispatchers.Main) { onStart() }
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val headerInfo = parseWavHeader(inputStream)
                    ?: throw Exception("Failed to parse WAV header.")
                val (sampleRate, numChannels, bitsPerSample) = headerInfo
                if (sampleRate != TARGET_SAMPLE_RATE || numChannels != 1 || bitsPerSample != 16) {
                    throw Exception("Audio format must be 16kHz, 16-bit, mono.")
                }
                val audioBytes = inputStream.readBytes()
                val shortArray = pcm16BitByteArrayToShortArray(audioBytes)
                    ?: throw Exception("Failed to convert audio bytes.")
                if (shortArray.isEmpty()) throw Exception("Audio data is empty.")

                val processedAudio = preprocessAudioForModel(shortArray)
                    ?: throw Exception("Audio preprocessing returned null.")

                val prediction = emotionPredictor.predict(processedAudio)
                    ?: throw Exception("ONNX model failed to return a result.")
                val vector = EmotionVector(prediction[0], prediction[2], prediction[1])
                withContext(Dispatchers.Main) { onSuccess(vector) }
            } ?: throw Exception("Failed to open input stream for URI.")
        } catch (e: Exception) {
            Log.e(TAG, "Detection failed", e)
            withContext(Dispatchers.Main) { onFailure(e.message ?: "An unknown error occurred.") }
        }
    }

    @SuppressLint("Range")
    private fun getFileName(uri: Uri): String {
        return contentResolver.query(uri, null, null, null, null)?.use {
            if (it.moveToFirst()) it.getString(it.getColumnIndex(OpenableColumns.DISPLAY_NAME))
            else "Unknown File"
        } ?: "Unknown File"
    }
}
// ===================================
//  MainActivity CLASS ENDS HERE
// ===================================


// =======================================================================
// ALL HELPER FUNCTIONS AND COMPOSABLES ARE OUTSIDE THE CLASS
// =======================================================================

fun preprocessAudioForModel(audioData: ShortArray): FloatArray? {
    if (audioData.isEmpty()) return null
    // Normalize from Short (-32768 to 32767) to Float (-1.0 to 1.0)
    return FloatArray(audioData.size) { i -> audioData[i] / 32768.0f }
}


// --- UI COMPOSABLES ---

@Composable
fun MainScreen(
    appState: AppState,
    amplitude: Float,
    onOpenFileClick: () -> Unit,
    onRecordAudioClick: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onDetectClick: (String, Uri) -> Unit,
    onPlayRecording: (Uri) -> Unit,
    onReRecord: () -> Unit
) {
    AnimatedContent(targetState = appState, label = "ScreenState") { state ->
        when (state) {
            is AppState.Recording -> RecordingScreen(amplitude, onStopRecording, onStartRecording)
            is AppState.PostRecordingReview -> PostRecordingScreen(state, onDetectClick, { onPlayRecording(state.fileUri) }, onReRecord)
            else -> FileSelectionScreen(
                appState = state,
                onOpenFileClick = onOpenFileClick,
                onRecordAudioClick = onRecordAudioClick,
                onDetectClick = onDetectClick,
                onPlayRecording = onPlayRecording // <-- ADD THIS
            )

        }
    }
}

@Composable
fun FileSelectionScreen(
    appState: AppState,
    onOpenFileClick: () -> Unit,
    onRecordAudioClick: () -> Unit,
    onDetectClick: (String, Uri) -> Unit,
    onPlayRecording: (Uri) -> Unit // <-- ADD THIS PARAMETER
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val isButtonEnabled = appState !is AppState.Detecting && appState !is AppState.Loading
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Tap to Record Audio", fontWeight = FontWeight.SemiBold)
            IconButton(onClick = onRecordAudioClick, enabled = isButtonEnabled) {
                Icon(imageVector = Icons.Default.Mic, "Record Audio", modifier = Modifier.size(48.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("or")
        Spacer(Modifier.height(8.dp))
        Button(onClick = onOpenFileClick, enabled = isButtonEnabled) {
            Text(if (appState is AppState.Idle) "Open WAV File from Device" else "Open New File")
        }
        Spacer(Modifier.height(24.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (appState) {
                is AppState.Idle -> {}
                is AppState.Loading -> CircularProgressIndicator()
                is AppState.Success -> {
                    Text("✅ Ready for Analysis ✅", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("File: ${appState.fileName}")
                    Spacer(Modifier.height(16.dp))

                    // --- START OF CHANGES ---
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Replay Button
                        Button(onClick = { onPlayRecording(appState.fileUri) }) {
                            Icon(Icons.Default.PlayArrow, "Play")
                            Spacer(Modifier.width(8.dp))
                            Text("Replay")
                        }
                        // Detect Button
                        Button(onClick = { onDetectClick(appState.fileName, appState.fileUri) }) {
                            Text("Detect Emotion")
                        }
                    }
                    // --- END OF CHANGES ---
                }
                is AppState.Detecting -> {
                    CircularProgressIndicator()
                    Text("Detecting emotion in ${appState.fileName}...")
                }
                is AppState.DetectionSuccess -> {
                    Text("✨ Detection Complete! ✨", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))
                    EmotionResult(vector = appState.result)
                }
                is AppState.Failure -> {
                    Text("❌ Error ❌", fontSize = 24.sp, color = Color.Red)
                    Text(appState.message, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))
                }
                else -> {}
            }
        }
    }
}


@Composable
fun RecordingScreen(amplitude: Float, onStopRecording: () -> Unit, onStartRecording: () -> Unit) {
    // This list will store the history of amplitudes for the scrolling effect
    val amplitudes = remember { mutableStateListOf<Float>() }

    // This effect will run whenever the 'amplitude' value changes.
    // By using 'amplitude' as a key, we ensure the block always reads the LATEST value.
    LaunchedEffect(amplitude) {
        amplitudes.add(amplitude) // Add the latest, correct amplitude
        // Keep the list at a manageable size (e.g., the last 100 values)
        if (amplitudes.size > 100) {
            amplitudes.removeAt(0)
        }
    }

    // This effect runs only ONCE to start the recording.
    LaunchedEffect(Unit) {
        onStartRecording()
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Recording...", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(32.dp))

        // This call is correct and does not need to change.
        ScrollingWaveform(
            amplitudes = amplitudes,
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .padding(horizontal = 16.dp)
        )

        Spacer(Modifier.height(32.dp))
        IconButton(
            onClick = { onStopRecording() },
            modifier = Modifier.size(72.dp)
        ) {
            Icon(Icons.Default.Stop, "Stop Recording", tint = Color.Red, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun EmotionResult(vector: EmotionVector) {
    val mappedEmotion = mapVectorToEmotion(vector)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = mappedEmotion.label,
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = mappedEmotion.description,
            fontSize = 16.sp,
            modifier = Modifier.padding(horizontal = 16.dp),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun ScrollingWaveform(
    amplitudes: List<Float>,
    modifier: Modifier = Modifier,
    waveColor: Color = Color.Blue,
    waveThickness: Float = 4f
) {
    Canvas(modifier = modifier.background(Color.LightGray)) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val middleY = canvasHeight / 2

        // Calculate the width of each bar
        val barWidth = canvasWidth / amplitudes.size

        amplitudes.forEachIndexed { index, amplitude ->
            // Calculate the x position of the bar
            val x = index * barWidth

            // Calculate the top and bottom of the bar based on amplitude
            // The amplitude is a value from 0.0 to 1.0
            val barHeight = amplitude * canvasHeight
            val top = middleY - (barHeight / 2)
            val bottom = middleY + (barHeight / 2)

            // Draw the vertical bar
            drawLine(
                color = waveColor,
                start = androidx.compose.ui.geometry.Offset(x, top),
                end = androidx.compose.ui.geometry.Offset(x, bottom),
                strokeWidth = waveThickness
            )
        }
    }
}
@Composable
fun PostRecordingScreen(
    appState: AppState.PostRecordingReview,
    onDetectClick: (String, Uri) -> Unit,
    onPlayClick: () -> Unit,
    onReRecordClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
    ) {
        Text("Recording Complete", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(appState.fileName)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onPlayClick) {
                Icon(Icons.Default.PlayArrow, "Play")
                Spacer(Modifier.width(8.dp))
                Text("Replay")
            }
            Button(onClick = onReRecordClick, colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)) {
                Icon(Icons.Default.Refresh, "Re-record")
                Spacer(Modifier.width(8.dp))
                Text("Re-record")
            }
        }
        Button(onClick = { onDetectClick(appState.fileName, appState.fileUri) }) {
            Text("Detect Emotion From This Recording")
        }
    }
}
