// Path: app/src/main/java/com/example/myapplication/LiveFeedbackScreen.kt
package com.example.myapplication

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.navigation.NavController

@Composable
fun LiveFeedbackScreen(navController: NavController, viewModel: AudioViewModel) {
    val isRecording by viewModel.isRecording.collectAsState()
    val liveEmotion by viewModel.liveEmotion.collectAsState()
    val audioAmplitudes by viewModel.audioAmplitudes.collectAsState()

    // State to manage the save dialog
    var showSaveDialog by remember { mutableStateOf(false) }

    // This will trigger the live processing start/stop
    // We use LaunchedEffect to start/stop when the button is pressed
    // This is a placeholder for the real implementation
    if (isRecording) {
        // TODO: This is where we would call viewModel.startLiveProcessing()
        // and have it continuously update liveEmotion and audioAmplitudes
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    // Display the live emotion label, or "..." for pauses, or a default message
                    text = if (isRecording) liveEmotion?.label ?: "..." else "Press Record for Live Feedback",
                    fontSize = 32.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.height(80.dp) // Give it space to avoid layout shifts
                )
                Text(
                    text = if (isRecording) liveEmotion?.description ?: "Listening..." else "The emotion will update in real-time.",
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            AudioWaveform(amplitudes = audioAmplitudes)

            Button(
                onClick = {
                    if (isRecording) {
                        viewModel.stopLiveProcessing()
                        showSaveDialog = true // Show dialog when stopping
                    } else {
                        viewModel.startLiveProcessing()
                    }
                },
                modifier = Modifier.size(100.dp)
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = if (isRecording) "Stop Recording" else "Start Live Recording",
                    modifier = Modifier.size(48.dp)
                )
            }
        }
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = {
                showSaveDialog = false
                navController.popBackStack() // Go back to main screen
            },
            title = { Text("Save Results?") },
            text = { Text("Do you want to save the emotion log from this session?") },
            confirmButton = {
                Button(
                    onClick = {
                        // TODO: Implement save logic
                        showSaveDialog = false
                        navController.popBackStack()
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                Button(
                    onClick = {
                        showSaveDialog = false
                        navController.popBackStack()
                    }
                ) {
                    Text("Discard")
                }
            }
        )
    }
}

@Composable
fun AudioWaveform(
    amplitudes: List<Float>,
    modifier: Modifier = Modifier,
    strokeWidth: Float = 4f,
    strokeColor: Color = Color.Gray
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(100.dp) // You can adjust the height as needed
    ) {
        val centerY = size.height / 2
        val xSpacing = if (amplitudes.isNotEmpty()) size.width / amplitudes.size else 0f

        amplitudes.forEachIndexed { index, amplitude ->
            val x = (index + 1) * xSpacing
            // We normalize the amplitude to be between 0 and 1,
            // then multiply by the available height.
            val lineHeight = amplitude * size.height
            drawLine(
                color = strokeColor,
                start = Offset(x, centerY - lineHeight / 2),
                end = Offset(x, centerY + lineHeight / 2),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
        }
    }
}