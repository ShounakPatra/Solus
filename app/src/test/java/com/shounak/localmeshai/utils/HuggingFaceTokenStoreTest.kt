package com.shounak.localmeshai.utils

import com.shounak.localmeshai.features.FeatureTestPreferences
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** JVM cipher fixture; production uses a non-exportable Android Keystore key. */
internal class TestTokenCipher : TokenCipher {
    private val key = SecretKeySpec(ByteArray(32) { 42 }, "AES")
    override fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()))
    }
    override fun decrypt(value: String): String {
        val bytes = Base64.getDecoder().decode(value)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
}

class HuggingFaceTokenStoreTest {
    @Test fun bothLegacyLocationsMigrateAndPlaintextIsRemoved() {
        for (legacy in listOf("hf_read_token", "hugging_face_read_token")) {
            val prefs = FeatureTestPreferences(mapOf(legacy to "old-token"))
            val store = HuggingFaceTokenStore(prefs, TestTokenCipher())
            assertEquals("old-token", store.read())
            assertFalse(prefs.contains(legacy))
            assertFalse(prefs.all.values.any { it == "old-token" })
            assertEquals("old-token", HuggingFaceTokenStore(prefs, TestTokenCipher()).read())
        }
    }

    @Test fun historicalDownloadTokenWinsWhenBothCopiesDiffer() {
        val prefs = FeatureTestPreferences(mapOf("hf_read_token" to "settings", "hugging_face_read_token" to "download"))
        assertEquals("download", HuggingFaceTokenStore(prefs, TestTokenCipher()).read())
        assertFalse(prefs.contains("hf_read_token")); assertFalse(prefs.contains("hugging_face_read_token"))
    }

    @Test fun clearSurvivesRestartAndDoesNotResurrectStaleLegacyToken() {
        val prefs = FeatureTestPreferences(mapOf("hf_read_token" to "old"))
        val store = HuggingFaceTokenStore(prefs, TestTokenCipher())
        store.read(); store.write("")
        prefs.edit().putString("hugging_face_read_token", "stale").commit()
        assertEquals("", HuggingFaceTokenStore(prefs, TestTokenCipher()).read())
        assertFalse(prefs.contains("hugging_face_read_token"))
    }

    @Test fun failedEncryptionPreservesLegacyAndDoesNotCrashStartup() {
        val prefs = FeatureTestPreferences(mapOf("hf_read_token" to "recoverable"))
        val broken = object : TokenCipher {
            override fun encrypt(value: String): String = error("Keystore unavailable")
            override fun decrypt(value: String): String = error("Keystore unavailable")
        }
        assertEquals("", HuggingFaceTokenStore(prefs, broken).read())
        assertEquals("recoverable", prefs.getString("hf_read_token", null))
        assertFalse(prefs.contains(HuggingFaceTokenStore.ENCRYPTED))
    }

    @Test fun corruptedCiphertextIsPreservedAndDoesNotCrashStartup() {
        val prefs = FeatureTestPreferences(mapOf(HuggingFaceTokenStore.ENCRYPTED to "broken"))
        assertEquals("", HuggingFaceTokenStore(prefs, TestTokenCipher()).read())
        assertEquals("broken", prefs.getString(HuggingFaceTokenStore.ENCRYPTED, null))
    }

    @Test fun settingsUseOneTokenAcrossUpdatesAndRestarts() {
        val prefs = FeatureTestPreferences()
        val app = AppSettings(prefs, HuggingFaceTokenStore(prefs, TestTokenCipher()))
        app.updateSettings { it.copy(huggingFaceToken = " new-token ") }
        app.updateSettings { it.copy(enableDarkMode = false) }
        assertEquals("new-token", app.settings.value.huggingFaceToken)
        assertEquals("new-token", AppSettings(prefs, HuggingFaceTokenStore(prefs, TestTokenCipher())).settings.value.huggingFaceToken)
        assertFalse(prefs.contains("hf_read_token")); assertFalse(prefs.contains("hugging_face_read_token"))
    }
}
