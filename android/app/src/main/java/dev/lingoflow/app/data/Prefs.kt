package dev.lingoflow.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secrets (API keys) with an Android Keystore key.
 * Falls back to plain preferences when the keystore is unavailable (some
 * emulators) — in that case the value still never leaves the app sandbox.
 */
object SecretStore {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "lingoflow-secrets"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun encrypt(value: String): String {
        if (value.isEmpty()) return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val encrypted = cipher.doFinal(value.toByteArray())
            val combined = cipher.iv + encrypted
            "v1:" + Base64.encodeToString(combined, Base64.NO_WRAP)
        }.getOrDefault("plain:$value")
    }

    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        if (stored.startsWith("plain:")) return stored.removePrefix("plain:")
        if (!stored.startsWith("v1:")) return stored
        return runCatching {
            val combined = Base64.decode(stored.removePrefix("v1:"), Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, 12)
            val payload = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(payload).decodeToString()
        }.getOrDefault("")
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}

/** App settings + recent projects. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("lingoflow", Context.MODE_PRIVATE)

    var engine: String
        get() = prefs.getString("engine", "offline") ?: "offline"
        set(value) = prefs.edit().putString("engine", value).apply()

    var offlineOnly: Boolean
        get() = prefs.getBoolean("offlineOnly", true)
        set(value) = prefs.edit().putBoolean("offlineOnly", value).apply()

    var endpointUrl: String
        get() = prefs.getString("endpointUrl", "https://api.openai.com/v1") ?: ""
        set(value) = prefs.edit().putString("endpointUrl", value).apply()

    var model: String
        get() = prefs.getString("model", "gpt-4o-mini") ?: ""
        set(value) = prefs.edit().putString("model", value).apply()

    var apiKey: String
        get() = SecretStore.decrypt(prefs.getString("apiKey", "") ?: "")
        set(value) = prefs.edit().putString("apiKey", SecretStore.encrypt(value)).apply()

    var customBodyTemplate: String
        get() = prefs.getString("customBody", """{"text": {{json.text}}, "source": {{json.source}}, "target": {{json.target}}}""")
            ?: ""
        set(value) = prefs.edit().putString("customBody", value).apply()

    var customResponsePath: String
        get() = prefs.getString("customResponsePath", "translatedText") ?: ""
        set(value) = prefs.edit().putString("customResponsePath", value).apply()

    var glossarProtected: Boolean
        get() = prefs.getBoolean("glossaryProtect", true)
        set(value) = prefs.edit().putBoolean("glossaryProtect", value).apply()

    fun recentProjects(): List<Pair<String, String>> {
        val raw = prefs.getString("recent", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val parts = line.split("\u0001")
            if (parts.size >= 2) parts[0] to parts[1] else null
        }
    }

    fun rememberProject(id: String, name: String) {
        val kept = (listOf(id to name) + recentProjects().filterNot { it.first == id }).take(8)
        prefs.edit().putString("recent", kept.joinToString("\n") { "${it.first}\u0001${it.second}" }).apply()
    }

    fun forgetProject(id: String) {
        val kept = recentProjects().filterNot { it.first == id }
        prefs.edit().putString("recent", kept.joinToString("\n") { "${it.first}\u0001${it.second}" }).apply()
    }
}
