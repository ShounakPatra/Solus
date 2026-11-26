package com.shounak.localmeshai.ai

import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.os.Build
import com.shounak.localmeshai.BuildConfig
import com.shounak.localmeshai.models.ModelFormat
import com.shounak.localmeshai.utils.InitCrashGuard
import java.io.File
import java.util.Locale

enum class NpuVendor(val displayName: String) {
    QUALCOMM("Qualcomm Snapdragon"),
    MEDIATEK("MediaTek Dimensity"),
    NONE("Unsupported / Generic SoC")
}

enum class NpuBackendSupport {
    SUPPORTED,
    UNSUPPORTED_SOC,
    PROPRIETARY_SDK_DISABLED_IN_FOSS,
    NATIVE_LIBRARY_MISSING,
    CRASH_GUARD_BLACKLISTED
}

data class FormatNpuCapability(
    val supported: Boolean,
    val backendLabel: String,
    val status: NpuBackendSupport,
    val reason: String
)

data class NpuCapabilitySnapshot(
    val vendor: NpuVendor,
    val manufacturer: String,
    val chipsetName: String,
    val socModelRaw: String,
    val isNpuFlavorBuild: Boolean,
    val qnnLibrariesDetected: Boolean,
    val neuroPilotLibrariesDetected: Boolean,
    val litertLm: FormatNpuCapability,
    val gguf: FormatNpuCapability,
    val onnx: FormatNpuCapability,
    val summaryReason: String
) {
    val anyNpuSupported: Boolean
        get() = litertLm.supported || gguf.supported || onnx.supported

    val primaryBadgeLabel: String
        get() = when (vendor) {
            NpuVendor.QUALCOMM -> "Qualcomm NPU"
            NpuVendor.MEDIATEK -> "MediaTek NPU"
            NpuVendor.NONE -> "NPU"
        }

    fun capabilityForFormat(format: ModelFormat?): FormatNpuCapability = when (format) {
        ModelFormat.LiteRtLm, ModelFormat.MediaPipeTask -> litertLm
        ModelFormat.GGUF -> gguf
        ModelFormat.Onnx -> onnx
        else -> FormatNpuCapability(
            supported = false,
            backendLabel = "CPU",
            status = NpuBackendSupport.UNSUPPORTED_SOC,
            reason = "NPU acceleration is not configured for this model format."
        )
    }
}

/**
 * Hardware-probing detector for Qualcomm Snapdragon NPU (QNN / Hexagon HTP) and
 * MediaTek Dimensity NPU (NeuroPilot APU). Safely falls back across API 30-37 without crashing.
 */
object NpuCapabilityDetector {
    private val QUALCOMM_CHIPSET_REGEX = Regex("""\b(SM\d{4}[A-Z0-9-]*|SDM\d{3,4}|MSM\d{4}|QCS\d{4}|QCM\d{4})\b""", RegexOption.IGNORE_CASE)
    private val MEDIATEK_DIMENSITY_REGEX = Regex("""\b(MT68\d{2}[A-Z0-9]*|MT69\d{2}[A-Z0-9]*|MT8\d{3}[A-Z0-9]*)\b""", RegexOption.IGNORE_CASE)

    private val QNN_SEARCH_LIBS = listOf(
        "libQnnHtp.so",
        "libQnnSystem.so",
        "libQnnHtpPrepare.so",
        "libcdsprpc.so"
    )
    private val NEUROPILOT_SEARCH_LIBS = listOf(
        "libneuron_adapter.so",
        "libneuron_adapter_mgvi.so",
        "libneuropilot_hal_utils.so",
        "libapu_mdw.so"
    )

    fun detect(context: Context, modelIdForCrashCheck: String? = null): NpuCapabilitySnapshot {
        val (manufacturer, socModel) = readBuildSocProperties()
        val cpuInfoHw = readCpuInfoHardware()
        val roHardware = Build.HARDWARE.orEmpty()
        val nativeLibDir = context.applicationInfo?.nativeLibraryDir

        val hasQnnLibs = hasAnyVendorLibrary(nativeLibDir, QNN_SEARCH_LIBS)
        val hasNeuroPilotLibs = hasAnyVendorLibrary(nativeLibDir, NEUROPILOT_SEARCH_LIBS)
        val hasOrtQnnEp = probeOnnxQnnProvider()
        val npuCrashBlacklisted = modelIdForCrashCheck?.takeIf { it.isNotBlank() }?.let {
            InitCrashGuard.isNpuBlocked(context, it)
        } == true

        return evaluate(
            manufacturer = manufacturer,
            socModel = socModel,
            cpuInfoHardware = cpuInfoHw,
            roHardware = roHardware,
            isNpuFlavor = BuildConfig.NPU_FLAVOR_ENABLED,
            hasQnnLibs = hasQnnLibs,
            hasHexagonGgufBackend = BuildConfig.NPU_FLAVOR_ENABLED && BuildConfig.QNN_SDK_CONFIGURED,
            hasNeuroPilotLibs = hasNeuroPilotLibs,
            hasOrtQnnEp = hasOrtQnnEp,
            npuCrashBlacklisted = npuCrashBlacklisted
        )
    }

    /**
     * Pure deterministic evaluator suitable for host unit tests and runtime policy decisions.
     */
    fun evaluate(
        manufacturer: String,
        socModel: String,
        cpuInfoHardware: String = "",
        roHardware: String = "",
        isNpuFlavor: Boolean,
        hasQnnLibs: Boolean,
        hasHexagonGgufBackend: Boolean,
        hasNeuroPilotLibs: Boolean,
        hasOrtQnnEp: Boolean,
        npuCrashBlacklisted: Boolean = false
    ): NpuCapabilitySnapshot {
        val combined = listOf(manufacturer, socModel, cpuInfoHardware, roHardware)
            .filter { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
            .joinToString(" ")
        val lower = combined.lowercase(Locale.US)

        val qualcommMatch = QUALCOMM_CHIPSET_REGEX.find(combined)?.value?.uppercase(Locale.US)
        val mediatekMatch = MEDIATEK_DIMENSITY_REGEX.find(combined)?.value?.uppercase(Locale.US)

        val vendor = when {
            lower.contains("qualcomm") || lower.contains("qti") || lower.contains("qcom") ||
                lower.contains("snapdragon") || qualcommMatch != null -> NpuVendor.QUALCOMM
            lower.contains("mediatek") || lower.contains("mtk") ||
                lower.contains("dimensity") || mediatekMatch != null -> NpuVendor.MEDIATEK
            else -> NpuVendor.NONE
        }

        val resolvedRawModel = socModel.ifBlank { qualcommMatch ?: mediatekMatch ?: roHardware }.trim()
        val chipsetName = when (vendor) {
            NpuVendor.QUALCOMM -> {
                val label = resolvedRawModel.ifBlank { "Snapdragon" }
                if (label.contains("Qualcomm", ignoreCase = true)) label else "Qualcomm $label"
            }
            NpuVendor.MEDIATEK -> {
                val label = resolvedRawModel.ifBlank { "Dimensity" }
                if (label.contains("MediaTek", ignoreCase = true)) label else "MediaTek $label"
            }
            NpuVendor.NONE -> combined.ifBlank { "Generic ARM64 SoC" }
        }

        val qcomNpuChip = qualcommMatch == null ||
            qualcommMatch.startsWith("SM8") ||
            qualcommMatch.startsWith("SM7") ||
            qualcommMatch.startsWith("SM6") ||
            qualcommMatch.startsWith("QCS") ||
            qualcommMatch.startsWith("QCM")

        val mtkNpuChip = mediatekMatch == null ||
            mediatekMatch.startsWith("MT68") ||
            mediatekMatch.startsWith("MT69") ||
            mediatekMatch.startsWith("MT8")

        val litertCap = evaluateLiteRt(vendor, qcomNpuChip, mtkNpuChip, isNpuFlavor, hasQnnLibs, hasNeuroPilotLibs, npuCrashBlacklisted)
        val ggufCap = evaluateGguf(vendor, qcomNpuChip, isNpuFlavor, hasHexagonGgufBackend, npuCrashBlacklisted)
        val onnxCap = evaluateOnnx(vendor, qcomNpuChip, isNpuFlavor, hasQnnLibs, hasOrtQnnEp, npuCrashBlacklisted)

        val summary = when {
            npuCrashBlacklisted -> "NPU disabled by crash guard after an earlier native driver failure; falling back to Vulkan GPU / ARM CPU."
            vendor == NpuVendor.NONE -> "SoC ($chipsetName) uses Vulkan GPU & tuned ARM CPU (no QNN/NeuroPilot NPU)."
            !isNpuFlavor -> "FOSS build excludes proprietary vendor NPU drivers; using open-source Vulkan GPU & ARM CPU."
            litertCap.supported || ggufCap.supported || onnxCap.supported ->
                "${when (vendor) { NpuVendor.QUALCOMM -> "Qualcomm QNN / Hexagon HTP"; NpuVendor.MEDIATEK -> "MediaTek NeuroPilot APU"; else -> "NPU" }} ready on $chipsetName."
            else -> litertCap.reason
        }

        return NpuCapabilitySnapshot(
            vendor = vendor,
            manufacturer = manufacturer,
            chipsetName = chipsetName,
            socModelRaw = resolvedRawModel,
            isNpuFlavorBuild = isNpuFlavor,
            qnnLibrariesDetected = hasQnnLibs,
            neuroPilotLibrariesDetected = hasNeuroPilotLibs,
            litertLm = litertCap,
            gguf = ggufCap,
            onnx = onnxCap,
            summaryReason = summary
        )
    }

    private fun evaluateLiteRt(
        vendor: NpuVendor,
        qcomNpuChip: Boolean,
        mtkNpuChip: Boolean,
        isNpuFlavor: Boolean,
        hasQnnLibs: Boolean,
        hasNeuroPilotLibs: Boolean,
        npuCrashBlacklisted: Boolean
    ): FormatNpuCapability {
        if (npuCrashBlacklisted) {
            return FormatNpuCapability(false, "Vulkan GPU / CPU", NpuBackendSupport.CRASH_GUARD_BLACKLISTED, "NPU crashed previously on this model/device; blacklisted to prevent crashes.")
        }
        if (vendor == NpuVendor.NONE) {
            return FormatNpuCapability(false, "GPU / CPU", NpuBackendSupport.UNSUPPORTED_SOC, "Device SoC does not expose Qualcomm QNN or MediaTek NeuroPilot NPU.")
        }
        if (!isNpuFlavor) {
            return FormatNpuCapability(false, "GPU / CPU", NpuBackendSupport.PROPRIETARY_SDK_DISABLED_IN_FOSS, "FOSS flavor keeps builds 100% open-source; build the 'npu' flavor to enable Backend.NPU.")
        }
        return when (vendor) {
            NpuVendor.QUALCOMM -> when {
                !qcomNpuChip -> FormatNpuCapability(false, "GPU / CPU", NpuBackendSupport.UNSUPPORTED_SOC, "This Snapdragon tier lacks Hexagon HTP LLM acceleration.")
                !hasQnnLibs -> FormatNpuCapability(false, "GPU / CPU", NpuBackendSupport.NATIVE_LIBRARY_MISSING, "Qualcomm QNN runtime libraries (libQnnHtp.so / libcdsprpc.so) were not found on device or in NPU SDK jniLibs.")
                else -> FormatNpuCapability(true, "Qualcomm NPU", NpuBackendSupport.SUPPORTED, "LiteRT-LM Backend.NPU via Qualcomm QNN HTP.")
            }
            NpuVendor.MEDIATEK -> when {
                !mtkNpuChip -> FormatNpuCapability(false, "GPU / CPU", NpuBackendSupport.UNSUPPORTED_SOC, "MediaTek SoC is not a Dimensity MT68xx/MT69xx APU generation.")
                !hasNeuroPilotLibs -> FormatNpuCapability(false, "GPU / CPU", NpuBackendSupport.NATIVE_LIBRARY_MISSING, "MediaTek NeuroPilot libraries (libneuron_adapter.so) were not found on device or in NPU SDK jniLibs.")
                else -> FormatNpuCapability(true, "MediaTek NPU", NpuBackendSupport.SUPPORTED, "LiteRT-LM Backend.NPU via MediaTek NeuroPilot APU.")
            }
            NpuVendor.NONE -> FormatNpuCapability(false, "GPU / CPU", NpuBackendSupport.UNSUPPORTED_SOC, "Unsupported SoC.")
        }
    }

    private fun evaluateGguf(
        vendor: NpuVendor,
        qcomNpuChip: Boolean,
        isNpuFlavor: Boolean,
        hasHexagonGgufBackend: Boolean,
        npuCrashBlacklisted: Boolean
    ): FormatNpuCapability {
        if (npuCrashBlacklisted) {
            return FormatNpuCapability(false, "Vulkan GPU / CPU", NpuBackendSupport.CRASH_GUARD_BLACKLISTED, "GGUF NPU crashed previously; falling back to Vulkan GPU / ARM CPU.")
        }
        if (!isNpuFlavor) {
            return FormatNpuCapability(false, "Vulkan GPU / CPU", NpuBackendSupport.PROPRIETARY_SDK_DISABLED_IN_FOSS, "GGUF uses GGML_VULKAN and ARM dotprod CPU in FOSS builds.")
        }
        return when (vendor) {
            NpuVendor.QUALCOMM -> when {
                !qcomNpuChip -> FormatNpuCapability(false, "Vulkan GPU / CPU", NpuBackendSupport.UNSUPPORTED_SOC, "Qualcomm Hexagon HTP backend requires SM6xxx/7xxx/8xxx class.")
                !hasHexagonGgufBackend -> FormatNpuCapability(false, "Vulkan GPU / CPU", NpuBackendSupport.NATIVE_LIBRARY_MISSING, "Hexagon SDK not configured during build (set hexagon.sdk.dir & hexagon.tools.dir in local.properties).")
                else -> FormatNpuCapability(true, "Qualcomm NPU", NpuBackendSupport.SUPPORTED, "llama.cpp GGML Hexagon HTP NPU backend.")
            }
            NpuVendor.MEDIATEK -> FormatNpuCapability(false, "Vulkan GPU / CPU", NpuBackendSupport.UNSUPPORTED_SOC, "llama.cpp has no universal MediaTek NeuroPilot GGUF backend; Solus routes GGUF to Vulkan GPU / tuned ARM CPU.")
            NpuVendor.NONE -> FormatNpuCapability(false, "Vulkan GPU / CPU", NpuBackendSupport.UNSUPPORTED_SOC, "GGUF routes to Vulkan GPU or tuned ARM CPU.")
        }
    }

    private fun evaluateOnnx(
        vendor: NpuVendor,
        qcomNpuChip: Boolean,
        isNpuFlavor: Boolean,
        hasQnnLibs: Boolean,
        hasOrtQnnEp: Boolean,
        npuCrashBlacklisted: Boolean
    ): FormatNpuCapability {
        if (npuCrashBlacklisted) {
            return FormatNpuCapability(false, "XNNPACK CPU", NpuBackendSupport.CRASH_GUARD_BLACKLISTED, "ONNX NPU crashed previously; using XNNPACK CPU.")
        }
        if (!isNpuFlavor) {
            return FormatNpuCapability(false, "XNNPACK CPU", NpuBackendSupport.PROPRIETARY_SDK_DISABLED_IN_FOSS, "ONNX Runtime uses XNNPACK (4 threads) in FOSS builds.")
        }
        return when (vendor) {
            NpuVendor.QUALCOMM -> when {
                !qcomNpuChip -> FormatNpuCapability(false, "XNNPACK CPU", NpuBackendSupport.UNSUPPORTED_SOC, "Qualcomm QNN EP requires SM6xxx/7xxx/8xxx Hexagon HTP.")
                hasQnnLibs && hasOrtQnnEp -> FormatNpuCapability(true, "Qualcomm NPU", NpuBackendSupport.SUPPORTED, "ONNX Runtime QNN Execution Provider.")
                else -> FormatNpuCapability(false, "XNNPACK CPU", NpuBackendSupport.NATIVE_LIBRARY_MISSING, "ORT QNN EP unavailable in current runtime; using XNNPACK (4 threads).")
            }
            NpuVendor.MEDIATEK -> FormatNpuCapability(false, "XNNPACK CPU", NpuBackendSupport.UNSUPPORTED_SOC, "MediaTek ONNX models default to XNNPACK (4 performance threads) for mixed int8/fp16 stability.")
            NpuVendor.NONE -> FormatNpuCapability(false, "XNNPACK CPU", NpuBackendSupport.UNSUPPORTED_SOC, "ONNX Runtime uses XNNPACK (4 performance threads).")
        }
    }

    private fun readBuildSocProperties(): Pair<String, String> {
        var manufacturer = ""
        var model = ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manufacturer = Build.SOC_MANUFACTURER.orEmpty().takeUnless { it.equals(Build.UNKNOWN, ignoreCase = true) }.orEmpty()
            model = Build.SOC_MODEL.orEmpty().takeUnless { it.equals(Build.UNKNOWN, ignoreCase = true) }.orEmpty()
        }
        if (manufacturer.isBlank()) manufacturer = readSysProp("ro.soc.manufacturer")
        if (model.isBlank()) {
            model = readSysProp("ro.soc.model")
                .ifBlank { readSysProp("ro.mediatek.platform") }
                .ifBlank { readSysProp("ro.vendor.mediatek.platform") }
                .ifBlank { readSysProp("ro.board.platform") }
        }
        return manufacturer to model
    }

    private fun readSysProp(key: String): String = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        val method = cls.getMethod("get", String::class.java, String::class.java)
        (method.invoke(null, key, "") as? String)?.trim().orEmpty()
    }.getOrDefault("")

    private fun readCpuInfoHardware(): String = runCatching {
        File("/proc/cpuinfo").useLines { lines ->
            lines.firstOrNull { line ->
                line.startsWith("Hardware", ignoreCase = true) || line.startsWith("Model", ignoreCase = true)
            }?.substringAfter(":")?.trim().orEmpty()
        }
    }.getOrDefault("")

    private fun hasAnyVendorLibrary(appNativeLibDir: String?, libNames: List<String>): Boolean {
        val dirs = buildList {
            if (!appNativeLibDir.isNullOrBlank()) add(appNativeLibDir)
            add("/vendor/lib64")
            add("/vendor/lib64/egl")
            add("/system/vendor/lib64")
            add("/odm/lib64")
        }
        return dirs.any { dir ->
            libNames.any { lib ->
                runCatching { File(dir, lib).exists() }.getOrDefault(false)
            }
        }
    }

    private fun probeOnnxQnnProvider(): Boolean = runCatching {
        OrtEnvironment.getAvailableProviders().any { provider ->
            provider.name.contains("QNN", ignoreCase = true)
        }
    }.getOrDefault(false)
}
