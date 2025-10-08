package com.example.myapplication

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.FloatBuffer
import java.util.Collections

private const val TAG_EP = "EmotionPredictor" // Using this tag consistently

class EmotionPredictor(private val context: Context) {
    private var ortSession: OrtSession? = null
    private val ortEnvironment: OrtEnvironment =
        OrtEnvironment.getEnvironment() // Ensure this is initialized
    private val modelFile =
        "emotion_model_final.onnx" // Make sure this is your exact model file name in assets

    // IMPORTANT: You need to find the actual input name of your model
    private var inputName: String? = null

    init {
        try {
            val modelInputStream: InputStream = context.assets.open(modelFile)
            val tempModelFile = File(context.cacheDir, modelFile)
            FileOutputStream(tempModelFile).use { outputStream ->
                modelInputStream.copyTo(outputStream)
            }
            modelInputStream.close()

            ortSession = ortEnvironment.createSession(tempModelFile.absolutePath)
            // After session is created, get the input name
            // This assumes your model has at least one input.
            // If it has multiple, you'll need to select the correct one.
            inputName = ortSession?.inputNames?.firstOrNull() // Get the first input name

            if (inputName == null) {
                Log.e(TAG_EP, "Failed to get input name from the ONNX model.")
            } else {
                Log.d(TAG_EP, "ONNX session initialized. Input name: $inputName")
            }
            Log.d(
                TAG_EP,
                "ONNX session initialized successfully from ${tempModelFile.absolutePath}"
            )

        } catch (e: Exception) {
            Log.e(TAG_EP, "Error initializing ONNX session", e)
        }
    }

    fun close() {
        try {
            ortSession?.close()
            // ortEnvironment.close(); // Only close if it's no longer needed by any other session and app is exiting
            Log.d(TAG_EP, "ONNX session closed.")
        } catch (e: Exception) {
            Log.e(TAG_EP, "Error closing ONNX session", e)
        }
    }

    fun predict(floatAudioInput: FloatArray?): FloatArray? {
        // --- THE FIX: Perform a safe, manual null-or-empty check first ---
        // This handles the nullable type correctly and satisfies the compiler.
        if (ortSession == null || inputName == null || floatAudioInput == null || floatAudioInput.isEmpty()) {
            Log.e(
                TAG_EP,
                "Prediction pre-check failed. Session Ready: ${ortSession != null}, Input Name Ready: ${inputName != null}, Input Valid: ${floatAudioInput != null && floatAudioInput.isNotEmpty()}"
            )
            return null
        }

        // After the check above, the compiler knows `floatAudioInput` is a valid, non-null FloatArray.
        // All subsequent calls to .size and its use in FloatBuffer.wrap() are now safe.

        try {
            val batchSize = 1L
            val sequenceLength = floatAudioInput.size.toLong()
            val shape = longArrayOf(batchSize, sequenceLength)
            val inputBuffer = FloatBuffer.wrap(floatAudioInput)

            // Use the class member ortEnvironment
            OnnxTensor.createTensor(ortEnvironment, inputBuffer, shape).use { inputTensor ->
                // Use the class member ortSession and the determined inputName
                ortSession?.run(Collections.singletonMap(inputName!!, inputTensor))
                    ?.use { results ->
                        val outputTensor = results.firstOrNull()?.value as? OnnxTensor
                        if (outputTensor != null) {
                            val outputBuffer = outputTensor.floatBuffer
                            val outputArray = FloatArray(outputBuffer.remaining())
                            outputBuffer.get(outputArray)
                            outputTensor.close()
                            return outputArray
                        } else {
                            Log.e(TAG_EP, "Output tensor is null or not of the expected type.")
                            return null
                        }
                    } ?: run {
                    Log.e(TAG_EP, "ONNX inference run returned null results.")
                    return null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG_EP, "Error during ONNX inference: ${e.message}", e)
            return null
        }
    }
}

