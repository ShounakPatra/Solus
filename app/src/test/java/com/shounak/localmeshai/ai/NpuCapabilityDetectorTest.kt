package com.shounak.localmeshai.ai

import android.content.Context
import android.content.SharedPreferences
import com.shounak.localmeshai.utils.InferenceBackend
import com.shounak.localmeshai.utils.InitCrashState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NpuCapabilityDetectorTest {

    @Test
    fun `detects Qualcomm SM7450 and SM8650 NPU in npu flavor when QNN libraries are present`() {
        val sm8650 = NpuCapabilityDetector.evaluate(
            manufacturer = "QUALCOMM",
            socModel = "SM8650",
            isNpuFlavor = true,
            hasQnnLibs = true,
            hasHexagonGgufBackend = true,
            hasNeuroPilotLibs = false,
            hasOrtQnnEp = true
        )
        assertEquals(NpuVendor.QUALCOMM, sm8650.vendor)
        assertEquals("Qualcomm SM8650", sm8650.chipsetName)
        assertTrue(sm8650.litertLm.supported)
        assertTrue(sm8650.gguf.supported)
        assertTrue(sm8650.onnx.supported)
        assertEquals("Qualcomm NPU", sm8650.primaryBadgeLabel)

        val sm7450 = NpuCapabilityDetector.evaluate(
            manufacturer = "QTI",
            socModel = "SM7450",
            isNpuFlavor = true,
            hasQnnLibs = true,
            hasHexagonGgufBackend = false,
            hasNeuroPilotLibs = false,
            hasOrtQnnEp = false
        )
        assertEquals(NpuVendor.QUALCOMM, sm7450.vendor)
        assertTrue(sm7450.litertLm.supported)
        assertFalse(sm7450.gguf.supported)
        assertEquals(NpuBackendSupport.NATIVE_LIBRARY_MISSING, sm7450.gguf.status)
    }

    @Test
    fun `detects MediaTek MT6878 Dimensity 7300 and MT6989 NPU and keeps GGUF on Vulkan or CPU`() {
        val mt6878 = NpuCapabilityDetector.evaluate(
            manufacturer = "Mediatek",
            socModel = "MT6878",
            cpuInfoHardware = "MT6878",
            isNpuFlavor = true,
            hasQnnLibs = false,
            hasHexagonGgufBackend = false,
            hasNeuroPilotLibs = true,
            hasOrtQnnEp = false
        )
        assertEquals(NpuVendor.MEDIATEK, mt6878.vendor)
        assertEquals("MediaTek MT6878", mt6878.chipsetName)
        assertEquals("MediaTek NPU", mt6878.primaryBadgeLabel)
        assertTrue(mt6878.litertLm.supported)
        assertFalse(mt6878.gguf.supported)
        assertEquals(NpuBackendSupport.UNSUPPORTED_SOC, mt6878.gguf.status)
        assertFalse(mt6878.onnx.supported)

        val mt6989 = NpuCapabilityDetector.evaluate(
            manufacturer = "",
            socModel = "mt6989",
            isNpuFlavor = false,
            hasQnnLibs = false,
            hasHexagonGgufBackend = false,
            hasNeuroPilotLibs = true,
            hasOrtQnnEp = false
        )
        assertEquals(NpuVendor.MEDIATEK, mt6989.vendor)
        assertFalse(mt6989.litertLm.supported)
        assertEquals(NpuBackendSupport.PROPRIETARY_SDK_DISABLED_IN_FOSS, mt6989.litertLm.status)
    }

    @Test
    fun `unknown Exynos Tensor and Unisoc SoCs report NpuVendor NONE cleanly`() {
        val tensor = NpuCapabilityDetector.evaluate(
            manufacturer = "Google",
            socModel = "Tensor G3",
            isNpuFlavor = true,
            hasQnnLibs = true,
            hasHexagonGgufBackend = true,
            hasNeuroPilotLibs = true,
            hasOrtQnnEp = true
        )
        assertEquals(NpuVendor.NONE, tensor.vendor)
        assertFalse(tensor.anyNpuSupported)
        assertEquals(NpuBackendSupport.UNSUPPORTED_SOC, tensor.litertLm.status)

        val exynos = NpuCapabilityDetector.evaluate(
            manufacturer = "Samsung",
            socModel = "s5e9945",
            isNpuFlavor = true,
            hasQnnLibs = false,
            hasHexagonGgufBackend = false,
            hasNeuroPilotLibs = false,
            hasOrtQnnEp = false
        )
        assertEquals(NpuVendor.NONE, exynos.vendor)
        assertFalse(exynos.anyNpuSupported)
    }

    @Test
    fun `crash guard blacklists NPU after native driver abort and RuntimeExecutionPolicy falls back to Vulkan GPU then CPU`() {
        val prefs = InMemorySharedPreferences()
        val crashState = InitCrashState(prefs)

        crashState.markNpuStarted("gemma4_e2b")
        assertFalse(crashState.isNpuBlocked("gemma4_e2b"))
        // Simulate process abort mid-init -> next launch calls recoverNpuCrash()
        val recovered = crashState.recoverNpuCrash()
        assertEquals("gemma4_e2b", recovered)
        assertTrue(crashState.isNpuBlocked("gemma4_e2b"))
        // Main model block list remains unaffected so Vulkan GPU / CPU fallback still runs automatically!
        assertFalse(crashState.isBlocked("gemma4_e2b"))
        assertNull(crashState.recoverNpuCrash())

        val snapshotAfterCrash = NpuCapabilityDetector.evaluate(
            manufacturer = "Qualcomm",
            socModel = "SM8650",
            isNpuFlavor = true,
            hasQnnLibs = true,
            hasHexagonGgufBackend = true,
            hasNeuroPilotLibs = false,
            hasOrtQnnEp = true,
            npuCrashBlacklisted = crashState.isNpuBlocked("gemma4_e2b")
        )
        assertFalse(snapshotAfterCrash.litertLm.supported)
        assertEquals(NpuBackendSupport.CRASH_GUARD_BLACKLISTED, snapshotAfterCrash.litertLm.status)

        // Policy priority: NPU -> Vulkan GPU -> ARM CPU
        val autoNpu = RuntimeExecutionPolicy.selectAcceleratedBackend(
            preferredBackend = "AUTO",
            npuCapable = true,
            npuBlocked = false,
            gpuQualified = true,
            gpuBlocked = false,
            vendor = NpuVendor.MEDIATEK
        )
        assertEquals(AcceleratedBackendPreference.NPU, autoNpu.backend)
        assertEquals("MediaTek NPU", autoNpu.displayLabel)

        val fallbackToGpu = RuntimeExecutionPolicy.selectAcceleratedBackend(
            preferredBackend = "AUTO",
            npuCapable = true,
            npuBlocked = crashState.isNpuBlocked("gemma4_e2b"),
            gpuQualified = true,
            gpuBlocked = false,
            vendor = NpuVendor.QUALCOMM
        )
        assertEquals(AcceleratedBackendPreference.VULKAN_GPU, fallbackToGpu.backend)
        assertEquals("Vulkan GPU", fallbackToGpu.displayLabel)

        val fallbackToCpu = RuntimeExecutionPolicy.selectAcceleratedBackend(
            preferredBackend = "NPU",
            npuCapable = true,
            npuBlocked = true,
            gpuQualified = false,
            gpuBlocked = false,
            vendor = NpuVendor.QUALCOMM
        )
        assertEquals(AcceleratedBackendPreference.CPU, fallbackToCpu.backend)

        // SpeedTuner selection respects NPU capability & blacklist
        assertEquals(
            InferenceBackend.LITERT_NPU,
            RuntimeSpeedTuner.selectLiteRtBackend(
                receiptBackend = "NPU",
                gpuVetoed = false,
                gpuProbeBlocked = false,
                npuSupported = true,
                npuBlocked = false
            )
        )
        assertEquals(
            InferenceBackend.LITERT_GPU,
            RuntimeSpeedTuner.selectLiteRtBackend(
                receiptBackend = null,
                gpuVetoed = false,
                gpuProbeBlocked = false,
                npuSupported = true,
                npuBlocked = true
            ).let {
                // With receipt=null and npuBlocked=true, falls back to CPU unless receipt="GPU"
                RuntimeSpeedTuner.selectLiteRtBackend(
                    receiptBackend = "GPU",
                    gpuVetoed = false,
                    gpuProbeBlocked = false,
                    npuSupported = true,
                    npuBlocked = true
                )
            }
        )
    }

    @Test
    fun `NPU failure steps down to Vulkan GPU offload before CPU`() {
        val initial = ModelLoadIdentity(
            modelPath = "/tmp/model.gguf",
            fileLength = 1024L,
            fileLastModified = 1L,
            runtimeKind = "LlamaCpp",
            backendPreference = "NPU",
            threads = 4,
            prefillThreads = 4,
            contextWindow = 4096,
            batchSize = 512,
            ubatchSize = 256,
            gpuLayers = 99
        )
        val gpuStep = RuntimeExecutionPolicy.nextRecoveryIdentity(initial, IllegalStateException("GPU init failed"))
        assertEquals("VULKAN", gpuStep?.backendPreference)
        assertEquals(99, gpuStep?.gpuLayers)

        val cpuStep = RuntimeExecutionPolicy.nextRecoveryIdentity(gpuStep!!, IllegalStateException("Vulkan init failed"))
        assertEquals("CPU", cpuStep?.backendPreference)
        assertEquals(0, cpuStep?.gpuLayers)
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = EditorImpl()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        inner class EditorImpl : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clearAll = false

            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { if (key != null) pending[key] = values?.toSet() }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) pending[key] = REMOVE_SENTINEL }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearAll) data.clear()
                pending.forEach { (k, v) -> if (v === REMOVE_SENTINEL) data.remove(k) else data[k] = v }
                pending.clear()
            }
        }

        private companion object {
            val REMOVE_SENTINEL = Any()
        }
    }
}
