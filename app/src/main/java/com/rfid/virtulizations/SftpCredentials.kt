
package com.rfid.virtulizations

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secure SFTP credential storage.
 *
 * AES/GCM encryption using Android Keystore.
 * Never falls back to a hardcoded password.
 */
class SftpCredentials(context: Context) {

    companion object {
        private const val TAG = "SftpCredentials"
        private const val PREF_NAME = "sftp_credentials"
        private const val PASSWORD_KEY = "password"
        private const val IV_KEY = "iv"
        private const val KEY_ALIAS = "rfid-sftp-password"

        fun fingerprint(password: String?): String? {
            if (password.isNullOrBlank()) return null

            val digest = MessageDigest.getInstance("SHA-256")
                .digest(password.toByteArray(Charsets.UTF_8))

            return digest.joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }
        }
    }

    private val appContext = context.applicationContext

    private val prefs = appContext.getSharedPreferences(
        PREF_NAME,
        Context.MODE_PRIVATE
    )

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
        }

        val existingKey = store.getKey(KEY_ALIAS, null) as? SecretKey

        if (existingKey != null) {
            return existingKey
        }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )

        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or
                        KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(
                    KeyProperties.ENCRYPTION_PADDING_NONE
                )
                .setRandomizedEncryptionRequired(true)
                .build()
        )

        return generator.generateKey()
    }

    /**
     * Encrypt and persist the supplied password.
     */
    @Synchronized
    fun save(password: String) {
        require(password.isNotBlank()) {
            "Enter the SFTP password"
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        cipher.init(
            Cipher.ENCRYPT_MODE,
            getOrCreateKey()
        )

        val encryptedBytes = cipher.doFinal(
            password.toByteArray(Charsets.UTF_8)
        )

        val encodedIv = Base64.encodeToString(
            cipher.iv,
            Base64.NO_WRAP
        )

        val encryptedPassword = Base64.encodeToString(
            encryptedBytes,
            Base64.NO_WRAP
        )

        val saved = prefs.edit()
            .putString(IV_KEY, encodedIv)
            .putString(PASSWORD_KEY, encryptedPassword)
            .commit()

        check(saved) {
            "Could not save SFTP credentials"
        }
    }

    /**
     * Return the decrypted password.
     *
     * An empty string indicates missing or unreadable credentials.
     * Never silently uses a hardcoded password.
     */
    @Synchronized
    fun read(): String {
        return try {
            val encrypted = prefs.getString(PASSWORD_KEY, null)
                ?: return ""

            val encodedIv = prefs.getString(IV_KEY, null)
                ?: return ""

            val store = KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
            }

            val secretKey = store.getKey(
                KEY_ALIAS,
                null
            ) as? SecretKey ?: return ""

            val iv = Base64.decode(
                encodedIv,
                Base64.NO_WRAP
            )

            val encryptedBytes = Base64.decode(
                encrypted,
                Base64.NO_WRAP
            )

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")

            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey,
                GCMParameterSpec(128, iv)
            )

            val decrypted = cipher.doFinal(encryptedBytes)

            String(decrypted, Charsets.UTF_8).also {
                if (it.isBlank()) {
                    Log.w(TAG, "Stored SFTP password is empty")
                }
            }
        } catch (error: Exception) {
            Log.e(
                TAG,
                "Unable to decrypt stored SFTP credentials",
                error
            )
            ""
        }
    }

    fun hasSavedCredentials(): Boolean {
        return !prefs.getString(PASSWORD_KEY, null).isNullOrBlank() &&
                !prefs.getString(IV_KEY, null).isNullOrBlank()
    }

    /**
     * Clear the saved credentials and remove the Keystore key.
     * Call this only when intentionally resetting credentials.
     */
    @Synchronized
    fun clear() {
        check(prefs.edit().clear().commit()) {
            "Could not clear saved SFTP credentials"
        }

        try {
            val store = KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
            }

            if (store.containsAlias(KEY_ALIAS)) {
                store.deleteEntry(KEY_ALIAS)
            }
        } catch (error: Exception) {
            Log.e(TAG, "Could not remove the Keystore key", error)
            throw error
        }
    }
}
