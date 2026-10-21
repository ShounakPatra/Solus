package com.shounak.localmeshai.utils

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface TokenCipher {
    fun encrypt(value: String): String
    fun decrypt(value: String): String
}

internal class KeystoreTokenCipher : TokenCipher {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    override fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.getEncoder().encodeToString(cipher.iv) + ":" +
            Base64.getEncoder().encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
    }

    override fun decrypt(value: String): String {
        val parts = value.split(':', limit = 2)
        require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])))
        return cipher.doFinal(Base64.getDecoder().decode(parts[1])).toString(Charsets.UTF_8)
    }

    private companion object { const val ALIAS = "solus_hugging_face_token_v1" }
}

/** One encrypted, non-portable token; legacy plaintext is removed only after a committed write. */
internal class HuggingFaceTokenStore(
    private val prefs: SharedPreferences,
    private val cipher: TokenCipher = KeystoreTokenCipher()
) {
    @Synchronized fun read(): String {
        // A device/key failure must not crash startup or delete the recoverable ciphertext.
        return runCatching {
            if (prefs.contains(ENCRYPTED)) {
                val token = cipher.decrypt(prefs.getString(ENCRYPTED, "").orEmpty())
                clearLegacy()
                token
            } else {
                val token = prefs.getString(LEGACY_DOWNLOAD, "").orEmpty().ifBlank {
                    prefs.getString(LEGACY_SETTINGS, "").orEmpty()
                }.trim()
                if (prefs.contains(LEGACY_DOWNLOAD) || prefs.contains(LEGACY_SETTINGS)) write(token)
                token
            }
        }.getOrDefault("")
    }

    @Synchronized fun write(token: String) {
        val encrypted = cipher.encrypt(token.trim())
        check(prefs.edit().putString(ENCRYPTED, encrypted)
            .remove(LEGACY_SETTINGS).remove(LEGACY_DOWNLOAD).commit()) { "Could not save access token" }
    }

    private fun clearLegacy() {
        if (prefs.contains(LEGACY_SETTINGS) || prefs.contains(LEGACY_DOWNLOAD)) {
            check(prefs.edit().remove(LEGACY_SETTINGS).remove(LEGACY_DOWNLOAD).commit())
        }
    }

    internal companion object {
        const val ENCRYPTED = "hf_token_encrypted_v1"
        const val LEGACY_SETTINGS = "hf_read_token"
        const val LEGACY_DOWNLOAD = "hugging_face_read_token"
    }
}
