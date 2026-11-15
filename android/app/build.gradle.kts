import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()?.coerceAtLeast(1) ?: 1

android {
    namespace = "com.framestack.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.framestack.android"
        minSdk = 26
        targetSdk = 35
        versionCode = ciRunNumber
        versionName = "1.0.$ciRunNumber"
    }

    signingConfigs {
        getByName("debug") {
            val customKeystore = System.getenv("ANDROID_KEYSTORE_PATH")
                ?.let { file(it) }
                ?.takeIf { it.exists() }
            if (customKeystore != null) {
                storeFile = customKeystore
                storePassword = System.getenv("ANDROID_KEYSTORE_PASS") ?: "android"
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: "framestack"
                keyPassword = System.getenv("ANDROID_KEY_PASS") ?: "android"
            } else {
                storeFile = file("framestack-debug.p12")
                storeType = "PKCS12"
                storePassword = "android"
                keyAlias = "framestack"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Media3 ExoPlayer for native adaptive & muxed playback
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")

    // NewPipeExtractor for YouTube channels, playlists, and raw stream resolution
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.27.1")

    // Networking, JSON, and Images
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("io.coil-kt:coil-compose:2.7.0")
}

androidComponents {
    onVariants { variant ->
        variant.instrumentation.transformClassesWith(
            UrlCharsetBackportVisitorFactory::class.java,
            InstrumentationScope.ALL
        ) {}
        variant.instrumentation.setAsmFramesComputationMode(
            FramesComputationMode.COPY_FRAMES
        )
    }
}
