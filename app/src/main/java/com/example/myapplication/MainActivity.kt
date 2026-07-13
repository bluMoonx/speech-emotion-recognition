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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.geometry.Size
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
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
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
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.sqrt

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
    val userConfirmedEmotion: String,
    val detectedEmotion: String,
    val vector: EmotionVector,
    val sessionMap: List<MappedEmotion> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val type: HistoryItemType
)
//endregion

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    //region Class Properties
    private var appState: AppState by mutableStateOf(AppState.Idle)
    private val PREFS_NAME = "ser_prefs"
    private val KEY_ONBOARDING_DONE = "onboarding_completed"
    private val emotionPredictor by lazy { EmotionPredictor(this) }
    private val liveAudioProcessor by lazy { LiveAudioProcessor(this, lifecycleScope, emotionPredictor) }
    private val audioPlayer by lazy { AudioPlayer(this) }
    private val clipRecorder by lazy { WavClipRecorder(this, lifecycleScope) }

    private val _history = MutableStateFlow<List<HistoryItem>>(emptyList())
    private val history: StateFlow<List<HistoryItem>> = _history
    private var currentRecordingFile: File? = null

    private lateinit var pickAudioLauncher: ActivityResultLauncher<Intent>
    private lateinit var permissionLauncher: ActivityResultLauncher<String>

    private var tempAudioFile: File? = null
    private var recordingIntention = RecordingIntention.NONE
    private lateinit var navController: NavHostController
    private enum class RecordingIntention { CLIP, LIVE, NONE }
    //endregion
    private fun saveHistoryToDisk(items: List<HistoryItem>) {
        lifecycleScope.launch(Dispatchers.IO) {
            val json = Gson().toJson(items)
            File(filesDir, "history_metadata.json").writeText(json)
        }
    }

    private fun loadHistoryFromDisk() {
        val file = File(filesDir, "history_metadata.json")
        if (file.exists()) {
            try {
                val json = file.readText()
                val type = object : TypeToken<List<HistoryItem>>() {}.type
                val loadedItems: List<HistoryItem> = Gson().fromJson(json, type)
                _history.value = loadedItems
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load history", e)
            }
        }
    }
    private fun deleteHistoryItem(item: HistoryItem) {
        // 1. Delete the actual audio file
        val file = File(item.permanentPath)
        if (file.exists()) file.delete()

        // 2. Update the list and save metadata
        val updatedList = _history.value.filter { it.id != item.id }
        _history.value = updatedList
        saveHistoryToDisk(updatedList)
    }

    private fun clearAllHistory() {
        _history.value.forEach { item -> File(item.permanentPath).delete() }
        _history.value = emptyList()
        saveHistoryToDisk(emptyList())
    }

    private fun exportHistoryAsText(): String {
        val sb = StringBuilder()
        sb.append("SER APP - EMOTION HISTORY EXPORT\n")
        sb.append("Generated on: ${Date()}\n\n")
        _history.value.sortedByDescending { it.timestamp }.forEach { item ->
            sb.append("Title: ${item.title}\n")
            sb.append("Type: ${item.type}\n")
            sb.append("Detected: ${item.detectedEmotion}\n")
            sb.append("Confirmed: ${item.userConfirmedEmotion}\n")
            sb.append("VAD: V:${item.vector.valence}, A:${item.vector.arousal}, D:${item.vector.dominance}\n")
            sb.append("Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(item.timestamp))}\n")
            sb.append("-----------------------------------\n")
        }
        return sb.toString()
    }

    private fun shareHistory() {
        val exportData = exportHistoryAsText()
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, exportData)
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, "Share Emotion History")
        startActivity(shareIntent)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadHistoryFromDisk()

        pickAudioLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri -> val tempFile = File(cacheDir, getFileName(uri))
                    contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    currentRecordingFile = tempFile
                    appState = AppState.Success(getFileName(uri), uri) }
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
                        Log.d(TAG, "Permission granted for LIVE. Navigating to live screen.")
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

                LaunchedEffect(appState) {
                    val currentState = appState
                    if (currentState is AppState.Detecting) {
                        scope.launch(Dispatchers.IO) {
                            detectEmotion(
                                uri = currentState.fileUri,
                                onSuccess = { vector ->
                                    scope.launch(Dispatchers.Main) {
                                        appState = AppState.DetectionSuccess(vector)
                                        navController.navigate("result")
                                    }
                                },
                                onFailure = { message ->
                                    scope.launch(Dispatchers.Main) {
                                        appState = AppState.Failure(message)
                                        navController.navigate("failure") {
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
                            NavigationDrawerItem(
                                label = { Text(text = "Model Information") },    icon = { Icon(Icons.Default.Memory, contentDescription = "Model") }, // Memory icon looks like a chip/model
                                selected = false,
                                onClick = {
                                    navController.navigate("model_info")
                                    scope.launch { drawerState.close() }
                                }
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
                        NavHost(navController = navController, startDestination = "home", modifier = Modifier.padding(paddingValues)) {
                            composable("home") {
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
                                                        appState = AppState.PostRecordingReview(it.name, Uri.fromFile(it))
                                                        navController.navigate("review")
                                                    }
                                                }
                                            }
                                            is AppState.Success -> {
                                                LaunchedEffect(Unit) {
                                                    appState = AppState.PostRecordingReview(state.fileName, state.fileUri)
                                                    navController.navigate("review")
                                                }
                                            }
                                            else -> WelcomeScreen({}, {}, {})
                                        }
                                    }
                                }
                            }

                            composable("review") {
                                DisposableEffect(Unit) { onDispose { audioPlayer.stop() } }
                                val state = appState
                                if (state is AppState.PostRecordingReview) {
                                    PostRecordingScreen(
                                        appState = state,
                                        onDetectClick = { _, uri ->
                                            appState = AppState.Detecting(state.fileName, uri)
                                            navController.navigate("detecting")
                                        },
                                        onPlayClick = { audioPlayer.play(state.fileUri) },
                                        onReRecordClick = {
                                            navController.popBackStack()
                                            appState = AppState.Idle
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
                                            .background(Color.Black)
                                            .padding(16.dp)
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(Icons.Default.ErrorOutline, "Error", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                                            Spacer(Modifier.height(16.dp))
                                            Text(
                                                text = state.message,
                                                color = MaterialTheme.colorScheme.error,
                                                textAlign = TextAlign.Center,
                                                style = MaterialTheme.typography.bodyLarge
                                            )
                                            Spacer(Modifier.height(24.dp))
                                            Button(onClick = {
                                                navController.navigate("home") {
                                                    popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                                                }
                                            }) { Text("Return Home") }
                                        }
                                    }
                                }
                            }

                            composable("result") {
                                val state = appState
                                if (state is AppState.DetectionSuccess) {
                                    EmotionResult(
                                        vector = state.result,
                                        onSaveAndExit = { finalEmotion, detectedEmotion, vector ->
                                            saveHistoryItem(
                                                finalEmotion = finalEmotion,
                                                detectedEmotion = detectedEmotion,
                                                vector = vector,
                                                type = HistoryItemType.CLIP,
                                                fileToSave = currentRecordingFile // Pass the correct file for clips
                                            )
                                            navController.navigate("home") {
                                                popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                                            }
                                        }
                                    )
                                }
                            }

                            composable("live") {
                                LiveRecordingScreen( // The function defined in LiveFeedbackScreen.kt
                                    processor = liveAudioProcessor,
                                    onStop = {
                                        liveAudioProcessor.stopRecording()
                                        val summary = liveAudioProcessor.sessionEmotions.value
                                        appState = AppState.SessionSummary(summary)
                                        navController.navigate("summary") {
                                            popUpTo("live") { inclusive = true }
                                        }
                                    }
                                )
                            }


                            composable("summary") {
                                DisposableEffect(Unit) { onDispose { audioPlayer.stop() } }
                                val state = appState
                                val sessionFile = remember { liveAudioProcessor.getSessionFile() }
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
                                        onSaveSession = { primaryEmotion, summary, averageVector, file ->
                                            saveHistoryItem(
                                                finalEmotion = primaryEmotion,
                                                detectedEmotion = summary,
                                                vector = averageVector,
                                                type = HistoryItemType.SESSION,
                                                fileToSave = file,
                                                sessionMap = state.emotions // FIX: Pass the session emotions here
                                            )
                                        }
                                    )
                                }
                            }

                            composable("history") {
                                val historyItems by history.collectAsState()
                                HistoryScreen(historyItems = historyItems,
                                    onPlayItem = { path -> audioPlayer.play(Uri.fromFile(File(path))) },
                                    onStopPlayer = { audioPlayer.stop() },
                                    onNavigateUp = { navController.popBackStack() },
                                    onRenameItem = { id, newTitle -> updateHistoryItemTitle(id, newTitle) },
                                    onDeleteItem = { item -> deleteHistoryItem(item) },
                                    onClearAll = { clearAllHistory() },
                                    onShare = { shareHistory() },
                                    onDownload = {
                                        // Save to Downloads folder logic
                                        val data = exportHistoryAsText()
                                        val file = File(getExternalFilesDir(null), "SER_Export_${System.currentTimeMillis()}.txt")
                                        file.writeText(data)
                                        // Show a toast or snackbar "Saved to ${file.absolutePath}"
                                    }
                                )
                            }


                            composable("catalog") {
                                EmotionCatalogScreen { navController.popBackStack() }
                            }
                            composable("model_info") {
                                ModelInfoScreen { navController.popBackStack() }
                            }
                        }
                    }
                }
            }
            var showPrivacyDisclosure by remember { mutableStateOf(!getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_ONBOARDING_DONE, false)) }
            var showOnboarding by remember { mutableStateOf(false) }

            if (showPrivacyDisclosure) {
                AlertDialog(
                    onDismissRequest = { },
                    title = { Text("Privacy & Microphone Usage") },
                    text = { Text("SER processes all audio locally on your device. Your voice data is never uploaded to a server or shared with third parties. We require microphone access solely to analyze emotional tone in real-time.") },
                    confirmButton = {
                        Button(onClick = {
                            showPrivacyDisclosure = false
                            showOnboarding = true
                        }) { Text("I Understand") }
                    }
                )
            }

            if (showOnboarding) {
                AlertDialog(
                    onDismissRequest = { },
                    title = { Text("Quick Start Guide") },
                    text = {
                        Column {
                            Text("• Record Clip: 2-6 seconds of one clear emotion.")
                            Text("• Select File: Analyze existing 16kHz mono .wav files. If file isn't in this format, the model might behave inaccurately.")
                            Text("• Live: Real-time feedback as you speak.")
                            Spacer(Modifier.height(8.dp))
                            Text("Note: VAD stands for Valence (Positivity), Arousal (Energy), and Dominance (Control).", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            showOnboarding = false
                            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
                        }) { Text("Get Started") }
                    }
                )
            }
        }
    }

    //region Core Logic
    @SuppressLint("Range")
    private fun getFileName(uri: Uri): String = contentResolver.query(uri, null, null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(it.getColumnIndex(OpenableColumns.DISPLAY_NAME)) else "Unknown File" } ?: "Unknown File"

    private fun isSpeechDetected(audioData: ShortArray, threshold: Float): Boolean {
        if (audioData.isEmpty()) return false

        // Find the absolute highest point in this chunk (Peak)
        val maxAbs = audioData.maxOfOrNull { Math.abs(it.toInt()) } ?: 0

        // Scale it so that it's more sensitive (multiplied by 5 to match your visual boost)
        val normalizedPeak = (maxAbs / 32767.0f) * 5f
        val normalizedVolume = normalizedPeak.coerceIn(0f, 1f)

        // Return true if the boosted volume is higher than the threshold
        return normalizedVolume > threshold
    }

    private suspend fun detectEmotion(uri: Uri, onSuccess: (EmotionVector) -> Unit, onFailure: (String) -> Unit) {
        try {            // 1. SIZE VALIDATION
            val parcelFileDescriptor = contentResolver.openFileDescriptor(uri, "r")
            val fileSize = parcelFileDescriptor?.statSize ?: 0
            parcelFileDescriptor?.close()

            if (fileSize > 5_000_000) {
                onFailure("File is too large for mobile processing.")
                return
            }

            // 2. DECODE AUDIO
            val pcm = AudioDecoder.decodeAudioFileToPcmShortArray(this, uri)

            // 3. SPEECH CONTENT VALIDATION
            if (pcm == null || pcm.size < 8000) {
                onFailure("Audio clip is too short to detect emotion.")
                return
            }

            // 4. PREPROCESS
            val floatData = preprocessAudioForModel(pcm)

            // 5. INFERENCE WITH LATENCY LOGGING
            val startTime = System.currentTimeMillis()

            val prediction = emotionPredictor.predict(floatData) ?: throw Exception("Inference Failed")

            val endTime = System.currentTimeMillis()
            val latency = endTime - startTime
            Log.d("LATENCY_TEST", "BATCH INFERENCE: ${latency}ms for clip: ${getFileName(uri)}")

            // 6. SUCCESS
            val vector = EmotionVector(arousal = prediction[0], valence = prediction[2], dominance = prediction[1])
            onSuccess(vector)

        } catch (e: Exception) {
            Log.e(TAG, "Detection error", e)
            onFailure("Could not process file. Ensure it is a valid .wav format.")
        }
    }

    // In MainActivity.kt, inside the class body

    // Paste this function definition
    private fun generateSummaryParagraph(emotions: List<MappedEmotion>): String {
        if (emotions.isEmpty()) {
            return "No significant emotional data was captured during the session."
        }

        val emotionCounts = emotions.groupingBy { it.label }.eachCount()
        val primaryEmotion = emotionCounts.maxByOrNull { it.value }?.key ?: "Neutral"
        val secondaryEmotions = emotionCounts.keys.filter { it != primaryEmotion }.sortedByDescending { emotionCounts[it] }

        val timeline = emotions.map { it.label }
        val segments = mutableListOf<Pair<String, Int>>()
        if (timeline.isNotEmpty()) {
            var currentEmotion = timeline[0]
            var count = 1
            for (i in 1 until timeline.size) {
                if (timeline[i] == currentEmotion) {
                    count++
                } else {
                    segments.add(currentEmotion to count)
                    currentEmotion = timeline[i]
                    count = 1
                }
            }
            segments.add(currentEmotion to count)
        }

        val totalDuration = emotions.size
        val primaryEmotionPercentage = (emotionCounts[primaryEmotion]?.toFloat()?.div(totalDuration) ?: 0f) * 100

        var summary = "The session was primarily defined by a feeling of $primaryEmotion, which was present for approximately ${"%.0f".format(primaryEmotionPercentage)}% of the duration. "

        if (segments.size > 1) {
            val longestSegment = segments.maxByOrNull { it.second }
            if (longestSegment != null && longestSegment.first == primaryEmotion) {
                summary += "The most consistent period of this emotion was a notable stretch of ${longestSegment.first}. "
            }
        }

        when {
            secondaryEmotions.isNotEmpty() -> {
                val otherEmotionsString = secondaryEmotions.take(2).joinToString(" and ")
                summary += "Beyond this, there were also significant moments of $otherEmotionsString. "
            }
            else -> {
                summary += "The emotional state remained quite consistent throughout. "
            }
        }

        val transitionCount = segments.size - 1
        if (transitionCount > 3) {
            summary += "The user's emotional state appeared to be quite dynamic, shifting multiple times during the session."
        } else if (transitionCount > 0) {
            summary += "A few transitions in feeling were noted, suggesting some changes in emotional state."
        }

        return summary
    }


        private fun updateHistoryItemTitle(itemId: String, newTitle: String) {
        val updatedList = _history.value.map {
            if (it.id == itemId) it.copy(title = newTitle) else it
        }
        _history.value = updatedList
        Log.d(TAG, "Updated title for item $itemId to '$newTitle'")
            saveHistoryToDisk(updatedList)
        }

    private fun saveHistoryItem(
        finalEmotion: String,
        detectedEmotion: String,
        vector: EmotionVector,
        type: HistoryItemType = HistoryItemType.CLIP,
        fileToSave: File?,
        sessionMap: List<MappedEmotion> = emptyList() // Added parameter
    ) {
        val sourceFile = fileToSave ?: currentRecordingFile ?: run {
            Log.e(TAG, "saveHistoryItem: No file provided!")
            return
        }

        val historyDir = File(filesDir, "history").apply { if (!exists()) mkdirs() }
        val destinationFile = File(historyDir, sourceFile.name)
        sourceFile.copyTo(destinationFile, overwrite = true)
        val displayTitle = if (type == HistoryItemType.SESSION) {
            "Live Session " + SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date())
        } else {
            sourceFile.name
        }
        _history.value = _history.value + HistoryItem(
            title = displayTitle,
            permanentPath = destinationFile.absolutePath,
            userConfirmedEmotion = finalEmotion,
            detectedEmotion = detectedEmotion,
            vector = vector,
            type = type,
            sessionMap = sessionMap // Pass it here
        )

        if (sourceFile.path.contains(cacheDir.path)) sourceFile.delete()
        if (type == HistoryItemType.CLIP) {
            currentRecordingFile = null
            tempAudioFile = null
        }
        saveHistoryToDisk(_history.value)
    }


    private fun preprocessAudioForModel(shortArray: ShortArray): FloatArray {
        return FloatArray(shortArray.size) { i -> shortArray[i] / 32768.0f }
    }
    //endregion

    //region UI Composables
    @Composable
    fun WelcomeScreen(onRecordAudioClick: () -> Unit, onOpenFileClick: () -> Unit, onLiveFeedbackClick: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(0.8f))
            WelcomeSection()
            Spacer(modifier = Modifier.weight(1f))
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
                val scale by infiniteTransition.animateFloat(1f, 1.1f, infiniteRepeatable(tween(1500), RepeatMode.Reverse), label = "scale")
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = onRecordAudioClick,
                        modifier = Modifier
                            .size(72.dp)
                            .graphicsLayer {
                                scaleX = scale; scaleY = scale; shadowElevation = 8.dp.toPx()
                            }
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
                    Text("Record Clip", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onOpenFileClick) { Text("Or Open an Audio File") }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

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
        val summaryParagraph = remember(emotions) { generateSummaryParagraph(emotions) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Session Summary", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text("Primary Emotion: $primaryEmotion", style = MaterialTheme.typography.titleLarge, color = createMappedEmotion(primaryEmotion).color)
            Spacer(Modifier.height(24.dp))

            // AI-Generated Analysis Section
            Text("AI-Generated Analysis", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Divider(modifier = Modifier.padding(vertical = 8.dp), color = Color.Gray)
            Text(
                text = summaryParagraph,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.9f),
                lineHeight = 24.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Divider(modifier = Modifier.padding(vertical = 8.dp), color = Color.Gray)
            Spacer(Modifier.height(16.dp))

            // Percentage Breakdown Section
            if (summary.isEmpty()) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text("No emotions were detected during the session.", color = Color.White, textAlign = TextAlign.Center)
                }
            } else {
                Text("Emotion Breakdown:", style = MaterialTheme.typography.titleMedium, color = Color.White)
                LazyColumn(modifier = Modifier
                    .weight(1f)
                    .padding(top = 8.dp)) {
                    items(summary.entries.toList().sortedByDescending { it.value }) { (label, count) ->
                        val percentage = (count / totalEmotions) * 100
                        Row(modifier = Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, color = createMappedEmotion(label).color, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            Text("${"%.1f".format(percentage)}%", color = Color.White, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onReplay) { Text("Replay") }
                Button(onClick = onReturnHome) { Text("Discard") }
                Button(onClick = {
                    val summaryString = summary.keys.joinToString(", ")
                    onSaveSession(primaryEmotion, summaryString, EmotionVector(0f, 0f, 0f), file)
                    onReturnHome()
                }) { Text("Save & Exit") }
            }
        }
    }

    @Composable
    fun RecordingScreen(amplitude: Float, onStopRecording: () -> Unit) {
        // Reduce total bars to 80 to make the animation feel faster/standard
        val maxBars = 80
        val amplitudes = remember { mutableStateListOf<Float>() }
        var lastSmoothAmplitude by remember { mutableFloatStateOf(0f) }

        LaunchedEffect(amplitude) {
            // Smoothing: logic to prevent weird "jittery" oscillations
            val smoothed = (amplitude * 0.3f) + (lastSmoothAmplitude * 0.7f)
            lastSmoothAmplitude = smoothed

            // Scale the visual height (multiplied by 6 for better visibility)
            amplitudes.add(smoothed.coerceIn(0.01f, 1f))
            if (amplitudes.size > maxBars) amplitudes.removeAt(0)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.weight(1f))
            Text("Recording...", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(32.dp))

            Canvas(modifier = Modifier
                .fillMaxWidth()
                .height(150.dp) // Taller canvas for better perspective
                .padding(horizontal = 24.dp)) {

                val canvasWidth = size.width
                val canvasHeight = size.height
                val barSpacing = canvasWidth / maxBars
                val middleY = canvasHeight / 2f

                amplitudes.forEachIndexed { index, amp ->
                    val x = index * barSpacing
                    // Scale the bar height
                    val barHeight = (amp * canvasHeight * 0.8f).coerceAtLeast(4f)

                    drawLine(
                        color = Color.White,
                        start = Offset(x, middleY - (barHeight / 2)),
                        end = Offset(x, middleY + (barHeight / 2)),
                        strokeWidth = 6f, // Thicker bars
                        cap = androidx.compose.ui.graphics.StrokeCap.Round // Rounded look
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Stop Button
            IconButton(
                onClick = onStopRecording,
                modifier = Modifier
                    .size(90.dp)
                    .padding(bottom = 32.dp)
                    .background(Color.White.copy(alpha = 0.1f), CircleShape)
            ) {
                Icon(
                    Icons.Default.Stop,
                    "Stop",
                    tint = Color.Red,
                    modifier = Modifier.size(48.dp)
                )
            }
        }
    }
    @Composable
    fun PostRecordingScreen(appState: AppState.PostRecordingReview, onDetectClick: (String, Uri) -> Unit, onPlayClick: () -> Unit, onReRecordClick: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
        ) {
            Text("Recording Complete", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(appState.fileName, color = Color.LightGray)
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onPlayClick) {
                    Icon(Icons.Default.PlayArrow, "Play")
                    Spacer(Modifier.width(8.dp))
                    Text("Replay")
                }
                Button(onClick = onReRecordClick, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)) {
                    Icon(Icons.Default.Refresh, "Re-record")
                    Spacer(Modifier.width(8.dp))
                    Text("Re-record")
                }
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(mappedEmotion.label, fontSize = 48.sp, fontWeight = FontWeight.Bold, color = mappedEmotion.color, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(mappedEmotion.description, fontSize = 16.sp, color = Color.White, textAlign = TextAlign.Center)
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
                        .fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = Color.Gray,
                        focusedLabelColor = Color.White, unfocusedLabelColor = Color.Gray,
                        cursorColor = MaterialTheme.colorScheme.primary
                    )
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
                        keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = Color.Gray,
                            focusedLabelColor = Color.White, unfocusedLabelColor = Color.Gray,
                            cursorColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    LazyColumn(modifier = Modifier.heightIn(max = 150.dp)) {
                        items(searchSuggestions) { suggestion ->
                            Text(suggestion, modifier = Modifier
                                .fillMaxWidth()
                                .clickable { customEmotionText = suggestion }
                                .padding(12.dp), color = Color.White)
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
    @Composable
    fun HistoryScreen(
        historyItems: List<HistoryItem>,
        onPlayItem: (path: String) -> Unit,
        onStopPlayer: () -> Unit,
        onNavigateUp: () -> Unit,
        onRenameItem: (id: String, title: String) -> Unit,
        onDeleteItem: (HistoryItem) -> Unit,
        onClearAll: () -> Unit,
        onShare: () -> Unit,
        onDownload: () -> Unit
    ) {
        var showRenameDialogFor by remember { mutableStateOf<HistoryItem?>(null) }
        var showClearConfirm by remember { mutableStateOf(false) }
        var expandedItem by remember { mutableStateOf<HistoryItem?>(null) }
        var currentlyPlayingId by remember { mutableStateOf<String?>(null) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("History") },
                    navigationIcon = { IconButton(onClick = onNavigateUp) { Icon(Icons.Default.ArrowBack, null) } },
                    actions = {
                        IconButton(onClick = onShare) { Icon(Icons.Default.Share, null) }
                        IconButton(onClick = onDownload) { Icon(Icons.Default.Download, null) }
                        IconButton(onClick = { showClearConfirm = true }) { Icon(Icons.Default.DeleteSweep, null, tint = Color.Red) }
                    }
                )
            },
            containerColor = Color.Black
        ) { pad ->
            if (historyItems.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                    Text("No recordings saved yet.", color = Color.White)
                }
            } else {
                LazyColumn(Modifier.padding(pad).padding(16.dp)) {
                    items(historyItems.sortedByDescending { it.timestamp }) { item ->
                        ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                // 1. SESSION STRIP (Only for Sessions)
                                if (item.type == HistoryItemType.SESSION && item.sessionMap.isNotEmpty()) {
                                    Box(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape).background(Color.DarkGray)) {
                                        Canvas(Modifier.fillMaxSize()) {
                                            val w = size.width / item.sessionMap.size
                                            item.sessionMap.forEachIndexed { i, em ->
                                                drawRect(em.color, Offset(i * w, 0f), Size(w + 1f, size.height))
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(item.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)

                                        // 2. TRUE VS DETECTED EMOTION
                                        if (item.type == HistoryItemType.CLIP) {
                                            Text("True Emotion: ${item.userConfirmedEmotion}", style = MaterialTheme.typography.bodyLarge)
                                            Text("Detected: ${item.detectedEmotion}", color = createMappedEmotion(item.detectedEmotion).color, style = MaterialTheme.typography.bodyMedium)

                                            // 3. VAD VALUES
                                            Text(
                                                text = "V: ${"%.2f".format(item.vector.valence)}  A: ${"%.2f".format(item.vector.arousal)}  D: ${"%.2f".format(item.vector.dominance)}",
                                                color = Color.Gray,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        } else {
                                            Text("Primary Emotion: ${item.userConfirmedEmotion}", color = createMappedEmotion(item.userConfirmedEmotion).color)
                                        }
                                    }

                                    // 4. ACTION BUTTONS
                                    Row {
                                        IconButton(onClick = {
                                            if (currentlyPlayingId == item.id) {
                                                onStopPlayer()
                                                currentlyPlayingId = null
                                            } else {
                                                onPlayItem(item.permanentPath)
                                                currentlyPlayingId = item.id
                                            }
                                        }) {
                                            Icon(if (currentlyPlayingId == item.id) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                                        }
                                        IconButton(onClick = { showRenameDialogFor = item }) { Icon(Icons.Default.Edit, null) }
                                        IconButton(onClick = { onDeleteItem(item) }) { Icon(Icons.Default.Delete, null, tint = Color.Red.copy(alpha = 0.7f)) }

                                        if (item.type == HistoryItemType.SESSION) {
                                            IconButton(onClick = { expandedItem = if (expandedItem?.id == item.id) null else item }) {
                                                Icon(if (expandedItem?.id == item.id) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                                            }
                                        }
                                    }
                                }

                                // 5. SESSION BREAKDOWN (Animated)
                                AnimatedVisibility(expandedItem?.id == item.id && item.type == HistoryItemType.SESSION) {
                                    Column {
                                        Divider(Modifier.padding(vertical = 8.dp))
                                        val total = item.sessionMap.size.toFloat().coerceAtLeast(1f)
                                        item.sessionMap.groupingBy { it.label }.eachCount().forEach { (label, count) ->
                                            Text("$label: ${"%.1f".format((count / total) * 100)}%", color = createMappedEmotion(label).color)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // DIALOGS
            if (showClearConfirm) {
                AlertDialog(
                    onDismissRequest = { showClearConfirm = false },
                    title = { Text("Clear All History?") },
                    text = { Text("This will permanently delete all recordings and emotional data.") },
                    confirmButton = { TextButton(onClick = { onClearAll(); showClearConfirm = false }) { Text("Clear", color = Color.Red) } },
                    dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") } }
                )
            }
            showRenameDialogFor?.let { item ->
                var newName by remember { mutableStateOf(item.title) }
                AlertDialog(
                    onDismissRequest = { showRenameDialogFor = null },
                    title = { Text("Rename Recording") },
                    text = { OutlinedTextField(newName, { newName = it }, singleLine = true) },
                    confirmButton = { Button(onClick = { onRenameItem(item.id, newName); showRenameDialogFor = null }) { Text("Save") } },
                    dismissButton = { TextButton(onClick = { showRenameDialogFor = null }) { Text("Cancel") } }
                )
            }
        }
    }
    @Composable
    fun ModelInfoScreen(onNavigateUp: () -> Unit) {
        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
        Column(
            modifier = Modifier.fillMaxSize().background(Color.Black).padding(24.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Analytics, null, tint = Color.White, modifier = Modifier.size(64.dp))
            Text("Model Credits", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text(
                text = "This application utilizes the wav2vec2 emotion recognition model developed by audEERING GmbH.",
                color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 16.dp)
            )
            Text("The model is used under the CC BY-NC-SA 4.0 license for non-commercial research purposes.", color = Color.Gray, textAlign = TextAlign.Center)

            Spacer(Modifier.height(24.dp))
            Button(onClick = { uriHandler.openUri("https://huggingface.co/audeering/wav2vec2-large-robust-12-ft-emotion-msp-dim") }, modifier = Modifier.fillMaxWidth()) {
                Text("View on Hugging Face")
            }
            OutlinedButton(
                onClick = { uriHandler.openUri("https://www.audeering.com/") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Text("Visit audEERING Website")
            }

            // YOUR GITHUB LINK
            Spacer(Modifier.height(48.dp))
            Divider(color = Color.DarkGray)
            Spacer(Modifier.height(16.dp))
            Text("Developer Resources", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text("For detailed documentation on how this app was built and the VAD mapping logic, visit the GitHub repository:", color = Color.LightGray, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 8.dp))
            TextButton(onClick = { uriHandler.openUri("https://github.com/BluMooon/speech-emotion-recognition/blob/main/README.md") }) {
                Text("GitHub: BluMooon/speech-emotion-recognition", color = MaterialTheme.colorScheme.primary, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)
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
                val description = when (emotionLabel) {
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
    fun WelcomeSection() {
        val provider = remember {
            GoogleFont.Provider(
                "com.google.android.gms.fonts",
                "com.google.android.gms",
                EMPTY_CERTIFICATES
            )
        }
        val fontFamily = remember { FontFamily(Font(GoogleFont("Montserrat"), provider)) }
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { visible = true }
        AnimatedVisibility(visible, enter = slideInVertically { it / 2 } + fadeIn()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "SER",
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 80.sp,
                    color = Color.White
                )
                Text(
                    "Speech Emotion Recognition",
                    fontFamily = fontFamily,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 2.sp,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
                Text(
                    "For best results, express one clear emotion. Clips should be 2-6 seconds. Live feedback can be longer. SER can make mistakes, your confirmation helps!",
                    textAlign = TextAlign.Center,
                    fontFamily = fontFamily,
                    color = Color.White.copy(alpha = 0.8f),
                    lineHeight = 24.sp,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

    }
    //endregion
}
