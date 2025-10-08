# Speech Emotion Recognition Android App #
Welcome to the repository for my **Speech Emotion Recognition (SER)** Android application. This project is an exploration into real-time & pre-uploaded voice analysis on a mobile device.

## About the App ##
This app is designed to analyze the emotional content of a user's voice from a *.wav* file. It uses a pre-trained ONNX (Open Neural Network Exchange) model to predict the emotional state based on vocal cues, classifying it into categories such as "Calm," "Happy," "Angry," "Anxious," etc. Please note that these values are based off an emotion map, for the actual output of the model indicates a vector of [arousal, valence, dominance], which are three fundamental aspects in voice and tone.

## The Original Model ##
This app was built on the pre-trained Wav2Vec2-Large-Robust model by Audeering.
The model cannot be used commercially, but I've implemented it here for educational purposes exclusively.
### Link to Audeering Model: **https://huggingface.co/audeering/wav2vec2-large-robust-12-ft-emotion-msp-dim** ###

## Project Goals ##
The primary goal is to create a simple, intuitive interface where a user can:
  1. Select a .wav audio file from their device OR record a short audio segment on the spot.
  2. Initiate an emotion detection process by running the inputted file / clip into the wav2vec2 model.
  3. Receive a clear emotional classification based on the model's output & raw values.

## Core Technologies ##
* Language: **Kotlin**
* UI Framework: Jetpack Compose for a fully declarative and modern user interface
* Machine Learning: ONNX Runtime for Android to load and execute the neural network model directly on the device
* Architecture: UI that manages different application states like Idle, Loading, Success, and Failure

## How to Find the Code ##
All of the primary application source code for this project is located within the following directory:
### */app/src/main/java/com/example/myapplication/* ###

Inside, you will find key files such as (*but not limited to*):
* MainActivity.kt: The main entry point and UI controller.
* EmotionPredictor.kt: The class responsible for handling the ONNX model.
* EmotionMapper.kt: The logic for converting the model's raw numerical output into meaningful emotion labels.

# Important Note on the ONNX Model #
The original machine learning model file (which I've converted from its original Pytorch status to a .onnx file out of necessity and convenience) is **NOT INCLUDED** in this repository. Due to its large size (over 500 MB), it exceeds GitHub's file size limit. The application code is structured to load a model named emotion_model_final.onnx from the app's assets folder, but you will need to provide your own model to build and run the project successfully. To do so, please use the *above link* and convert to .onnx to fully run. This repository is only meant to be used as a workspace to share code and commit saved work.
