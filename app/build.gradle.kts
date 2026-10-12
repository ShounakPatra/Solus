import java.util.Properties
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val nativeSourceDigest = MessageDigest.getInstance("SHA-256").apply {
    update("toolchain:ndk-28.2.13676358;cmake-3.22.1;platform:android-28;abi:arm64-v8a;c++17".toByteArray())
    fileTree("src/main/cpp") {
        include(
            "**/*.c", "**/*.cpp", "**/*.h", "**/*.hpp",
            "**/*.comp", "**/*.spv", "**/*.glsl", "**/*.vert", "**/*.frag",
            "**/CMakeLists.txt", "**/*.cmake", "**/*.map"
        )
    }.files.sortedBy { it.relativeTo(projectDir).invariantSeparatorsPath }.forEach { source ->
        update(source.relativeTo(projectDir).invariantSeparatorsPath.toByteArray())
        source.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var count = input.read(buffer)
            while (count >= 0) {
                if (count > 0) update(buffer, 0, count)
                count = input.read(buffer)
            }
        }
    }
}.digest().joinToString("") { "%02x".format(it) }
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}

android {
    namespace = "com.shounak.localmeshai"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.shounak.localmeshai"
        minSdk = 28
        targetSdk = 37
        versionCode = 8
        versionName = "5.0.0"
        buildConfigField("String", "NATIVE_BUILD_ID", "\"$nativeSourceDigest\"")
        buildConfigField("String", "LITERT_RUNTIME_VERSION", "\"${libs.versions.litertLm.get()}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.clear()
            abiFilters.add("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments("-DANDROID_PLATFORM=android-28", "-DANDROID_LD=lld")
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

    val isReleaseSmoke = (project.findProperty("solusReleaseSmoke")?.toString()?.toBoolean() == true)
    if (isReleaseSmoke) {
        testBuildType = "release"
    }

    buildTypes {
        debug {
            if (debugKeystoreFile.isFile) {
                signingConfig = signingConfigs.findByName("debugConfig")
            }
        }
        release {
            if (isReleaseSmoke && debugKeystoreFile.isFile) {
                signingConfig = signingConfigs.findByName("debugConfig")
            } else if (keystorePropertiesFile.isFile) {
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
        abortOnError = true
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

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.packaging.dex.useLegacyPackaging.set(true)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-Xbackend-threads=0"
        )
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
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodelCompose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
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
    testImplementation(libs.json)
    testImplementation(libs.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}



