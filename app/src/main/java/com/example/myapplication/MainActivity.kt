package com.example.myapplication

// --- ALL NECESSARY IMPORTS ---
import android.annotation.SuppressLint
import android.app.Activity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
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
import java.util.*
import kotlin.math.sqrt
import kotlin.random.Random

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
        )
    }

    private val _liveEmotion = MutableLiveData<MappedEmotion?>(null)
    val liveEmotion: LiveData<MappedEmotion?> = _liveEmotion
    private val sessionEmotions = mutableListOf<MappedEmotion>()
    private val emotionHistory = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var lastDisplayedEmotion: String? = null
    private var liveAudioBuffer = ShortArray(0)
    private var isLiveCalibrating by mutableStateOf(false)
    private lateinit var pickAudioLauncher: ActivityResultLauncher<Intent>
    private lateinit var permissionLauncher: ActivityResultLauncher<String>
    private var tempAudioFile: File? = null
    private enum class RecordingIntention { CLIP, LIVE, NONE }
    private var recordingIntention = RecordingIntention.NONE

    private fun isSpeechDetected(audioData: ShortArray, threshold: Float): Boolean {
        if (audioData.isEmpty()) return false
        val rms = kotlin.math.sqrt(audioData.map { it.toDouble() * it.toDouble() }.average())
        val normalizedRms = rms / 32767.0f
        return normalizedRms > threshold
    }



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
                val currentLiveEmotion by liveEmotion.observeAsState()
                val liveAmplitude by liveRecorder.amplitude.collectAsState()
                val clipAmplitude by clipRecorder.amplitude.collectAsState()

                val amplitudeToDisplay = when (appState) {
                    is AppState.LiveFeedback -> liveAmplitude
                    is AppState.Recording -> clipAmplitude
                    else -> 0f
                }


                LaunchedEffect(appState) {
                    if (appState is AppState.LiveFeedback) {
                        isLiveCalibrating = true
                        Log.d("LivePipeline", "STATE CHANGE: LiveFeedback. Starting audio collection.")


                        val targetBufferSize = (16000 * 1.2).toInt()
                        var chunksReceived = 0

                        liveRecorder.start { audioChunk ->
                            liveAudioBuffer = liveAudioBuffer.plus(audioChunk)

                            if (liveAudioBuffer.size > targetBufferSize) {
                                liveAudioBuffer = liveAudioBuffer.takeLast(targetBufferSize).toShortArray()
                            }
                            chunksReceived++

                            if (chunksReceived % 4 == 0) {
                                coroutineScope.launch {
                                    detectLiveEmotion(liveAudioBuffer.copyOf())
                                }
                            }
                        }

                    } else {
                        Log.d("LivePipeline", "STATE CHANGE: Not LiveFeedback. Calling liveRecorder.stop()")
                        liveRecorder.stop()
                        liveAudioBuffer = ShortArray(0)
                    }
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
                        Log.d("LivePipeline", "Stop button pressed. Changing state to SessionSummary.")
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
                        Log.d("LivePipeline", "Home button pressed. Changing state to Idle.")
                        appState = AppState.Idle
                        sessionEmotions.clear()
                        _liveEmotion.value = null
                    },
                    triggerClipRecording = {
                        val timeStamp =
                            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                        tempAudioFile = File(cacheDir, "REC_${timeStamp}.wav")
                        tempAudioFile?.let { clipRecorder.start(it) }
                    },
                    triggerLiveRecording = {
                        // empty
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

    private suspend fun detectEmotion(
        uri: Uri,
        onStart: () -> Unit,
        onSuccess: (EmotionVector) -> Unit,
        onFailure: (String) -> Unit
    ) {
        withContext(Dispatchers.Main) { onStart() }
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val header = parseWavHeader(inputStream)
                    ?: throw Exception("Failed to parse WAV header.")

                val audioBytes = inputStream.readBytes()

                val shortArray: ShortArray? = when (header.bitsPerSample) {
                    16 -> pcm16BitByteArrayToShortArray(audioBytes)
                    32 -> {
                        if (header.audioFormat == 3) { // 3 means 32-bit Float
                            pcm32BitFloatByteArrayToShortArray(audioBytes)
                        } else { // Assume 1 means 32-bit Integer
                            pcm32BitIntByteArrayToShortArray(audioBytes)
                        }
                    }
                    else -> throw Exception("Unsupported bit depth: ${header.bitsPerSample}")
                }

                if (shortArray == null || shortArray.isEmpty()) {
                    throw Exception("Audio data is empty or failed conversion.")
                }

                val MIN_SAMPLES_THRESHOLD = 8000
                val FILE_VAD_THRESHOLD = 0.01f
                if (shortArray.size < MIN_SAMPLES_THRESHOLD || !isSpeechDetected(shortArray, FILE_VAD_THRESHOLD)) {
                    onFailure("Recording is too short or silent. Unable to detect emotion.")
                    return
                }
                val processedAudio = preprocessAudioForModel(shortArray)
                    ?: throw Exception("Audio preprocessing returned null.")

                val prediction = emotionPredictor.predict(processedAudio)
                    ?: throw Exception("ONNX model failed to return a result.")

                val vector = EmotionVector(
                    arousal = prediction[0],
                    valence = prediction[2],
                    dominance = prediction[1]
                )
                withContext(Dispatchers.Main) { onSuccess(vector) }

            } ?: throw Exception("Failed to open input stream for URI.")
        } catch (e: Exception) {
            Log.e(TAG, "Detection failed", e)
            withContext(Dispatchers.Main) { onFailure(e.message ?: "An unknown error occurred.") }
        }
    }

    private suspend fun detectLiveEmotion(audioChunk: ShortArray) {
        val LIVE_VAD_THRESHOLD = 0.015f
        if (!isSpeechDetected(audioChunk, LIVE_VAD_THRESHOLD)) {
            _liveEmotion.postValue(null)
            Log.d("LivePipeline", "Silence detected in live chunk. Displaying 'Listening...'.")
            return
        }
        Log.d("LivePipeline", "detectLiveEmotion: Started for chunk size ${audioChunk.size}.")
        try {
            if (audioChunk.isEmpty()) {
                Log.w("LivePipeline", "Skipping empty audio chunk.")
                return
            }

            val processedAudio = preprocessAudioForModel(audioChunk)
            val prediction = emotionPredictor.predict(processedAudio)

            if (prediction == null) {
                Log.e("LivePipeline", "Model prediction was null.")
                return
            }

            val vector = EmotionVector(
                arousal = prediction[0],
                valence = prediction[2],
                dominance = prediction[1]
            )
            val mapped = mapVectorToEmotionForLive(vector)
            sessionEmotions.add(mapped)
            emotionHistory.add(mapped.label)

            val SMOOTHING_WINDOW_SIZE = 5
            if (emotionHistory.size > SMOOTHING_WINDOW_SIZE) {
                emotionHistory.removeAt(0)
            }

            val mostCommonEmotionLabel = emotionHistory
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key

            if (mostCommonEmotionLabel != null && mostCommonEmotionLabel != lastDisplayedEmotion) {
                val CONFIDENCE_THRESHOLD = 2
                val count = emotionHistory.count { it == mostCommonEmotionLabel }

                if (count >= CONFIDENCE_THRESHOLD) {
                    if (isLiveCalibrating) {
                        isLiveCalibrating = false
                    }
                    lastDisplayedEmotion = mostCommonEmotionLabel
                    val newEmotionToDisplay = createMappedEmotion(mostCommonEmotionLabel)
                    _liveEmotion.postValue(newEmotionToDisplay)
                    Log.d("LivePipeline", "UI UPDATE: Trend changed to '${newEmotionToDisplay.label}'.")
                }
            }
        } catch (e: Exception) {
            Log.e("LivePipeline", "detectLiveEmotion: CRASHED with an exception.", e)
        }
    }

    private fun createMappedEmotion(label: String): MappedEmotion {
        return when(label) {
            "Joy" -> MappedEmotion("Joy", "A positive and controlled emotional state.", Color(0xFFD4AF37))
            "Surprise" -> MappedEmotion("Surprise", "A sharp, sudden reaction of moderate-to-high energy and control.", Color(0xFF40E0D0))
            "Anger" -> MappedEmotion("Anger", "A powerful, aggressive, and high-energy negative state.", Color(0xFFB22222))
            "Disgust" -> MappedEmotion("Disgust", "A controlled negative reaction to something unpleasant.", Color(0xFF556B2F))
            "Sadness" -> MappedEmotion("Sadness", "A state of low energy, low positivity, and low control.", Color(0xFF191970))
            "Fear" -> MappedEmotion("Fear", "A negative state defined by a lack of control.", Color(0xFF4B0082))
            "Neutral" -> MappedEmotion("Neutral", "A balanced emotional state with no single strong emotion.", Color.Gray)
            else -> MappedEmotion("...", "Listening...", Color.White)
        }
    }

    private fun mapVectorToEmotionForLive(vector: EmotionVector): MappedEmotion {
        val (arousal, valence, dominance) = vector

        val ENERGY_THRESHOLD = 0.45f
        if (arousal < ENERGY_THRESHOLD && dominance < ENERGY_THRESHOLD) {
            return createMappedEmotion("Neutral")
        }
        return mapVectorToEmotion(vector)
    }

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
                val isCalibrating = liveEmotion == null && emotionHistory.isEmpty()
                val textToShow = when {
                    isCalibrating -> "Keep talking...\nEvaluating emotions..."
                    liveEmotion == null -> "Listening..."
                    else -> liveEmotion.label
                }
                val colorToShow = liveEmotion?.color ?: Color.White
                val fontSizeToShow = if (isCalibrating) 36.sp else 48.sp

                Text(
                    text = textToShow,
                    fontSize = fontSizeToShow,
                    fontWeight = FontWeight.Bold,
                    color = colorToShow,
                    textAlign = TextAlign.Center,
                    lineHeight = if (isCalibrating) 40.sp else 52.sp
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
        val totalEmotions = emotions.size.coerceAtLeast(1)

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
            Text("Session Summary", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))
            Text("Primary Emotion Detected:", style = MaterialTheme.typography.titleMedium)
            Text(mostFrequentEmotion, fontSize = 32.sp, color = primaryEmotionColor, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(32.dp))
            Text("Emotion Breakdown:", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Column(horizontalAlignment = Alignment.Start) {
                // --- NEW: LOGIC FOR PERCENTAGES ---
                emotionCounts.forEach { (label, count) ->
                    val percentage = (count.toFloat() / totalEmotions) * 100
                    val color = emotions.find { it.label == label }?.color ?: Color.Unspecified
                    // Use a formatted string to show the percentage nicely
                    Text(
                        text = "${label.padEnd(9)}: ${"%.1f".format(percentage)}%",
                        fontFamily = FontFamily.Monospace,
                        color = color
                    )
                }
            }
        }
    }


    // FIX: ADDING THE MISSING EMOTIONRESULT COMPOSABLE
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
            val canvasWidth = size.width
            val canvasHeight = size.height
            val middleY = canvasHeight / 2
            val barWidth = if (amplitudes.isNotEmpty()) canvasWidth / amplitudes.size else 0f
            amplitudes.forEachIndexed { index, amplitude ->
                val x = index * barWidth
                val barHeight = amplitude * canvasHeight
                drawLine(
                    color = waveColor,
                    start = Offset(x, middleY - barHeight / 2),
                    end = Offset(x, middleY + barHeight / 2),
                    strokeWidth = 4f
                )
            }
        }
    }


    @Composable
    fun WelcomeSection() {
        val provider = remember {
            GoogleFont.Provider(
                providerAuthority = "com.google.android.gms.fonts",
                providerPackage = "com.google.android.gms",
                certificates = R.array.com_google_android_gms_fonts_certs
            )
        }

        val fontFamily = remember {
            FontFamily(
                Font(GoogleFont("Montserrat"), provider, FontWeight.Bold),
                Font(GoogleFont("Montserrat"), provider, FontWeight.Normal)
            )
        }

        val animatedVisibility = remember { MutableTransitionState(false) }
            .apply { targetState = true }

        AnimatedVisibility(
            visibleState = animatedVisibility,
            enter = slideInVertically(
                initialOffsetY = { it / 2 },
                animationSpec = tween(durationMillis = 1000, delayMillis = 200, easing = EaseOutCubic)
            ) + fadeIn(animationSpec = tween(1000, delayMillis = 200)),
            exit = fadeOut()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "SER",
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 80.sp,
                    color = Color.White,
                    modifier = Modifier.graphicsLayer {
                        shadowElevation = 20f
                    }
                )
                Text(
                    text = "Speech Emotion Recognition",
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 2.sp,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
                Text(
                    text = "Discover the emotional landscape of your voice. Using the VAD model, SER maps your speech to core emotions like Joy, Anger, and Sadness in real-time.",
                    textAlign = TextAlign.Center,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.Normal,
                    color = Color.White.copy(alpha = 0.8f),
                    lineHeight = 24.sp,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "VAD stands for Valence-Arousal-Dominance; valence - positive/negative energy; arousal - energetic/tired energy; dominance - dominant/subservient energy.",
                    textAlign = TextAlign.Center,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.Normal,
                    color = Color.White.copy(alpha = 0.5f),
                    lineHeight = 18.sp,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 32.dp)
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

    @Composable
    fun FileSelectionScreen(
        appState: AppState,
        onOpenFileClick: () -> Unit,
        onRecordAudioClick: () -> Unit,
        onLiveFeedbackClick: () -> Unit,
        onDetectClick: (String, Uri) -> Unit,
        onPlayRecording: (Uri) -> Unit
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0D1117),
                            Color(0xFF010409)
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(60.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedContent(
                        targetState = appState,
                        label = "FileScreenState",
                        transitionSpec = {
                            fadeIn(animationSpec = tween(300)) togetherWith
                                    fadeOut(animationSpec = tween(300))
                        }
                    ) { state ->
                        when (state) {
                            is AppState.Loading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(8.dp))
                                Text("Loading ${state.fileName}...")
                            }
                            is AppState.Success -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Ready to analyze:", style = MaterialTheme.typography.titleMedium)
                                Text(state.fileName, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(16.dp))
                                Row {
                                    Button(onClick = { onDetectClick(state.fileName, state.fileUri) }) { Text("Detect Emotion") }
                                    Spacer(Modifier.width(16.dp))
                                    Button(onClick = { onPlayRecording(state.fileUri) }) { Text("Replay") }
                                }
                            }
                            is AppState.Detecting -> Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            ) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(8.dp))
                                Text(text = "Detecting emotion in ${state.fileName}...", textAlign = TextAlign.Center)
                            }
                            is AppState.DetectionSuccess -> EmotionResult(vector = state.result)
                            is AppState.Failure -> Text(text = state.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                            else -> {
                                WelcomeSection()
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(50.dp))
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
                    val infiniteTransition = rememberInfiniteTransition(label = "Breathing")
                    val scale by infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = 1.1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1500, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ), label = "scale"
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = onRecordAudioClick,
                            modifier = Modifier
                                .size(72.dp)
                                .scale(scale)
                                .graphicsLayer { shadowElevation = 8.dp.toPx() }
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Mic, "Record Audio", modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                        Text("Record Clip", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    text = "For best results, record for at least 3 seconds but not too long expressing one clear emotion.",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Text(
                    text = "For live emotion detection, you may express multiple emotions, but be sure to record for a longer period of time",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = onOpenFileClick) { Text("Or Open an Audio File") }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
