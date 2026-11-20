package pro.noodle.mobile.core.dub

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import pro.noodle.mobile.core.studio.StudioSecretVault

enum class DubProvider(val title: String) { GROQ("Groq"), OPENAI("OpenAI") }
enum class TtsEngine(val title: String) { SYSTEM("Системный голос"), OPENAI("OpenAI голос") }

data class DubState(
    val enabled: Boolean = false,
    val provider: DubProvider = DubProvider.GROQ,
    val groqKey: String = "",
    val openaiKey: String = "",
    val tts: TtsEngine = TtsEngine.SYSTEM,
    val originalVolume: Float = 0.2f,
) {
    val key: String get() = if (provider == DubProvider.GROQ) groqKey else openaiKey
}

/** Настройки автоматической озвучки. Ключи API хранятся зашифрованными (Android Keystore). */
object DubConfig {
    private val _flow = MutableStateFlow(DubState())
    val flow: StateFlow<DubState> = _flow.asStateFlow()
    private var prefs: android.content.SharedPreferences? = null
    private val vault by lazy { StudioSecretVault() }

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("noodle_dub", Context.MODE_PRIVATE)
        prefs = p
        fun secret(k: String) = runCatching { vault.decrypt(p.getString(k, "").orEmpty()) }.getOrDefault("")
        _flow.value = DubState(
            enabled = p.getBoolean("enabled", false),
            provider = runCatching { DubProvider.valueOf(p.getString("provider", "GROQ").orEmpty()) }.getOrDefault(DubProvider.GROQ),
            groqKey = secret("gk"), openaiKey = secret("ok"),
            tts = runCatching { TtsEngine.valueOf(p.getString("tts", "SYSTEM").orEmpty()) }.getOrDefault(TtsEngine.SYSTEM),
            originalVolume = p.getFloat("orig", 0.2f),
        )
    }

    fun update(transform: (DubState) -> DubState) {
        val s = transform(_flow.value)
        _flow.value = s
        prefs?.edit()
            ?.putBoolean("enabled", s.enabled)?.putString("provider", s.provider.name)
            ?.putString("gk", runCatching { vault.encrypt(s.groqKey) }.getOrDefault(""))
            ?.putString("ok", runCatching { vault.encrypt(s.openaiKey) }.getOrDefault(""))
            ?.putString("tts", s.tts.name)?.putFloat("orig", s.originalVolume)?.apply()
    }
}
