package com.example.myapplication

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaExtractor
import androidx.compose.material.icons.filled.Podcasts
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.text.color
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.min
import kotlin.math.sqrt
import androidx.navigation.NavHostController
private const val TAG = "ULTRA_DEBUG"
private val EMPTY_CERTIFICATES = emptyList<List<ByteArray>>()

//region Data Classes & Interfaces
sealed interface AppState {
    object Idle : AppState
    object Recording : AppState
    object LiveRecording : AppState
    data class SessionSummary(val emotions: List<MappedEmotion>) : AppState
    data class Success(val fileName: String, val fileUri: Uri) : AppState
    data class PostRecordingReview(val fileName: String, val fileUri: Uri) : AppState
    data class Detecting(val fileName: String, val fileUri: Uri) : AppState
    data class DetectionSuccess(val result: EmotionVector) : AppState
    data class Failure(val message: String) : AppState
}
enum class HistoryItemType { CLIP, SESSION }
data class HistoryItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val permanentPath: String,
    val userConfirmedEmotion: String, // For sessions, this could be the primary detected emotion
    val detectedEmotion: String,      // For sessions, this can be a summary string like "Joy, Anger, Sadness"
    val vector: EmotionVector,        // For sessions, this can be the average vector
    val timestamp: Long = System.currentTimeMillis(),
    val type: HistoryItemType = HistoryItemType.CLIP // Add this field
)
//endregion

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    //region Class Properties
    private var appState: AppState by mutableStateOf(AppState.Idle)

    private val emotionPredictor by lazy { EmotionPredictor(this) }
    private val audioPlayer by lazy { AudioPlayer(this) }
    private val clipRecorder by lazy { WavClipRecorder(this, lifecycleScope) }

    private val audioViewModel: AudioViewModel by viewModels {
        AudioViewModelFactory(applicationContext)
    }

    private val _history = MutableStateFlow<List<HistoryItem>>(emptyList())
    private val history: StateFlow<List<HistoryItem>> = _history
    private var currentRecordingFile: File? = null

    // Launchers must be initialized before the activity is CREATED.
    private lateinit var pickAudioLauncher: ActivityResultLauncher<Intent>
    private lateinit var permissionLauncher: ActivityResultLauncher<String>

    private var tempAudioFile: File? = null
    private var recordingIntention = RecordingIntention.NONE
    private var navigateToLive by mutableStateOf(false)
    private lateinit var navController: NavHostController
    private enum class RecordingIntention { CLIP, LIVE, NONE }
    //endregion

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // CORRECT: Initialize launchers here, before setContent.
        pickAudioLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri -> appState = AppState.Success(getFileName(uri), uri) }
                    ?: run { appState = AppState.Failure("Could not retrieve file URI.") }
            }
        }

        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            Log.d(TAG, "Permission result received: isGranted = $isGranted")
            if (isGranted) {
                when (recordingIntention) {
                    RecordingIntention.CLIP -> {
                        Log.d(TAG, "Permission granted for CLIP, starting recording.")
                        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                        tempAudioFile = File(cacheDir, "REC_${timeStamp}.wav").also { currentRecordingFile = it }
                        tempAudioFile?.let {
                            clipRecorder.start(it)
                            appState = AppState.Recording
                        }
                    }
                    RecordingIntention.LIVE -> {
                        Log.d(TAG, "Permission granted for LIVE. Setting trigger to navigate.")
                        audioViewModel.startRecording() // Assuming viewModel is your AudioViewModel
                        appState = AppState.LiveRecording
                        navController.navigate("live")
                    }
                    else -> {}
                }
            } else {
                Log.d(TAG, "Permission denied.")
                appState = AppState.Failure("Microphone permission is required for this feature.")
            }
            recordingIntention = RecordingIntention.NONE
        }

        setContent {
            MyApplicationTheme {
                navController = rememberNavController()
                val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                val historyItems by history.collectAsState()

                // --- START OF MAJOR FIX ---

                // This effect handles the navigation from the permission callback.
                LaunchedEffect(navigateToLive) {
                    if (navigateToLive) {
                        navController.navigate("live")
                        navigateToLive = false // Reset the trigger
                    }
                }

                // This effect handles the background emotion detection task.
                LaunchedEffect(appState) {
                    val currentState = appState
                    if (currentState is AppState.Detecting) {
                        scope.launch(Dispatchers.IO) {
                            detectEmotion(
                                uri = currentState.fileUri,
                                onSuccess = { vector ->
                                    scope.launch(Dispatchers.Main) {
                                        // When detection succeeds, navigate to the result screen.
                                        appState = AppState.DetectionSuccess(vector)
                                        navController.navigate("result")
                                    }
                                },
                                onFailure = { message ->
                                    scope.launch(Dispatchers.Main) {
                                        // Set the state AND navigate to the new failure screen
                                        appState = AppState.Failure(message)
                                        navController.navigate("failure") {
                                            // Optional: clear the "detecting" screen from the backstack
                                            popUpTo("detecting") { inclusive = true }
                                        }
                                    }
                                }
                            )
                        }
                    }
                }

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet(Modifier.width(280.dp)) {
                            Text("SER Menu", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
                            Divider()
                            NavigationDrawerItem(
                                label = { Text(text = "Home") },
                                icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                                selected = false,
                                onClick = {
                                    navController.navigate("home") {
                                        popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                    scope.launch { drawerState.close() }
                                }
                            )
                            NavigationDrawerItem(
                                label = { Text(text = "History") },
                                icon = { Icon(Icons.Default.History, contentDescription = "History") },
                                selected = false,
                                onClick = { navController.navigate("history"); scope.launch { drawerState.close() } }
                            )
                            NavigationDrawerItem(
                                label = { Text(text = "Emotion Catalog") },
                                icon = { Icon(Icons.Default.Book, contentDescription = "Emotion Catalog") },
                                selected = false,
                                onClick = { navController.navigate("catalog"); scope.launch { drawerState.close() } }
                            )
                        }
                    }
                ) {
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = { Text("Speech Emotion Recognition") },
                                navigationIcon = { IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, "Menu") } }
                            )
                        }
                    ) { paddingValues ->
                        // The NavHost is now the direct child of the Scaffold.
                        // MainContent is GONE from here.
                        NavHost(navController = navController, startDestination = "home", modifier = Modifier.padding(paddingValues)) {

                            // "home" route now contains ONLY the Welcome and Recording screens, controlled by AppState.
                            composable("home") {
                                // This effect resets the state when you return to the home screen.
                                LaunchedEffect(Unit) {
                                    if (appState !is AppState.Idle && appState !is AppState.Recording) {
                                        appState = AppState.Idle
                                    }
                                }

                                AnimatedContent(targetState = appState, label = "HomeAnimation") { state ->
                                    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                                        when (state) {
                                            is AppState.Idle -> WelcomeScreen(
                                                onRecordAudioClick = {
                                                    recordingIntention = RecordingIntention.CLIP
                                                    permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                                },
                                                onOpenFileClick = {
                                                    pickAudioLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply { type = "audio/*" })
                                                },
                                                onLiveFeedbackClick = {
                                                    clipRecorder.stop()
                                                    recordingIntention = RecordingIntention.LIVE
                                                    permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                                }
                                            )
                                            is AppState.Recording -> {
                                                val amp by clipRecorder.amplitude.collectAsState()
                                                RecordingScreen(amp) {
                                                    clipRecorder.stop()
                                                    tempAudioFile?.let {
                                                        // Navigate to the review screen after recording
                                                        appState = AppState.PostRecordingReview(it.name, Uri.fromFile(it))
                                                        navController.navigate("review")
                                                    }
                                                }
                                            }
                                            is AppState.LiveRecording -> {
                                                WelcomeScreen({}, {}, {})
                                            }
                                            is AppState.Success -> {
                                                // Handle file picked successfully, move to review
                                                LaunchedEffect(Unit) {
                                                    appState = AppState.PostRecordingReview(state.fileName, state.fileUri)
                                                    navController.navigate("review")
                                                }
                                            }

                                            is AppState.SessionSummary -> {
                                                WelcomeScreen({}, {}, {})
                                            }
                                            else -> {
                                                // Fallback for other states on the home route, show a loader or idle.
                                                WelcomeScreen({}, {}, {})
                                            }
                                        }
                                    }
                                }
                            }

                            // NEW: "review" route for PostRecordingReviewScreen
                            composable("review") {
                                val state = appState
                                if (state is AppState.PostRecordingReview) {
                                    PostRecordingScreen(
                                        appState = state,
                                        onDetectClick = { _, uri ->
                                            appState = AppState.Detecting(state.fileName, uri)
                                            navController.navigate("detecting") }, // This will trigger the LaunchedEffect
                                        onPlayClick = { audioPlayer.play(state.fileUri) },
                                        onReRecordClick = {
                                            // Go back to home to re-record
                                            navController.popBackStack()
                                            appState = AppState.Idle
                                            // Immediately trigger recording
                                            recordingIntention = RecordingIntention.CLIP
                                            permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                        }
                                    )
                                }
                            }
                            composable("detecting") {
                                val state = appState
                                if (state is AppState.Detecting) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black)
                                            .padding(16.dp)
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            CircularProgressIndicator(color = Color.White)
                                            Spacer(Modifier.height(16.dp))
                                            Text(
                                                text = "Detecting emotion in\n${state.fileName}...",
                                                color = Color.White,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }
                            composable("failure") {
                                val state = appState
                                if (state is AppState.Failure) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black) // Ensure background is black
                                            .padding(16.dp)
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            // Use a visible error icon
                                            Icon(
                                                Icons.Default.ErrorOutline,
                                                contentDescription = "Error",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(48.dp)
                                            )
                                            Spacer(Modifier.height(16.dp))
                                            Text(
                                                text = state.message,
                                                color = MaterialTheme.colorScheme.error, // Use theme color
                                                textAlign = TextAlign.Center,
                                                style = MaterialTheme.typography.bodyLarge // Make it readable
                                            )
                                            Spacer(Modifier.height(24.dp))
                                            Button(onClick = {
                                                // Navigate home cleanly
                                                navController.navigate("home") {
                                                    popUpTo(navController.graph.findStartDestination().id) {
                                                        inclusive = true
                                                    }
                                                }
                                            }) {
                                                Text("Return Home")
                                            }
                                        }
                                    }
                                }
                            }

                            // NEW: "result" route for the EmotionResult screen
                            composable("result") {
                                val state = appState
                                if (state is AppState.DetectionSuccess) {
                                    EmotionResult(
                                        vector = state.result,
                                        onSaveAndExit = { finalEmotion, detectedEmotion, vector ->
                                            saveHistoryItem(finalEmotion, detectedEmotion, vector)
                                            // Navigate home properly
                                            navController.navigate("home") {
                                                popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                                            }
                                        }
                                    )
                                }
                            }

                            composable("live") {
                                LiveRecordingScreen(
                                    viewModel = audioViewModel,
                                    onStop = {
                                        // This logic is now simpler. We let the summary screen handle the file.
                                        audioViewModel.stopRecording()
                                        val summary = audioViewModel.emotionHistory.value
                                        appState = AppState.SessionSummary(summary)
                                        navController.navigate("summary") {
                                            popUpTo("live") { inclusive = true }
                                        }
                                    }
                                )
                            }
                            composable("summary") {
                                val state = appState
                                val sessionFile = remember { audioViewModel.getSessionFile() }
                                if (state is AppState.SessionSummary) {
                                    SessionSummaryScreen(
                                        emotions = state.emotions,
                                        file = sessionFile,
                                        onReturnHome = {
                                            navController.navigate("home") {
                                                popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                                            }
                                        },
                                        onReplay = {
                                            sessionFile?.let { audioPlayer.play(Uri.fromFile(it)) }
                                        },
                                        // Add a save function here
                                        onSaveSession = { primaryEmotion, summary, averageVector, file ->
                                            saveHistoryItem( // Assuming you have a saveHistoryItem function
                                                finalEmotion = primaryEmotion,
                                                detectedEmotion = summary,
                                                vector = averageVector,
                                                type = HistoryItemType.SESSION,
                                                fileToSave = file
                                            )
                                        }
                                    )
                                }
                            }
                            composable("history") {
                                HistoryScreen(
                                    historyItems = historyItems,
                                    onPlayItem = { path -> audioPlayer.play(Uri.fromFile(File(path))) },
                                    onNavigateUp = { navController.popBackStack() },
                                    onRenameItem = { id, newTitle -> updateHistoryItemTitle(id, newTitle) } // <<< PASS THE FUNCTION
                                )
                            }
                            composable("catalog") {
                                EmotionCatalogScreen { navController.popBackStack() }
                            }
                        }
                    }
                }
            }
        }
    }

    //region Core Logic
    @SuppressLint("Range")
    private fun getFileName(uri: Uri): String = contentResolver.query(uri, null, null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(it.getColumnIndex(OpenableColumns.DISPLAY_NAME)) else "Unknown File" } ?: "Unknown File"

    private fun isSpeechDetected(audioData: ShortArray, threshold: Float): Boolean {
        if (audioData.isEmpty()) return false
        val rms = sqrt(audioData.map { it.toDouble() * it.toDouble() }.average())
        return (rms / 32767.0f) > threshold
    }

    // In detectEmotion() function
    private suspend fun detectEmotion(uri: Uri, onSuccess: (EmotionVector) -> Unit, onFailure: (String) -> Unit) {
        try {
            val shortArray = withContext(Dispatchers.IO) { decodeAudioFileToPcmShortArray(uri, contentResolver) }
            // LOWERED THE REQUIREMENT TO 1 SECOND (16000 SAMPLES)
            if (shortArray == null || shortArray.size < 16000 || !isSpeechDetected(shortArray, 0.01f)) {
                onFailure("Clip is too short or no speech was detected.")
                return
            }

            val processedAudio = preprocessAudioForModel(shortArray)
            val prediction = emotionPredictor.predict(processedAudio) ?: throw Exception("Prediction failed.")
            val vector = EmotionVector(arousal = prediction[0], valence = prediction[2], dominance = prediction[1])
            onSuccess(vector)
        } catch (e: Exception) {
            Log.e(TAG, "Detection failed for URI", e)
            onFailure(e.message ?: "An unknown error occurred during detection.")
        }
    }
    private fun updateHistoryItemTitle(itemId: String, newTitle: String) {
        // Find the item, create a copy with the new title, and update the list
        val updatedList = _history.value.map {
            if (it.id == itemId) {
                it.copy(title = newTitle)
            } else {
                it
            }
        }
        _history.value = updatedList
        Log.d(TAG, "Updated title for item $itemId to '$newTitle'")
    }

    private fun decodeAudioFileToPcmShortArray(uri: Uri, contentResolver: android.content.ContentResolver): ShortArray? {
        val extractor = MediaExtractor(); var decoder: MediaCodec? = null; var pfd: android.os.ParcelFileDescriptor? = null
        return try {
            pfd = contentResolver.openFileDescriptor(uri, "r") ?: return null; extractor.setDataSource(pfd.fileDescriptor)
            val trackIndex = (0 until extractor.trackCount).indexOfFirst { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            if (trackIndex == -1) return null; val format = extractor.getTrackFormat(trackIndex); extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            decoder = MediaCodec.createDecoderByType(mime).apply { configure(format, null, null, 0); start() }
            val bufferInfo = MediaCodec.BufferInfo(); val decodedData = mutableListOf<Short>(); var isEndOfStream = false
            while (!isEndOfStream) {
                val inputBufferIndex = decoder.dequeueInputBuffer(10000)
                if (inputBufferIndex >= 0) {
                    val sampleSize = extractor.readSampleData(decoder.getInputBuffer(inputBufferIndex)!!, 0)
                    if (sampleSize < 0) { decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); isEndOfStream = true
                    } else { decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, extractor.sampleTime, 0); extractor.advance() }
                }
                var outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                while (outputBufferIndex >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) isEndOfStream = true
                    if (bufferInfo.size > 0) {
                        val shortBuffer = decoder.getOutputBuffer(outputBufferIndex)!!.asShortBuffer()
                        val shorts = ShortArray(shortBuffer.remaining()); shortBuffer.get(shorts); decodedData.addAll(shorts.toList())
                    }
                    decoder.releaseOutputBuffer(outputBufferIndex, false); outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                }
            }
            decodedData.toShortArray()
        } catch (e: Exception) { Log.e(TAG, "decodeAudioFileToPcmShortArray failed", e); null }
        finally { try { pfd?.close(); extractor.release(); decoder?.stop(); decoder?.release() } catch (e: Exception) {} }
    }

    private fun saveHistoryItem(
        finalEmotion: String,
        detectedEmotion: String,
        vector: EmotionVector,
        type: HistoryItemType = HistoryItemType.CLIP,
        fileToSave: File? = null // <-- Add this new parameter
    ) {
        val sourceFile = fileToSave ?: currentRecordingFile ?: run {
            Log.e(TAG, "saveHistoryItem: No file provided to save!")
            return
        }

        val historyDir = File(filesDir, "history").apply { if (!exists()) mkdirs() }
        val destinationFile = File(historyDir, sourceFile.name)
        sourceFile.copyTo(destinationFile, overwrite = true)

        _history.value = _history.value + HistoryItem(
            title = sourceFile.name, // <<< USE THE FILENAME AS THE INITIAL TITLE
            permanentPath = destinationFile.absolutePath,
            userConfirmedEmotion = finalEmotion,
            detectedEmotion = detectedEmotion,
            vector = vector,
            type = type
        )
        Log.d(TAG, "History item saved: ${destinationFile.absolutePath}")
        sourceFile.delete()
        if (type == HistoryItemType.CLIP) {
            currentRecordingFile = null
            tempAudioFile = null
        }
    }
    private fun preprocessAudioForModel(shortArray: ShortArray): FloatArray {
        // DO NOT pad or truncate here. The model expects the raw audio signal.
        // This was the source of the "Neutral" bug.
        return FloatArray(shortArray.size) { i ->
            shortArray[i] / 32768.0f // Normalize to [-1.0, 1.0]
        }
    }
    //endregion

    //region UI Composables

    @Composable
    fun WelcomeScreen(onRecordAudioClick: () -> Unit, onOpenFileClick: () -> Unit, onLiveFeedbackClick: () -> Unit) {
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.weight(0.8f)); WelcomeSection(); Spacer(modifier = Modifier.weight(1f))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onLiveFeedbackClick, modifier = Modifier.height(80.dp)) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Outlined.Analytics, "Live Feedback"); Text("Live") } }
                val infiniteTransition = rememberInfiniteTransition(label = "Breathing"); val scale by infiniteTransition.animateFloat(1f, 1.1f, infiniteRepeatable(tween(1500), RepeatMode.Reverse), label = "scale")
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = onRecordAudioClick, modifier = Modifier
                        .size(72.dp)
                        .graphicsLayer {
                            scaleX = scale; scaleY = scale; shadowElevation = 8.dp.toPx()
                        }) {
                        Box(modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) { Icon(Icons.Default.Mic, "Record Audio", modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimary) }
                    }
                    Text("Record Clip", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                }
            }
            Spacer(modifier = Modifier.height(24.dp)); Button(onClick = onOpenFileClick) { Text("Or Open an Audio File") }; Spacer(modifier = Modifier.height(24.dp))
        }
    }

    @Composable
    fun SessionSummaryScreen(emotions: List<MappedEmotion>, onReturnHome: () -> Unit) {
        val summary = emotions.groupingBy { it.label }.eachCount()
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Session Summary", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Spacer(Modifier.height(16.dp))
            if (summary.isEmpty()) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text("No emotions were detected during the session.", color = Color.White, textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(summary.entries.toList()) { (label, count) ->
                        val emotion = createMappedEmotion(label)
                        Row(modifier = Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, color = emotion.color, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            Text("$count", color = Color.White, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onReturnHome) { Text("Return to Home") }
        }
    }

    @Composable
    fun RecordingScreen(amplitude: Float, onStopRecording: () -> Unit) {
        val amplitudes = remember { mutableStateListOf<Float>() }; LaunchedEffect(amplitude) { amplitudes.add((amplitude * 4.0f).coerceIn(0f, 1f)); if (amplitudes.size > 150) amplitudes.removeAt(0) }
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Spacer(modifier = Modifier.weight(1f)); Text("Recording...", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color.White); Spacer(modifier = Modifier.height(16.dp))
            ScrollingWaveform(amplitudes, Modifier
                .fillMaxWidth()
                .height(100.dp)
                .padding(horizontal = 16.dp)); Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onStopRecording, modifier = Modifier
                .size(72.dp)
                .padding(bottom = 32.dp)) { Icon(Icons.Default.Stop, "Stop Recording", tint = Color.Red, modifier = Modifier.fillMaxSize()) }
        }
    }

    @Composable
    fun PostRecordingScreen(appState: AppState.PostRecordingReview, onDetectClick: (String, Uri) -> Unit, onPlayClick: () -> Unit, onReRecordClick: () -> Unit) {
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
            Text("Recording Complete", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White); Text(appState.fileName, color = Color.LightGray); Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onPlayClick) { Icon(Icons.Default.PlayArrow, "Play"); Spacer(Modifier.width(8.dp)); Text("Replay") }
                Button(onClick = onReRecordClick, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)) { Icon(Icons.Default.Refresh, "Re-record"); Spacer(Modifier.width(8.dp)); Text("Re-record") }
            }
            Button(onClick = { onDetectClick(appState.fileName, appState.fileUri) }) { Text("Detect Emotion From This Recording") }
        }
    }

    @Composable
    fun EmotionResult(vector: EmotionVector, onSaveAndExit: (finalEmotion: String, detectedEmotion: String, vector: EmotionVector) -> Unit) {
        val mappedEmotion = mapVectorToEmotion(vector)
        val synonyms = remember { (emotionSynonyms[mappedEmotion.label] ?: emptyList()) + mappedEmotion.label }
        val allEmotionsForSearch = remember { emotionSynonyms.flatMap { it.value + it.key }.distinct().sorted() }

        var selectedEmotion by remember { mutableStateOf(mappedEmotion.label) }
        var showCustomField by remember { mutableStateOf(false) }
        var customEmotionText by remember { mutableStateOf("") }
        var isDropdownExpanded by remember { mutableStateOf(false) }
        val keyboardController = LocalSoftwareKeyboardController.current

        Column(modifier = Modifier
            .fillMaxSize()
            .padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(mappedEmotion.label, fontSize = 48.sp, fontWeight = FontWeight.Bold, color = mappedEmotion.color, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text("Description: ${mappedEmotion.description}", fontSize=16.sp, color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text("Valence: ${"%.4f".format(vector.valence)}", fontFamily = FontFamily.Monospace, color = Color.Gray, fontSize = 12.sp)
            Text("Arousal: ${"%.4f".format(vector.arousal)}", fontFamily = FontFamily.Monospace, color = Color.Gray, fontSize = 12.sp)
            Text("Dominance: ${"%.4f".format(vector.dominance)}", fontFamily = FontFamily.Monospace, color = Color.Gray, fontSize = 12.sp)
            Spacer(Modifier.height(24.dp))
            Text("Confirm or change the detected emotion:", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Spacer(Modifier.height(8.dp))

            ExposedDropdownMenuBox(expanded = isDropdownExpanded && !showCustomField, onExpandedChange = { isDropdownExpanded = !isDropdownExpanded }) {
                OutlinedTextField(
                    readOnly = true,
                    value = selectedEmotion,
                    onValueChange = {},
                    label = { Text("Selected Emotion") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isDropdownExpanded && !showCustomField) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = isDropdownExpanded && !showCustomField, onDismissRequest = { isDropdownExpanded = false }) {
                    synonyms.forEach { synonym ->
                        DropdownMenuItem(text = { Text(synonym) }, onClick = { selectedEmotion = synonym; isDropdownExpanded = false })
                    }
                    DropdownMenuItem(text = { Text("Other...", fontStyle = FontStyle.Italic) }, onClick = { showCustomField = true; selectedEmotion = "Other..."; isDropdownExpanded = false })
                }
            }

            AnimatedVisibility(visible = showCustomField) {
                var searchSuggestions by remember { mutableStateOf<List<String>>(emptyList()) }
                LaunchedEffect(customEmotionText) {
                    searchSuggestions = if (customEmotionText.isNotBlank()) {
                        allEmotionsForSearch.filter { it.contains(customEmotionText, ignoreCase = true) }
                    } else { emptyList() }
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = customEmotionText,
                        onValueChange = { customEmotionText = it.filter { c -> c != '\n' } },
                        label = { Text("Search or add new emotion") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() })
                    )
                    LazyColumn(modifier = Modifier.heightIn(max = 150.dp)) {
                        items(searchSuggestions) { suggestion ->
                            Text(suggestion, modifier = Modifier
                                .fillMaxWidth()
                                .clickable { customEmotionText = suggestion }
                                .padding(12.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    val finalEmotion = if (showCustomField) customEmotionText.trim() else selectedEmotion
                    onSaveAndExit(finalEmotion, mappedEmotion.label, vector)
                },
                enabled = if (showCustomField) customEmotionText.isNotBlank() else true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) { Text("Accept & Save") }
        }
    }
// In MainActivity.kt, replace the entire HistoryScreen function

    @Composable
    fun HistoryScreen(
        historyItems: List<HistoryItem>,onPlayItem: (path: String) -> Unit,
        onNavigateUp: () -> Unit,
        onRenameItem: (id: String, newTitle: String) -> Unit // <<< This is the correct signature
    ) {
        // State for managing which item's rename dialog is open.
        // This needs to be at the top level of the screen composable.
        var showRenameDialogFor by remember { mutableStateOf<HistoryItem?>(null) }

        if (historyItems.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No recordings saved yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White
                )
            }
        } else {
            // The LazyColumn displays the items
            LazyColumn(contentPadding = PaddingValues(16.dp)) {
                item {
                    Text(
                        "Session History",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                items(historyItems.sortedByDescending { it.timestamp }) { item ->
                    ElevatedCard(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val icon = if (item.type == HistoryItemType.CLIP) Icons.Default.Mic else Icons.Default.Podcasts
                            Icon(
                                imageVector = icon,
                                contentDescription = item.type.name,
                                modifier = Modifier.padding(end = 16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                // Display the title
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                                // Your existing logic for clip vs session display
                                if (item.type == HistoryItemType.CLIP) {
                                    Text("True Emotion: ${item.userConfirmedEmotion}", style = MaterialTheme.typography.bodyLarge)
                                    Text("Detected: ${item.detectedEmotion}", style = MaterialTheme.typography.titleSmall, fontStyle = FontStyle.Italic, color = createMappedEmotion(item.detectedEmotion).color)
                                } else {
                                    Text("Primary Emotion: ${item.userConfirmedEmotion}", style = MaterialTheme.typography.bodyLarge, color = createMappedEmotion(item.userConfirmedEmotion).color)
                                }
                                Text(
                                    SimpleDateFormat("MMM dd, yyyy, hh:mm a", Locale.getDefault()).format(Date(item.timestamp)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // Rename Icon
                            IconButton(onClick = { showRenameDialogFor = item }) {
                                Icon(Icons.Default.Edit, "Rename Recording", tint = Color.Gray)
                            }
                            // Play Icon
                            IconButton(onClick = { onPlayItem(item.permanentPath) }) {
                                Icon(Icons.Default.PlayArrow, "Play Recording", modifier = Modifier.size(36.dp))
                            }
                        }
                    }
                }
            }

            // The AlertDialog is now correctly placed at the end of the screen's content.
            // It observes the 'showRenameDialogFor' state.
            showRenameDialogFor?.let { itemToRename ->
                var newName by remember { mutableStateOf(itemToRename.title) }
                AlertDialog(
                    onDismissRequest = { showRenameDialogFor = null },
                    title = { Text("Rename Recording") },
                    text = {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text("New Name") },
                            singleLine = true
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (newName.isNotBlank()) {
                                    onRenameItem(itemToRename.id, newName.trim())
                                }
                                showRenameDialogFor = null
                            },
                            enabled = newName.isNotBlank()
                        ) { Text("Save") }
                    },
                    dismissButton = {
                        Button(onClick = { showRenameDialogFor = null }) { Text("Cancel") }
                    }
                )
            }
        }
    }


    @Composable
    fun EmotionCatalogScreen(onNavigateUp: () -> Unit) {
        val catalog = listOf("Joy", "Anger", "Sadness", "Fear", "Disgust", "Surprise", "Neutral")
        LazyColumn(contentPadding = PaddingValues(16.dp)) {
            item {
                Text("The VAD Model", style = MaterialTheme.typography.headlineSmall, color = Color.White, modifier = Modifier.padding(bottom = 8.dp))
                Text("This app maps speech to a 3D emotional space: Valence (pleasure/displeasure), Arousal (energy/lethargy), and Dominance (control/submission). Below are the core emotions and their typical VAD profiles.", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(bottom = 16.dp))
            }
            items(catalog) { emotionLabel ->
                val emotion = createMappedEmotion(emotionLabel)
                val description = when(emotionLabel) {
                    "Joy" -> "High Valence, High Arousal. The feeling of great pleasure and happiness."
                    "Anger" -> "Low Valence, High Arousal, High Dominance. A strong feeling of annoyance or hostility."
                    "Sadness" -> "Low Valence, Low Arousal. The feeling of sorrow, typically caused by loss."
                    "Fear" -> "Low Valence, High Arousal, Low Dominance. An unpleasant emotion caused by the belief that someone or something is dangerous."
                    "Disgust" -> "Low Valence, Low Arousal, High Dominance. A feeling of revulsion or profound disapproval."
                    "Surprise" -> "High Arousal. A brief state experienced as the result of an unexpected event."
                    "Neutral" -> "Centered VAD values. A lack of strong emotional content."
                    else -> "A complex emotional state."
                }
                Column(modifier = Modifier.padding(vertical = 12.dp)) {
                    Text(emotion.label, style = MaterialTheme.typography.titleLarge, color = emotion.color, fontWeight = FontWeight.Bold)
                    Text(description, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.8f))
                }
            }
        }
    }

    @Composable
    fun LiveRecordingScreen(viewModel: AudioViewModel, onStop: () -> Unit) {
        // Collect the necessary states from the ViewModel
        val amplitudes by viewModel.amplitudeFlow.collectAsState()
        val latestEmotion by viewModel.latestEmotion.collectAsState()

        val statusText = latestEmotion?.label ?: "Listening..."
        val statusColor = latestEmotion?.let { createMappedEmotion(it.label).color } ?: Color.White

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.weight(1f))

            // Animated text for the emotion
            AnimatedContent(
                targetState = Pair(statusText, statusColor),
                label = "StatusTextAnimation"
            ) { (text, color) ->
                Text(
                    text = text,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = color,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Your existing waveform looks great for this
            ScrollingWaveform(
                amplitudes = amplitudes,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.weight(0.8f))

            // Stop Button
            IconButton(
                onClick = onStop,
                modifier = Modifier
                    .size(72.dp)
                    .padding(bottom = 32.dp)
            ) {
                Icon(
                    Icons.Default.Stop,
                    contentDescription = "Stop Recording",
                    tint = Color.Red,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(modifier = Modifier.weight(0.2f))
        }
    }

    // This is the new SessionSummaryScreen you already had, but we'll add saving
    @Composable
    fun SessionSummaryScreen(
        emotions: List<MappedEmotion>,
        file: File?,
        onReturnHome: () -> Unit,
        onReplay: () -> Unit,
        onSaveSession: (primaryEmotion: String, summary: String, averageVector: EmotionVector, file: File?) -> Unit
    ) {
        val summary = emotions.groupingBy { it.label }.eachCount()
        val totalEmotions = emotions.size.toFloat().coerceAtLeast(1f)
        val primaryEmotion = summary.maxByOrNull { it.value }?.key ?: "Neutral"

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Session Summary", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text("Primary Emotion: $primaryEmotion", style = MaterialTheme.typography.titleLarge, color = createMappedEmotion(primaryEmotion).color)
            Spacer(Modifier.height(16.dp))

            if (summary.isEmpty()) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text("No emotions were detected during the session.", color = Color.White, textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(summary.entries.toList().sortedByDescending { it.value }) { (label, count) ->
                        val percentage = (count / totalEmotions) * 100
                        val emotion = createMappedEmotion(label)
                        Row(
                            modifier = Modifier.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, color = emotion.color, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            Text("${"%.1f".format(percentage)}%", color = Color.White, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // New "Replay" button
                Button(onClick = onReplay) { Text("Replay") }
                Button(onClick = onReturnHome) { Text("Discard") }
                Button(onClick = {
                    val summaryString = summary.keys.joinToString(", ")
                    val avgVector = EmotionVector(0f, 0f, 0f) // Placeholder
                    onSaveSession(primaryEmotion, summaryString, avgVector, file) // Pass the file here
                    onReturnHome()
                }) { Text("Save & Exit") }
            }
        }
    }

    @Composable
    fun WelcomeSection() {
        val provider = remember { GoogleFont.Provider("com.google.android.gms.fonts", "com.google.android.gms", EMPTY_CERTIFICATES) }
        val fontFamily = remember { FontFamily(Font(GoogleFont("Montserrat"), provider)) }
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { visible = true }
        AnimatedVisibility(visible, enter = slideInVertically { it / 2 } + fadeIn()) {
            Column(modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("SER", fontFamily = fontFamily, fontWeight = FontWeight.Bold, fontSize = 80.sp, color = Color.White)
                Text("Speech Emotion Recognition", fontFamily = fontFamily, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary, letterSpacing = 2.sp, modifier = Modifier.padding(bottom = 24.dp))
                Text("For best results, express one clear emotion. Clips should be 2-6 seconds. Live feedback can be longer. SER can make mistakes, your confirmation helps!", textAlign = TextAlign.Center, fontFamily = fontFamily, color = Color.White.copy(alpha = 0.8f), lineHeight = 24.sp, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }

    @Composable
    fun ScrollingWaveform(amplitudes: List<Float>, modifier: Modifier = Modifier, waveColor: Color = MaterialTheme.colorScheme.primary) {
        Canvas(modifier = modifier) {
            val canvasWidth = size.width; val canvasHeight = size.height; val middleY = canvasHeight / 2
            val barWidth = if (amplitudes.isNotEmpty()) canvasWidth / amplitudes.size else 0f
            amplitudes.forEachIndexed { index, amplitude ->
                val x = index * barWidth; val barHeight = amplitude * canvasHeight
                drawLine(color = waveColor, start = Offset(x, middleY - barHeight / 2), end = Offset(x, middleY + barHeight / 2), strokeWidth = 4f)
            }
        }
    }
    //endregion
}

