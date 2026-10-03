import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}

android {
    namespace = "com.shounak.localmeshai"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.shounak.localmeshai"
        minSdk = 28
        targetSdk = 37
        versionCode = 7
        versionName = "3.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.clear()
            abiFilters.add("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments("-DANDROID_PLATFORM=android-28")
            }
        }
    }

    val debugKeystoreFile = file("${rootDir}/debug.keystore")
    signingConfigs {
        if (debugKeystoreFile.isFile) {
            create("debugConfig") {
                storeFile = debugKeystoreFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        if (keystorePropertiesFile.isFile) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (debugKeystoreFile.isFile) {
                signingConfig = signingConfigs.findByName("debugConfig")
            }
        }
        release {
            if (keystorePropertiesFile.isFile) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    val sdkDirString = project.findProperty("sdk.dir")?.toString()
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: "C:/Users/shoun/AppData/Local/Android/Sdk"
    val ndkDir = file(sdkDirString).resolve("ndk")
    val hasValidNdk = ndkDir.exists() && ndkDir.listFiles()?.any { it.resolve("source.properties").isFile } == true
    if (hasValidNdk) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            excludes += listOf(
                "**/x86/**",
                "**/x86_64/**",
                "**/armeabi-v7a/**",
                "**/armeabi/**",
                "**/mips/**",
                "**/mips64/**"
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

configurations.all {
    exclude(group = "com.google.firebase", module = "firebase-measurement-connector")
    exclude(group = "com.google.firebase", module = "firebase-analytics")
    exclude(group = "com.google.firebase", module = "firebase-sessions")
    exclude(group = "com.google.firebase", module = "firebase-installations")
    exclude(group = "com.google.android.gms", module = "play-services-measurement")
    exclude(group = "com.google.android.gms", module = "play-services-measurement-base")
    exclude(group = "com.google.android.gms", module = "play-services-measurement-api")
    exclude(group = "com.google.android.gms", module = "play-services-measurement-sdk")
    exclude(group = "com.google.android.gms", module = "play-services-measurement-sdk-api")
    exclude(group = "com.google.android.datatransport", module = "transport-backend-cct")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtimeKtx)
    implementation(libs.androidx.lifecycle.viewmodelCompose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.emoji2)
    implementation(libs.androidx.emoji2.bundled)
    implementation(libs.latex.base)
    implementation(libs.latex.parser)
    implementation(libs.latex.renderer)
    implementation(libs.material)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.mediapipe.tasks.genai)
    implementation(libs.mediapipe.tasks.core)
    implementation(libs.litert.lm)
    implementation(libs.pdfbox.android)
    implementation(libs.haze)
    implementation(libs.haze.materials)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

tasks.matching { it.name.contains("AarMetadata") }.configureEach {
    enabled = false
}

