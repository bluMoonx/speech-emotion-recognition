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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
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
import androidx.compose.ui.draw.clip
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults


private const val TAG = "ULTRA_DEBUG"

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


// ===================================
//  MainActivity CLASS STARTS HERE
// ===================================
class MainActivity : ComponentActivity() {

    private val emotionPredictor by lazy { EmotionPredictor(this) }
    private val audioPlayer by lazy { AudioPlayer(this) }
    private val audioRecorder by lazy { WavAudioRecorder(this) }

    private lateinit var pickAudioLauncher: ActivityResultLauncher<Intent>
    private lateinit var permissionLauncher: ActivityResultLauncher<String>
    private val Triple<Int, Int, Int>.sampleRate: Int
        get() = this.first

    private val Triple<Int, Int, Int>.numChannels: Int
        get() = this.second

    private val Triple<Int, Int, Int>.bitsPerSample: Int
        get() = this.third
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
                    onReRecord = { appState = AppState.Recording },
                    onGoHome = { appState = AppState.Idle}
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
                // Step 1: Parse the header to know the original format
                val headerInfo = parseWavHeader(inputStream)
                    ?: throw Exception("Failed to parse WAV header. Not a valid WAV file.")

                val audioBytes = inputStream.readBytes()

                // Step 2: Use the universal converter to get the data into the required format
                Log.d(TAG, "Original format: ${headerInfo.sampleRate}Hz, ${headerInfo.bitsPerSample}-bit, ${headerInfo.numChannels}-channel. Converting to target format...")
                val shortArray = AudioConverter.convertToTargetFormat(audioBytes, headerInfo)
                    ?: throw Exception("Unsupported audio format or failed to convert. Original: ${headerInfo.bitsPerSample}-bit.")

                if (shortArray.isEmpty()) {
                    throw Exception("Audio data is empty after conversion.")
                }
                Log.d(TAG, "Conversion successful. ${shortArray.size} samples ready for model.")

                // Step 3: Pre-process and predict as before
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


// In MainActivity.kt, find and replace the MainScreen composable...

@OptIn(ExperimentalMaterial3Api::class)
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
    onReRecord: () -> Unit,
    onGoHome: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Emotion Detection App") },
                navigationIcon = {
                    // Show the home icon on every screen EXCEPT the initial Idle screen.
                    if (appState !is AppState.Idle) {
                        IconButton(onClick = onGoHome) {
                            Icon(
                                imageVector = Icons.Default.Home,
                                contentDescription = "Go to Home Screen"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { innerPadding ->
        // The AnimatedContent from before is now placed inside the Scaffold's content area.
        Box(modifier = Modifier.padding(innerPadding)) {
            AnimatedContent(targetState = appState, label = "ScreenState") { state ->
                when (state) {
                    is AppState.Recording -> RecordingScreen(amplitude, onStopRecording, onStartRecording)
                    is AppState.PostRecordingReview -> PostRecordingScreen(state, onDetectClick, { onPlayRecording(state.fileUri) }, onReRecord)
                    else -> FileSelectionScreen(
                        appState = state,
                        onOpenFileClick = onOpenFileClick,
                        onRecordAudioClick = onRecordAudioClick,
                        onDetectClick = onDetectClick,
                        onPlayRecording = onPlayRecording
                    )
                }
            }
        }
    }
}

@Composable
fun FileSelectionScreen(
    appState: AppState,
    onOpenFileClick: () -> Unit,
    onRecordAudioClick: () -> Unit,
    onDetectClick: (String, Uri) -> Unit,onPlayRecording: (Uri) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        // Main vertical arrangement is now handled by Spacers inside
    ) {
        // --- 1. TOP SPACER ---
        // Pushes everything down from the top edge of the screen.
        Spacer(modifier = Modifier.weight(1f))

        // --- 2. DYNAMIC CONTENT AREA (RESULTS, LOADING, ETC.) ---
        // This Box ensures that the content area has a consistent minimum height
        // so the layout doesn't jump around too much.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(2f), // Takes up a good portion of the middle screen
            contentAlignment = Alignment.Center
        ) {
            when (appState) {
                is AppState.Loading -> {
                    CircularProgressIndicator()
                    Text("Loading ${appState.fileName}...")
                }
                is AppState.Success -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Ready to analyze:", style = MaterialTheme.typography.titleMedium)
                        Text(appState.fileName, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(16.dp))
                        Row {
                            Button(onClick = { onDetectClick(appState.fileName, appState.fileUri) }) {
                                Text("Detect Emotion")
                            }
                            Spacer(Modifier.width(16.dp))
                            Button(onClick = { onPlayRecording(appState.fileUri) }) {
                                Text("Replay")
                            }
                        }
                    }
                }
                is AppState.Detecting -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text("Detecting emotion in ${appState.fileName}...")
                    }
                }
                is AppState.DetectionSuccess -> {
                    EmotionResult(vector = appState.result)
                }
                is AppState.Failure -> {
                    Text(
                        text = appState.message,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
                else -> {
                    // This space is intentionally left blank for the Idle state.
                    // The recording button is now shown below this dynamic area.
                }
            }
        }

        // --- 3. THE "ALWAYS VISIBLE" ACTION BUTTONS ---
        // This section is now outside the 'when' block, so it always shows.

        // The recording button and its text
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(top = 24.dp) // Add space above the mic
        ) {
            IconButton(
                onClick = onRecordAudioClick,
                modifier = Modifier.size(72.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Record Audio",
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp)) // Smaller spacer
            Text(
                text = "Tap to Record Audio",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }

        // The file open button
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onOpenFileClick) {
            Text("Or Open an Audio File")
        }

        // --- 4. BOTTOM SPACER ---
        // Pushes everything up from the bottom edge.
        Spacer(modifier = Modifier.weight(1f))
    }
}



// In MainActivity.kt, inside the RecordingScreen composable...

@Composable
fun RecordingScreen(amplitude: Float, onStopRecording: () -> Unit, onStartRecording: () -> Unit) {
    val amplitudes = remember { mutableStateListOf<Float>() }
    val sensitivity = 4.0f // <-- You can adjust this factor. 2.0f, 3.0f, or 4.0f work well.

    LaunchedEffect(amplitude) {
        // Multiply the raw amplitude by the sensitivity factor before adding it.
        // `coerceIn` ensures the value doesn't go above 1.0f, which would break the visual.
        val boostedAmplitude = (amplitude * sensitivity).coerceIn(0f, 1f)

        amplitudes.add(boostedAmplitude)

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
    val detailsText = """
        Valence:   ${"%.4f".format(vector.valence)}
        Arousal:   ${"%.4f".format(vector.arousal)}
        Dominance: ${"%.4f".format(vector.dominance)}
    """.trimIndent()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // The main emotion label (e.g., "Angry")
        Text(
            text = mappedEmotion.label,
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            lineHeight = 52.sp
        )

        // The description of the emotion
        Text(
            text = mappedEmotion.description,
            fontSize = 16.sp,
            modifier = Modifier.padding(horizontal = 16.dp),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        // The raw values for your debugging
        Text(
            text = detailsText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            fontFamily = FontFamily.Monospace // Makes the numbers line up nicely
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
