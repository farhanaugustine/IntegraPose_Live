plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

val ncnnSdkRoot = rootProject
    .file("third_party/ncnn/ncnn-20260526-android-vulkan")
    .invariantSeparatorsPath

android {
    namespace = "com.integrapose.mobile"
    compileSdk = 35
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.integrapose.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "v.0.1.rc1"

        buildConfigField("boolean", "BUNDLED_TEST_KIT", "false")
        buildConfigField("boolean", "PLAY_SPLASH_VIDEO", "true")
        buildConfigField("boolean", "START_ON_BENCHMARK", "false")
        buildConfigField("boolean", "REQUEST_SENSORS_AFTER_AGREEMENT", "true")
        buildConfigField("boolean", "POSTPROCESS_LIVE_ANNOTATED_VIDEO", "true")
        buildConfigField("boolean", "MODEL_SCOPED_PIPELINE_AUTOTUNE", "true")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DNCNN_ROOT=$ncnnSdkRoot",
                    "-DANDROID_STL=c++_static"
                )
                cppFlags += listOf("-std=c++17")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // Public Android Studio Run builds use the same product workflow
            // as release builds and never package private validation assets.
            buildConfigField("boolean", "BUNDLED_TEST_KIT", "false")
            buildConfigField("boolean", "START_ON_BENCHMARK", "false")
            buildConfigField("boolean", "REQUEST_SENSORS_AFTER_AGREEMENT", "true")
        }

        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE-notice.md"
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.01.00")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("androidx.camera:camera-video:1.4.1")

    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation("androidx.media3:media3-transformer:1.7.1")
    implementation("androidx.media3:media3-effect:1.7.1")
    implementation("androidx.media3:media3-common:1.7.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation(composeBom)
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
