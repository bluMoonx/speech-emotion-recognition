package com.example.myapplication

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AudioViewModel(context: Context) : ViewModel() {

    // CORRECTED: Instantiate our new custom AudioProcessor class, not the Media3 interface.
    private val audioProcessor = AudioProcessor(context, viewModelScope)

    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    private val _mappedEmotion = MutableStateFlow<MappedEmotion?>(null)
    val mappedEmotion = _mappedEmotion.asStateFlow()

    // --- STATE FOR LIVE FEEDBACK (NEW) ---
    private val _liveEmotion = MutableStateFlow<MappedEmotion?>(null)
    val liveEmotion = _liveEmotion.asStateFlow()
    // ---

    private val _audioAmplitudes = MutableStateFlow<List<Float>>(emptyList())
    val audioAmplitudes = _audioAmplitudes.asStateFlow()

    fun startRecording() {
        viewModelScope.launch {
            _isRecording.value = true
            _mappedEmotion.value = null
            // We pass a lambda function to our processor.
            audioProcessor.startRecording { emotion: MappedEmotion?, amplitudes: List<Float> ->
                _mappedEmotion.value = emotion
                _audioAmplitudes.value = amplitudes
                _isRecording.value = false
            }
        }
    }

    fun stopRecording() {
        audioProcessor.stopRecording()
        _isRecording.value = false
    }

    // --- FUNCTIONS FOR LIVE FEEDBACK (NEW) ---

    fun startLiveProcessing() {
        viewModelScope.launch {
            _isRecording.value = true
            _liveEmotion.value = null // Reset on start
            // TODO: Implement the continuous processing logic in AudioProcessor
            // This will call a new function like audioProcessor.startLiveProcessing { ... }
        }
    }

    fun stopLiveProcessing() {
        // TODO: Implement the logic to stop the live processing stream.
        audioProcessor.stopLiveProcessing()
        _isRecording.value = false
    }

    // ---
}


class AudioViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AudioViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AudioViewModel(context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
