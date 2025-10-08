
package com.example.myapplication
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

class MainViewModel : ViewModel() {
// --- State Management ---
// These states will survive when the Activity is re-created.
var statusText by mutableStateOf("Select a .wav file to begin.")
    private set // Only ViewModel can change this

var isLoading by mutableStateOf(false)
    private set // Only ViewModel can change this

fun onFileSelectionStarted() {
    isLoading = true
    statusText = "Starting..."
}

fun onProcessingResult(result: String) {
    isLoading = false
    statusText = result
}
}
