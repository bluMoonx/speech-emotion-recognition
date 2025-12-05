// In file: app/src/main/java/com/example/myapplication/LiveFeedbackScreen.kt

package com.example.myapplication

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// This is the new, correct version of your LiveFeedbackScreen.
// It is now called from MainActivity's NavHost.
@Composable
fun LiveRecordingScreen(
    processor: LiveAudioProcessor,
    onStop: () -> Unit
) {
    // This effect starts the processor when the screen appears
    LaunchedEffect(Unit) {
        processor.startRecording()
    }

    // Collect the states from the new processor
    val amplitudes by processor.amplitudeFlow.collectAsState()
    val latestEmotion by processor.latestEmotion.collectAsState()

    val statusText = latestEmotion?.label ?: "Listening..."
    val statusColor = latestEmotion?.color ?: Color.White

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.weight(1f))

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

        // Use the ScrollingWaveform from MainActivity
        ScrollingWaveform(
            amplitudes = amplitudes,
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.weight(0.8f))

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