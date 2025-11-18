package com.example.myapplication

import android.annotation.SuppressLint
import android.provider.OpenableColumns
import kotlin.collections.isNullOrEmpty
import com.example.myapplication.WavHeaderInfo
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


private const val TAG = "ULTRA_DEBUG"


sealed interface AppState {
    object Idle : AppState
    object Recording : AppState
    object LiveFeedback : AppState
    data class SessionSummary(val emotions: List<MappedEmotion>) : AppState
    data class Loading(val fileName: String) : AppState
    data class Success(val fileName: String, val fileUri: Uri) : AppState
    data class Detecting(val fileName: String) : AppState
    data class DetectionSuccess(val result: EmotionVector) : AppState
    data class Failure(val message: String) : AppState
    data class PostRecordingReview(val fileName: String, val fileUri: Uri) : AppState
}

class MainActivity : ComponentActivity() {

    private val emotionPredictor by lazy { EmotionPredictor(this) }
    private val audioPlayer by lazy { AudioPlayer(this) }
    private val clipRecorder by lazy { WavClipRecorder(this, CoroutineScope(Dispatchers.Default)) }
    private val liveRecorder by lazy {
        WavAudioChunkRecorder(
            this,
            CoroutineScope(Dispatchers.Default)
        )
    }

    private var liveDetectionJob: Job? = null
    private val _liveEmotion = MutableLiveData<MappedEmotion?>(null)
    val liveEmotion: LiveData<MappedEmotion?> = _liveEmotion
    private val sessionEmotions = mutableListOf<MappedEmotion>()

    private lateinit var pickAudioLauncher: ActivityResultLauncher<Intent>
    private lateinit var permissionLauncher: ActivityResultLauncher<String>

    private var tempAudioFile: File? = null

    private enum class RecordingIntention { CLIP, LIVE, NONE }

    private var recordingIntention = RecordingIntention.NONE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var appState: AppState by mutableStateOf(AppState.Idle)
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        permissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                if (isGranted) {
                    when (recordingIntention) {
                        RecordingIntention.CLIP -> appState = AppState.Recording
                        RecordingIntention.LIVE -> appState = AppState.LiveFeedback
                        RecordingIntention.NONE -> {}
                    }
                } else {
                    appState = AppState.Failure("Microphone permission is required.")
                }
                recordingIntention = RecordingIntention.NONE
            }

        pickAudioLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    result.data?.data?.let { uri ->
                        appState = AppState.Success(getFileName(uri), uri)
                    } ?: run {
                        appState = AppState.Failure("Could not retrieve file URI.")
                    }
                }
            }

        setContent {
            MyApplicationTheme {
                EmotionEvaluatorScreen()
                val currentLiveEmotion by liveEmotion.observeAsState()
                val liveAmplitude by liveRecorder.amplitude.collectAsState()
                val clipAmplitude by clipRecorder.amplitude.collectAsState()

                val amplitudeToDisplay = when (appState) {
                    is AppState.LiveFeedback -> liveAmplitude
                    is AppState.Recording -> clipAmplitude
                    else -> 0f
                }

                MainScreen(
                    appState = appState,
                    amplitude = amplitudeToDisplay,
                    liveEmotion = currentLiveEmotion,
                    onOpenFileClick = {
                        val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "audio/*" }
                        pickAudioLauncher.launch(intent)
                    },
                    onStartClipRecording = {
                        recordingIntention = RecordingIntention.CLIP
                        permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    },
                    onStopClipRecording = {
                        clipRecorder.stop()
                        tempAudioFile?.let {
                            appState = AppState.PostRecordingReview(it.name, Uri.fromFile(it))
                        }
                    },
                    onStartLiveRecording = {
                        recordingIntention = RecordingIntention.LIVE
                        permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    },
                    onStopLiveRecording = {
                        liveDetectionJob?.cancel()
                        liveRecorder.stop()
                        appState = AppState.SessionSummary(sessionEmotions.toList())
                        sessionEmotions.clear()
                        _liveEmotion.value = null
                    },
                    onDetectClick = { fileName, uri ->
                        coroutineScope.launch {
                            detectEmotion(
                                uri = uri,
                                onStart = { appState = AppState.Detecting(fileName) },
                                onSuccess = { vector ->
                                    appState = AppState.DetectionSuccess(vector)
                                },
                                onFailure = { message -> appState = AppState.Failure(message) }
                            )
                        }
                    },
                    onPlayRecording = { uri -> audioPlayer.play(uri) },
                    onGoHome = {
                        liveDetectionJob?.cancel()
                        liveRecorder.stop()
                        clipRecorder.stop()
                        sessionEmotions.clear()
                        _liveEmotion.value = null
                        appState = AppState.Idle
                    },
                    triggerClipRecording = {
                        val timeStamp =
                            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                        tempAudioFile = File(cacheDir, "REC_${timeStamp}.wav")
                        tempAudioFile?.let { clipRecorder.start(it) }
                    },
                    // THIS IS THE DEFINITIVE FIX FOR LIVE FEEDBACK
                    triggerLiveRecording = {
                        liveDetectionJob?.cancel() // Cancel any old job before starting a new one.
                        // Create a new parent job that we can control.
                        liveDetectionJob = coroutineScope.launch {
                            // Call the recorder's start function. The lambda provided to it
                            // will be executed by the recorder for each audio chunk.
                            liveRecorder.start { audioChunk ->
                                // Inside the lambda, launch a new, small coroutine to process the chunk.
                                // This prevents the detection from blocking the recording process.
                                launch {
                                    detectLiveEmotion(audioChunk)
                                }
                            }
                        }
                    }
                )
            }
        }
    }
    @SuppressLint("Range")
    private fun getFileName(uri: Uri): String {
        return contentResolver.query(uri, null, null, null, null)?.use {
            if (it.moveToFirst()) it.getString(it.getColumnIndex(OpenableColumns.DISPLAY_NAME)) else "Unknown File"
        } ?: "Unknown File"
    }

    // In your CURRENT MainActivity.kt, find and REPLACE the detectEmotion function

    // In MainActivity.kt

    // In MainActivity.kt

    private suspend fun detectEmotion(
        uri: Uri,
        onStart: () -> Unit,
        onSuccess: (EmotionVector) -> Unit,
        onFailure: (String) -> Unit
    ) {
        withContext(Dispatchers.Main) { onStart() }
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                // Use the new, more informative header parser
                val header = parseWavHeader(inputStream)
                    ?: throw Exception("Failed to parse WAV header.")

                val audioBytes = inputStream.readBytes()

                // --- THIS IS THE DEFINITIVE FIX ---
                // Now we can correctly choose the conversion based on the audio format
                val shortArray: ShortArray? = when (header.bitsPerSample) {
                    16 -> pcm16BitByteArrayToShortArray(audioBytes)
                    32 -> {
                        if (header.audioFormat == 3) { // 3 means 32-bit Float
                            Log.d(TAG, "Detected 32-bit FLOAT audio. Using float-to-short converter.")
                            pcm32BitFloatByteArrayToShortArray(audioBytes)
                        } else { // Assume 1 means 32-bit Integer
                            Log.d(TAG, "Detected 32-bit INTEGER audio. Using int-to-short converter.")
                            pcm32BitIntByteArrayToShortArray(audioBytes)
                        }
                    }
                    else -> throw Exception("Unsupported bit depth: ${header.bitsPerSample}")
                }

                if (shortArray == null || shortArray.isEmpty()) {
                    throw Exception("Audio data is empty or failed conversion.")
                }

                // The rest of the logic is now correct and will receive proper data
                val processedAudio = preprocessAudioForModel(shortArray)
                    ?: throw Exception("Audio preprocessing returned null.")

                val prediction = emotionPredictor.predict(processedAudio)
                    ?: throw Exception("ONNX model failed to return a result.")

                val vector = EmotionVector(
                    arousal = prediction[0],
                    valence = prediction[2],
                    dominance = prediction[1]
                )
                Log.d(TAG, "SUCCESS - VAD Vector -> Arousal: ${vector.arousal}, Valence: ${vector.valence}, Dominance: ${vector.dominance}")
                withContext(Dispatchers.Main) { onSuccess(vector) }

            } ?: throw Exception("Failed to open input stream for URI.")
        } catch (e: Exception) {
            Log.e(TAG, "Detection failed", e)
            withContext(Dispatchers.Main) { onFailure(e.message ?: "An unknown error occurred.") }
        }
    }





// Now, find and REPLACE the detectLiveEmotion function to match

    private suspend fun detectLiveEmotion(audioChunk: ShortArray) {
        try {
            if (audioChunk.isEmpty()) return

            // Use the simple, correct preprocessing
            val processedAudio = preprocessAudioForModel(audioChunk) ?: return
            val prediction = emotionPredictor.predict(processedAudio) ?: return

            // --- THE CRITICAL FIX: Use the original, correct VAD mapping ---
            val vector = EmotionVector(
                arousal = prediction[0],
                valence = prediction[2],
                dominance = prediction[1]
            )
            val mapped = mapVectorToEmotion(vector) // Your EmotionMapper now gets correct data

            withContext(Dispatchers.Main) { _liveEmotion.value = mapped }
            sessionEmotions.add(mapped)
        } catch (e: Exception) {
            Log.e(TAG, "Live detection on chunk failed", e)
        }
    }


// --- ALL COMPOSABLE SCREENS ---

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainScreen(
        appState: AppState,
        amplitude: Float,
        liveEmotion: MappedEmotion?,
        onOpenFileClick: () -> Unit,
        onStartClipRecording: () -> Unit,
        onStopClipRecording: () -> Unit,
        onStartLiveRecording: () -> Unit,
        onStopLiveRecording: () -> Unit,
        onDetectClick: (String, Uri) -> Unit,
        onPlayRecording: (Uri) -> Unit,
        onGoHome: () -> Unit,
        triggerClipRecording: () -> Unit,
        triggerLiveRecording: () -> Unit
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Emotion Detection App") },
                    navigationIcon = {
                        if (appState !is AppState.Idle) {
                            IconButton(onClick = onGoHome) {
                                Icon(
                                    imageVector = Icons.Default.Home,
                                    contentDescription = "Go to Home Screen"
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                )
            }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                AnimatedContent(targetState = appState, label = "Main Screen Animation") { state ->
                    when (state) {
                        is AppState.Recording -> RecordingScreen(
                            amplitude,
                            onStopClipRecording,
                            triggerClipRecording
                        )

                        is AppState.LiveFeedback -> LiveFeedbackScreen(
                            amplitude,
                            liveEmotion,
                            onStopLiveRecording,
                            triggerLiveRecording
                        )

                        is AppState.SessionSummary -> SessionSummaryScreen(state.emotions)
                        is AppState.PostRecordingReview -> PostRecordingScreen(
                            state,
                            onDetectClick,
                            { onPlayRecording(state.fileUri) },
                            onStartClipRecording
                        )

                        else -> FileSelectionScreen(
                            state,
                            onOpenFileClick,
                            onStartClipRecording,
                            onStartLiveRecording,
                            onDetectClick,
                            onPlayRecording
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
        onLiveFeedbackClick: () -> Unit,
        onDetectClick: (String, Uri) -> Unit,
        onPlayRecording: (Uri) -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(2f),
                contentAlignment = Alignment.Center
            ) {
                when (appState) {
                    is AppState.Loading -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text("Loading ${appState.fileName}...")
                        }
                    }

                    is AppState.Success -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Ready to analyze:", style = MaterialTheme.typography.titleMedium)
                            Text(appState.fileName, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(16.dp))
                            Row {
                                Button(onClick = {
                                    onDetectClick(
                                        appState.fileName,
                                        appState.fileUri
                                    )
                                }) { Text("Detect Emotion") }
                                Spacer(Modifier.width(16.dp))
                                Button(onClick = { onPlayRecording(appState.fileUri) }) { Text("Replay") }
                            }
                        }
                    }

                    is AppState.Detecting -> {
                        // --- FIX APPLIED HERE ---
                        // This Column ensures both the spinner and the text are centered horizontally.
                        // The textAlign on the Text composable handles multi-line centering if the file name is long.
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 16.dp) // Add padding for long file names
                        ) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Detecting emotion in ${appState.fileName}...",
                                textAlign = TextAlign.Center // This centers the text itself
                            )
                        }
                    }

                    is AppState.DetectionSuccess -> EmotionResult(vector = appState.result)
                    is AppState.Failure -> Text(
                        text = appState.message,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )

                    else -> { /* Idle state */
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onLiveFeedbackClick, modifier = Modifier.height(80.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Analytics, "Live Feedback")
                        Text("Live")
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                                Icons.Default.Mic,
                                "Record Audio",
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                    Text("Record Clip", style = MaterialTheme.typography.bodyMedium)
                }
            }

            // --- CHANGE APPLIED HERE ---
            // Added the instructional text right below the button row.
            Text(
                text = "For best results, record a clip at least 3 seconds long expressing one clear emotion.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            // --- END OF CHANGE ---

            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onOpenFileClick) { Text("Or Open an Audio File") }
            Spacer(modifier = Modifier.weight(1f))
        }
    }

    @Composable
    fun RecordingScreen(
        amplitude: Float,
        onStopRecording: () -> Unit,
        onStartRecording: () -> Unit
    ) {
        LaunchedEffect(Unit) { onStartRecording() }
        val amplitudes = remember { mutableStateListOf<Float>() }
        LaunchedEffect(amplitude) {
            amplitudes.add((amplitude * 4.0f).coerceIn(0f, 1f))
            if (amplitudes.size > 150) amplitudes.removeAt(0)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    "Recording Clip...",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            ScrollingWaveform(
                amplitudes = amplitudes,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(32.dp))
            IconButton(onClick = { onStopRecording() }, modifier = Modifier.size(72.dp)) {
                Icon(
                    Icons.Default.Stop,
                    "Stop Recording",
                    tint = Color.Red,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(Modifier.weight(0.5f))
        }
    }

    @Composable
    fun LiveFeedbackScreen(
        amplitude: Float,
        liveEmotion: MappedEmotion?,
        onStop: () -> Unit,
        onStartRecording: () -> Unit
    ) {
        LaunchedEffect(Unit) { onStartRecording() }
        val amplitudes = remember { mutableStateListOf<Float>() }
        LaunchedEffect(amplitude) {
            amplitudes.add((amplitude * 4.0f).coerceIn(0f, 1f))
            if (amplitudes.size > 150) amplitudes.removeAt(0)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = liveEmotion?.label ?: "Listening...",
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    color = liveEmotion?.color ?: Color.White,
                    textAlign = TextAlign.Center
                )
            }
            ScrollingWaveform(
                amplitudes = amplitudes,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(32.dp))
            IconButton(onClick = onStop, modifier = Modifier.size(72.dp)) {
                Icon(
                    Icons.Default.Stop,
                    "Stop Live Feedback",
                    tint = Color.Red,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(Modifier.weight(0.5f))
        }
    }

    @Composable
    fun SessionSummaryScreen(emotions: List<MappedEmotion>) {
        val emotionCounts = emotions.groupingBy { it.label }.eachCount()

        if (emotions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No emotions were detected during the session.",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp)
                )
            }
            return
        }

        val mostFrequentEmotion = emotionCounts.maxByOrNull { it.value }?.key ?: "N/A"
        val primaryEmotionColor = emotions.find { it.label == mostFrequentEmotion }?.color
            ?: MaterialTheme.colorScheme.primary

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "Session Summary",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(24.dp))
            Text("Primary Emotion Detected:", style = MaterialTheme.typography.titleMedium)
            Text(
                mostFrequentEmotion,
                fontSize = 32.sp,
                color = primaryEmotionColor,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(32.dp))
            Text("Emotion Breakdown:", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Column(horizontalAlignment = Alignment.Start) {
                emotionCounts.forEach { (label, count) ->
                    val color = emotions.find { it.label == label }?.color ?: Color.Unspecified
                    Text("$label: $count times", fontFamily = FontFamily.Monospace, color = color)
                }
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
            Text(
                text = mappedEmotion.label,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                color = mappedEmotion.color,
                textAlign = TextAlign.Center,
                lineHeight = 52.sp
            )
            Text(
                text = mappedEmotion.description,
                fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 16.dp),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = detailsText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                fontFamily = FontFamily.Monospace
            )
        }
    }

    @Composable
    fun ScrollingWaveform(
        amplitudes: List<Float>,
        modifier: Modifier = Modifier,
        waveColor: Color = MaterialTheme.colorScheme.primary
    ) {
        Canvas(modifier = modifier) {
            val canvasWidth = size.width;
            val canvasHeight = size.height;
            val middleY = canvasHeight / 2
            val barWidth = if (amplitudes.isNotEmpty()) canvasWidth / amplitudes.size else 0f
            amplitudes.forEachIndexed { index, amplitude ->
                val x = index * barWidth;
                val barHeight = amplitude * canvasHeight
                drawLine(
                    color = waveColor,
                    start = androidx.compose.ui.geometry.Offset(x, middleY - barHeight / 2),
                    end = androidx.compose.ui.geometry.Offset(x, middleY + barHeight / 2),
                    strokeWidth = 4f
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
            // FIXED: Corrected the typo from spacedby to spacedBy
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onPlayClick) {
                    Icon(
                        Icons.Default.PlayArrow,
                        "Play"
                    ); Spacer(Modifier.width(8.dp)); Text("Replay")
                }
                Button(
                    onClick = onReRecordClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        "Re-record"
                    ); Spacer(Modifier.width(8.dp)); Text("Re-record")
                }
            }
            Button(onClick = {
                onDetectClick(
                    appState.fileName,
                    appState.fileUri
                )
            }) { Text("Detect Emotion From This Recording") }
        }
    }
}
@Composable
fun EmotionEvaluatorScreen() {
    // --- FIX: Use rememberSaveable to preserve state on screen rotation ---
    // These state holders will now survive activity recreation.
    var arousal by rememberSaveable { mutableStateOf(0.5f) }
    var valence by rememberSaveable { mutableStateOf(0.5f) }
    var dominance by rememberSaveable { mutableStateOf(0.5f) }

    // The emotion vector is derived from the state, so it will update automatically.
    val emotionVector = EmotionVector(arousal, valence, dominance)

    // The mapping logic is called here.
    val mappedEmotion = mapVectorToEmotion(emotionVector)

    // UI Layout
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Display the resulting emotion
            Text(
                text = mappedEmotion.label,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                color = mappedEmotion.color
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = mappedEmotion.description,
                fontSize = 16.sp,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(32.dp))

            // VAD Sliders
            VADSlider(
                label = "Valence",
                value = valence,
                onValueChange = { valence = it }
            )
            VADSlider(
                label = "Arousal",
                value = arousal,
                onValueChange = { arousal = it }
            )
            VADSlider(
                label = "Dominance",
                value = dominance,
                onValueChange = { dominance = it }
            )
        }
    }
}

@Composable
fun VADSlider(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(text = "$label: ${"%.2f".format(value)}")
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..1f
        )
    }
}