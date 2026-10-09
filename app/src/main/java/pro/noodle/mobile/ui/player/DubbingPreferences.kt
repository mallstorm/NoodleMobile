package pro.noodle.mobile.ui.player

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal enum class DubbingMode(val id: String) {
    VOICE_OVER("voice_over"),
    DUB("dub");

    companion object {
        fun fromId(value: String): DubbingMode =
            entries.firstOrNull { it.id == value } ?: VOICE_OVER
    }
}

internal data class DubbingConfig(
    val endpoint: String = DEFAULT_ENDPOINT,
    val model: String = DEFAULT_MODEL,
    val mode: DubbingMode = DubbingMode.VOICE_OVER,
    val voiceName: String = "",
) {
    companion object {
        const val DEFAULT_ENDPOINT = "https://api.openai.com/v1/audio/transcriptions"
        const val DEFAULT_MODEL = "gpt-4o-transcribe-diarize"
    }
}

/**
 * Stores non-secret RU dubbing options in private SharedPreferences and the ASR token encrypted
 * with an Android Keystore AES/GCM key. The plaintext token is never written to disk.
 */
internal class DubbingPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): DubbingConfig = DubbingConfig(
        endpoint = prefs.getString(KEY_ENDPOINT, null).orEmpty().ifBlank { DubbingConfig.DEFAULT_ENDPOINT },
        model = prefs.getString(KEY_MODEL, null).orEmpty().ifBlank { DubbingConfig.DEFAULT_MODEL },
        mode = DubbingMode.fromId(prefs.getString(KEY_MODE, null).orEmpty()),
        voiceName = prefs.getString(KEY_VOICE, "").orEmpty(),
    )

    fun save(config: DubbingConfig, apiKey: String? = null) {
        prefs.edit()
            .putString(KEY_ENDPOINT, config.endpoint.trim())
            .putString(KEY_MODEL, config.model.trim())
            .putString(KEY_MODE, config.mode.id)
            .putString(KEY_VOICE, config.voiceName)
            .apply()

        if (apiKey != null) {
            val trimmed = apiKey.trim()
            if (trimmed.isBlank()) {
                prefs.edit().remove(KEY_TOKEN_IV).remove(KEY_TOKEN_DATA).apply()
            } else {
                val (iv, encrypted) = encrypt(trimmed)
                prefs.edit()
                    .putString(KEY_TOKEN_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                    .putString(KEY_TOKEN_DATA, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .apply()
            }
        }
    }

    fun apiKey(): String {
        val iv = prefs.getString(KEY_TOKEN_IV, null) ?: return ""
        val data = prefs.getString(KEY_TOKEN_DATA, null) ?: return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(
                cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)),
                StandardCharsets.UTF_8,
            )
        }.getOrDefault("")
    }

    fun hasApiKey(): Boolean = apiKey().isNotBlank()

    private fun encrypt(value: String): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return cipher.iv to cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "ru_dubbing"
        const val KEY_ENDPOINT = "asr_endpoint"
        const val KEY_MODEL = "asr_model"
        const val KEY_MODE = "dubbing_mode"
        const val KEY_VOICE = "voice_name"
        const val KEY_TOKEN_IV = "asr_token_iv"
        const val KEY_TOKEN_DATA = "asr_token_data"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "noodle_ru_asr_token_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
