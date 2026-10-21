import java.util.Properties
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.Adler32
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import com.android.build.api.artifact.SingleArtifact

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
        versionCode = 10
        versionName = "6.0.1"
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
            optimization {
                keepRules {
                    // Haze 1.5.1's only consumer rule uses { *; }, which also
                    // matches Object methods. Replace it in proguard-rules.pro.
                    // Version-scoped so future Haze rules are reviewed on upgrade.
                    ignoreFrom("dev.chrisbanes.haze:haze-android:1.5.1")
                }
            }
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
    testOptions {
        unitTests.isReturnDefaultValues = true
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

abstract class VerifyDexIntegrityTask : DefaultTask() {
    @get:InputDirectory
    abstract val apkFolder: DirectoryProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun verify() {
        val dir = apkFolder.get().asFile
        val apkFiles = dir.listFiles { file -> file.isFile && file.name.endsWith(".apk") }
            ?: emptyArray()
        if (apkFiles.isEmpty()) {
            throw GradleException("verifyDebugApkDexIntegrity: No APK files found in directory: ${dir.absolutePath}")
        }
        for (apkFile in apkFiles) {
            verifyApk(apkFile)
        }
    }

    private fun verifyApk(apkFile: File) {
        ZipFile(apkFile).use { zip ->
            val dexEntries = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith("classes") && it.name.endsWith(".dex") }
                .toList()
            if (dexEntries.isEmpty()) {
                throw GradleException("verifyDebugApkDexIntegrity: No classes*.dex entries found in APK '${apkFile.name}'")
            }
            for (entry in dexEntries) {
                val dexBytes = zip.getInputStream(entry).use { it.readBytes() }
                verifyDexBytes(apkFile.name, entry.name, dexBytes)
            }
            logger.lifecycle("verifyDebugApkDexIntegrity: Verified ${dexEntries.size} DEX file(s) in '${apkFile.name}' successfully.")
        }
    }

    private fun verifyDexBytes(apkName: String, entryName: String, dexBytes: ByteArray) {
        if (dexBytes.size < 112) {
            throw GradleException(
                "verifyDebugApkDexIntegrity: DEX entry '$entryName' in APK '$apkName' is too small (${dexBytes.size} bytes < 112 bytes minimum header)"
            )
        }

        // 1. Magic & Version check (8 bytes: dex\n + 3 ascii digits + \0)
        val isMagicValid = dexBytes[0] == 0x64.toByte() &&
            dexBytes[1] == 0x65.toByte() &&
            dexBytes[2] == 0x78.toByte() &&
            dexBytes[3] == 0x0a.toByte() &&
            dexBytes[7] == 0x00.toByte()

        if (!isMagicValid) {
            val prefixHex = dexBytes.take(8).joinToString(" ") { "%02x".format(it) }
            throw GradleException(
                "verifyDebugApkDexIntegrity: DEX entry '$entryName' in APK '$apkName' has invalid magic bytes: [$prefixHex]"
            )
        }

        val version = String(dexBytes, 4, 3, Charsets.US_ASCII)
        if (!version.all { it.isDigit() }) {
            throw GradleException(
                "verifyDebugApkDexIntegrity: DEX entry '$entryName' in APK '$apkName' has invalid version: '$version'"
            )
        }

        // 2. Declared length check (offset 32..35, little-endian unsigned 32-bit int)
        val declaredLength = (dexBytes[32].toLong() and 0xFF) or
            ((dexBytes[33].toLong() and 0xFF) shl 8) or
            ((dexBytes[34].toLong() and 0xFF) shl 16) or
            ((dexBytes[35].toLong() and 0xFF) shl 24)

        if (declaredLength != dexBytes.size.toLong()) {
            throw GradleException(
                "verifyDebugApkDexIntegrity: DEX entry '$entryName' in APK '$apkName' declared length mismatch: header declares $declaredLength bytes, actual entry size is ${dexBytes.size} bytes"
            )
        }

        // 3. Adler-32 checksum check (offset 8..11, little-endian unsigned 32-bit int)
        val storedAdler = (dexBytes[8].toLong() and 0xFF) or
            ((dexBytes[9].toLong() and 0xFF) shl 8) or
            ((dexBytes[10].toLong() and 0xFF) shl 16) or
            ((dexBytes[11].toLong() and 0xFF) shl 24)

        val adler = Adler32()
        adler.update(dexBytes, 12, dexBytes.size - 12)
        val calculatedAdler = adler.value

        if (storedAdler != calculatedAdler) {
            throw GradleException(
                "verifyDebugApkDexIntegrity: DEX entry '$entryName' in APK '$apkName' Adler-32 checksum mismatch: stored=0x%08x, calculated=0x%08x"
                    .format(storedAdler, calculatedAdler)
            )
        }

        // 4. SHA-1 signature check (offset 12..31, 20 bytes)
        val storedSha1 = dexBytes.copyOfRange(12, 32)
        val md = MessageDigest.getInstance("SHA-1")
        md.update(dexBytes, 32, dexBytes.size - 32)
        val calculatedSha1 = md.digest()

        if (!storedSha1.contentEquals(calculatedSha1)) {
            val storedHex = storedSha1.joinToString("") { "%02x".format(it) }
            val calcHex = calculatedSha1.joinToString("") { "%02x".format(it) }
            throw GradleException(
                "verifyDebugApkDexIntegrity: DEX entry '$entryName' in APK '$apkName' SHA-1 signature mismatch: stored=$storedHex, calculated=$calcHex"
            )
        }
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.packaging.dex.useLegacyPackaging.set(true)
    }
    onVariants(selector().withBuildType("debug")) { variant ->
        val verifyTask = tasks.register<VerifyDexIntegrityTask>("verifyDebugApkDexIntegrity") {
            apkFolder.set(variant.artifacts.get(SingleArtifact.APK))
        }
        tasks.matching { it.name == "assembleDebug" }.configureEach {
            dependsOn(verifyTask)
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            // Bound memory usage and avoid the parallel JVM codegen race in this toolchain.
            "-Xbackend-threads=1"
        )
    }
}

configurations.all {
    // No Firebase service integration. Keep the encoder-only serialization
    // helpers required by MediaPipe's transport classes; removing them breaks R8.
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



