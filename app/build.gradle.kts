plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.example.myapplication"
    compileSdk = 34 // Reverting to a more standard and stable SDK version

    defaultConfig {
        applicationId = "com.example.myapplication"
        minSdk = 24
        targetSdk = 34 // Aligning target SDK
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8 // Reverting to 1.8 is safer for broader compatibility
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    // Add the composeOptions block to specify the compiler version if needed
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11" // This version is compatible with Compose BOM 2024.02.02
    }
}

dependencies {
    // --- STABLE DEPENDENCY SET ---

    // 1. Use a stable Compose BOM from early 2024. This controls many other versions.
    val composeBom = platform("androidx.compose:compose-bom:2024.02.02")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // 2. Core Jetpack libraries with stable, known-good versions.
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.compose.runtime:runtime-livedata")

    // 3. Compose UI libraries (versions are all managed by the BOM)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("androidx.compose.ui:ui-text-google-fonts:1.6.6")


    // 4. Other dependencies
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.3")
    implementation(libs.androidx.navigation.compose)

    // For Media3, using the same stable version WITHOUT the broken exclude blocks.
    val media3Version = "1.3.1"
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")

    // THIS IS THE CRITICAL FIX: Explicitly add the Android-compatible Guava.
    // This forces all other dependencies (like media3) to use this correct version, resolving the conflict.
    implementation("com.google.guava:guava:33.2.0-android")

    // These don't typically need updates unless there's a specific reason
    implementation("io.grpc:grpc-okhttp:1.58.0")
    implementation("io.grpc:grpc-protobuf-lite:1.58.0")
    implementation("io.grpc:grpc-stub:1.58.0")
    implementation("javax.annotation:javax.annotation-api:1.3.2")


    // --- TEST DEPENDENCIES ---
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

