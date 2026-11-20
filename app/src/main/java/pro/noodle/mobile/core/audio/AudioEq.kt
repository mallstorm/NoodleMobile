package pro.noodle.mobile.core.audio

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.nio.ByteBuffer

/**
 * Глобальное состояние эквалайзера. Обработчики звука всех плееров читают настройки отсюда,
 * поэтому изменения слышны сразу, без пересоздания плеера.
 */
object AudioEq {
    @Volatile var settings: EqSettings = EqSettings(); private set
    @Volatile var version: Int = 0; private set
    private val _flow = MutableStateFlow(EqSettings())
    val flow: StateFlow<EqSettings> = _flow.asStateFlow()
    private val _presets = MutableStateFlow<Map<String, EqSettings>>(emptyMap())
    val userPresets: StateFlow<Map<String, EqSettings>> = _presets.asStateFlow()
    private var prefs: android.content.SharedPreferences? = null

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("noodle_eq", Context.MODE_PRIVATE)
        prefs = p
        runCatching { p.getString("current", null)?.let { settings = EqSettings.fromJson(JSONObject(it)) } }
        runCatching {
            val o = JSONObject(p.getString("presets", "{}").orEmpty())
            _presets.value = o.keys().asSequence().associateWith { EqSettings.fromJson(o.getJSONObject(it)) }
        }
        _flow.value = settings
        version++
    }

    private fun commit(s: EqSettings) {
        settings = s; version++; _flow.value = s
        prefs?.edit()?.putString("current", s.toJson().toString())?.apply()
    }

    /** Ручная правка: пресет становится «Свой». */
    fun edit(transform: (EqSettings) -> EqSettings) = commit(transform(settings).copy(presetName = "Свой"))

    fun setEnabled(on: Boolean) = commit(settings.copy(enabled = on))

    fun apply(preset: EqPreset) {
        val cur = settings
        val s = if (preset.touchesChannels) preset.settings else preset.settings.copy(channelMode = cur.channelMode, invertPhase = cur.invertPhase, balance = cur.balance)
        commit(s.copy(enabled = true, presetName = preset.name))
    }

    fun applyUser(name: String) { _presets.value[name]?.let { commit(it.copy(enabled = true, presetName = name)) } }

    fun saveUserPreset(name: String) {
        val n = name.trim().take(24).ifBlank { return }
        val s = settings.copy(presetName = n)
        _presets.value = _presets.value + (n to s)
        persistPresets(); commit(s)
    }

    fun deleteUserPreset(name: String) { _presets.value = _presets.value - name; persistPresets() }

    private fun persistPresets() {
        val o = JSONObject(); _presets.value.forEach { (k, v) -> o.put(k, v.toJson()) }
        prefs?.edit()?.putString("presets", o.toString())?.apply()
    }

    fun reset() = commit(EqSettings(enabled = settings.enabled))

    @OptIn(UnstableApi::class)
    fun renderersFactory(context: Context): DefaultRenderersFactory {
        init(context)
        return object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf<AudioProcessor>(EqAudioProcessor())).build()
        }
    }
}

/** Обработчик PCM для Media3: стерео/моно, 16 бит и float. Моно на входе расширяется до стерео. */
@OptIn(UnstableApi::class)
class EqAudioProcessor : BaseAudioProcessor() {
    private var engine: EqEngine? = null
    private var applied = -1
    private var inChannels = 2
    private var float = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if ((enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) || inputAudioFormat.channelCount !in 1..2) return AudioProcessor.AudioFormat.NOT_SET
        inChannels = inputAudioFormat.channelCount
        float = enc == C.ENCODING_PCM_FLOAT
        engine = EqEngine(inputAudioFormat.sampleRate)
        applied = -1
        return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, 2, enc)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val e = engine
        if (e == null) { inputBuffer.position(inputBuffer.limit()); return }
        if (applied != AudioEq.version) { e.configure(AudioEq.settings); applied = AudioEq.version }
        val bps = if (float) 4 else 2
        val frames = inputBuffer.remaining() / (bps * inChannels)
        if (frames <= 0) { inputBuffer.position(inputBuffer.limit()); return }
        val out = replaceOutputBuffer(frames * bps * 2)
        val on = e.enabled
        for (i in 0 until frames) {
            val l: Float; val r: Float
            if (float) {
                l = inputBuffer.getFloat(); r = if (inChannels == 2) inputBuffer.getFloat() else l
            } else {
                l = inputBuffer.getShort() / 32768f; r = if (inChannels == 2) inputBuffer.getShort() / 32768f else l
            }
            val ol: Float; val orr: Float
            if (on) { e.process(l, r); ol = e.l; orr = e.r } else { ol = l; orr = r }
            if (float) { out.putFloat(ol); out.putFloat(orr) }
            else {
                out.putShort((ol * 32768f).toInt().coerceIn(-32768, 32767).toShort())
                out.putShort((orr * 32768f).toInt().coerceIn(-32768, 32767).toShort())
            }
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }
}
