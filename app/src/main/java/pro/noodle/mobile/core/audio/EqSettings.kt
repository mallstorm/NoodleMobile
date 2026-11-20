package pro.noodle.mobile.core.audio

import org.json.JSONArray
import org.json.JSONObject

/** Маршрутизация каналов. REVERSE — «реверсивный канал»: левый и правый меняются местами. */
enum class ChannelMode(val title: String) {
    STEREO("Стерео"), MONO("Моно"), LEFT("Левый → оба"), RIGHT("Правый → оба"), REVERSE("Реверс L↔R")
}

val EQ_BAND_FREQS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
const val EQ_MAX_DB = 12f

/** Полный набор параметров звука плеера. Все значения в «человеческих» единицах (дБ, 0..1). */
data class EqSettings(
    val enabled: Boolean = true,
    val preamp: Float = 0f,
    val bands: List<Float> = List(EQ_BAND_FREQS.size) { 0f },
    val q: Float = 1.41f,
    val bass: Float = 0f,
    val treble: Float = 0f,
    val channelMode: ChannelMode = ChannelMode.STEREO,
    val invertPhase: Boolean = false,
    val balance: Float = 0f,
    val width: Float = 1f,
    val surround: Float = 0f,
    val crossfeed: Float = 0f,
    val compressor: Float = 0f,
    val reverb: Float = 0f,
    val reverbRoom: Float = 0.5f,
    val outputGain: Float = 0f,
    val limiter: Boolean = true,
    val presetName: String = "Плоская",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled).put("preamp", preamp.toDouble()).put("bands", JSONArray(bands.map { it.toDouble() }))
        .put("q", q.toDouble()).put("bass", bass.toDouble()).put("treble", treble.toDouble())
        .put("channelMode", channelMode.name).put("invertPhase", invertPhase).put("balance", balance.toDouble())
        .put("width", width.toDouble()).put("surround", surround.toDouble()).put("crossfeed", crossfeed.toDouble())
        .put("compressor", compressor.toDouble()).put("reverb", reverb.toDouble()).put("reverbRoom", reverbRoom.toDouble())
        .put("outputGain", outputGain.toDouble()).put("limiter", limiter).put("presetName", presetName)

    companion object {
        fun fromJson(o: JSONObject): EqSettings {
            val d = EqSettings()
            val arr = o.optJSONArray("bands")
            val bands = List(EQ_BAND_FREQS.size) { i -> arr?.optDouble(i, 0.0)?.toFloat() ?: 0f }
            return EqSettings(
                enabled = o.optBoolean("enabled", d.enabled), preamp = o.optDouble("preamp", 0.0).toFloat(), bands = bands,
                q = o.optDouble("q", d.q.toDouble()).toFloat(), bass = o.optDouble("bass", 0.0).toFloat(), treble = o.optDouble("treble", 0.0).toFloat(),
                channelMode = runCatching { ChannelMode.valueOf(o.optString("channelMode", "STEREO")) }.getOrDefault(ChannelMode.STEREO),
                invertPhase = o.optBoolean("invertPhase", false), balance = o.optDouble("balance", 0.0).toFloat(),
                width = o.optDouble("width", 1.0).toFloat(), surround = o.optDouble("surround", 0.0).toFloat(), crossfeed = o.optDouble("crossfeed", 0.0).toFloat(),
                compressor = o.optDouble("compressor", 0.0).toFloat(), reverb = o.optDouble("reverb", 0.0).toFloat(), reverbRoom = o.optDouble("reverbRoom", 0.5).toFloat(),
                outputGain = o.optDouble("outputGain", 0.0).toFloat(), limiter = o.optBoolean("limiter", true), presetName = o.optString("presetName", d.presetName),
            )
        }
    }
}

data class EqPreset(val name: String, val settings: EqSettings, val touchesChannels: Boolean = false)

object EqPresets {
    private fun p(name: String, vararg bands: Float, bass: Float = 0f, treble: Float = 0f, width: Float = 1f, surround: Float = 0f, crossfeed: Float = 0f,
                  compressor: Float = 0f, reverb: Float = 0f, room: Float = 0.5f, preamp: Float = 0f, out: Float = 0f, mode: ChannelMode? = null) =
        EqPreset(
            name,
            EqSettings(
                preamp = preamp, bands = if (bands.isEmpty()) List(10) { 0f } else bands.toList(), bass = bass, treble = treble, width = width, surround = surround,
                crossfeed = crossfeed, compressor = compressor, reverb = reverb, reverbRoom = room, outputGain = out,
                channelMode = mode ?: ChannelMode.STEREO, presetName = name,
            ),
            touchesChannels = mode != null,
        )

    val builtin: List<EqPreset> = listOf(
        p("Плоская"),
        p("Бас+", 6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f, preamp = -3f),
        p("Бас−", -6f, -5f, -3f, -1f, 0f, 0f, 0f, 0f, 0f, 0f),
        p("Высокие+", 0f, 0f, 0f, 0f, 0f, 1f, 2f, 4f, 5f, 6f, preamp = -3f),
        p("Голос", -3f, -3f, -2f, 1f, 3f, 4f, 3f, 1f, -1f, -2f),
        p("Диалоги", -6f, -5f, -3f, 0f, 3f, 5f, 4f, 2f, -1f, -3f, compressor = 0.3f),
        p("Рок", 5f, 4f, 2f, -2f, -3f, -1f, 2f, 4f, 5f, 5f, preamp = -4f),
        p("Поп", -1f, 1f, 3f, 4f, 3f, 0f, -1f, -1f, 1f, 2f),
        p("Джаз", 3f, 2f, 1f, 2f, -2f, -2f, 0f, 1f, 2f, 3f),
        p("Классика", 4f, 3f, 2f, 2f, -1f, -1f, 0f, 2f, 3f, 4f),
        p("Танцы", 6f, 5f, 3f, 0f, 1f, 3f, 4f, 4f, 3f, 0f, preamp = -4f),
        p("Электроника", 5f, 4f, 1f, 0f, -2f, 2f, 1f, 2f, 4f, 5f, preamp = -3f),
        p("Хип-хоп", 5f, 4f, 1f, 3f, -1f, -1f, 2f, -1f, 2f, 3f, preamp = -3f),
        p("Акустика", 4f, 3f, 2f, 1f, 2f, 2f, 3f, 3f, 2f, 1f),
        p("Громкость", 6f, 4f, 0f, 0f, -2f, 0f, -1f, -4f, 5f, 1f, preamp = -4f),
        p("Ночь", 2f, 1f, 0f, 0f, 0f, 0f, 0f, -1f, -2f, -3f, compressor = 0.8f, out = 2f),
        p("Кино", 4f, 3f, 1f, 0f, -1f, 0f, 2f, 3f, 3f, 2f, width = 1.4f, surround = 0.5f, reverb = 0.1f, preamp = -3f),
        p("Концерт", 2f, 2f, 1f, 0f, 0f, 1f, 2f, 2f, 2f, 1f, width = 1.3f, surround = 0.6f, reverb = 0.35f, room = 0.8f, preamp = -2f),
        p("Наушники", 1f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, crossfeed = 0.5f),
        p("3D-объём", width = 1.8f, surround = 0.9f, reverb = 0.15f),
        p("Моно", mode = ChannelMode.MONO),
        p("Реверс каналов", mode = ChannelMode.REVERSE),
    )
}
