package home.olvii

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Connection configuration saved only when the user enables home availability. */
data class HomeConfig(val address: String, val name: String, val key: String)

/** Stores the access key encrypted by a non-exportable Android Keystore key. */
class HomeSettings(context: Context) {
    private val prefs = context.getSharedPreferences("olvii", Context.MODE_PRIVATE)
    private val alias = "olvii.home-access"

    /** Stable device identity, shared with earlier foreground-only installations. */
    val id: String =
        prefs.getString("id", null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString("id", it).apply() }

    /** Whether the user explicitly enabled the foreground connection service. */
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) {
            prefs.edit().putBoolean("enabled", value).apply()
        }

    /** Restores connection fields; an unreadable saved key becomes an empty key. */
    fun load(): HomeConfig {
        val address = prefs.getString("server", "http://192.168.1.96:5080")!!
        val name = prefs.getString("name", android.os.Build.MODEL)!!
        val key = runCatching { decrypt() }.getOrDefault("")
        return HomeConfig(address, name, key)
    }

    /** Saves credentials for service recreation; plaintext is never written to preferences. */
    fun save(config: HomeConfig) {
        // Generate a fresh IV for each write.
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret())
        val encrypted = cipher.doFinal(config.key.toByteArray(Charsets.UTF_8))

        // Commit before starting a service that may be recreated independently.
        val edit = prefs.edit().putString("server", config.address).putString("name", config.name)
        edit.putString("key_iv", encode(cipher.iv)).putString("key_cipher", encode(encrypted))
        check(edit.commit()) { "Cannot save connection settings" }
    }

    private fun decrypt(): String {
        val encrypted = prefs.getString("key_cipher", null) ?: return ""
        val iv = prefs.getString("key_iv", null) ?: return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, decode(iv)))
        return cipher.doFinal(decode(encrypted)).toString(Charsets.UTF_8)
    }

    private fun secret(): SecretKey {
        // Reuse the app-owned key across service and activity recreation.
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let {
            return it
        }

        // No biometric prompt is required to receive a background call.
        val purpose = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        val spec =
            KeyGenParameterSpec.Builder(alias, purpose)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(spec)
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)
}
