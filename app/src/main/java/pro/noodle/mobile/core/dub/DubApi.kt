package pro.noodle.mobile.core.dub

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class DubException(message: String, val fatal: Boolean = false, val retryAfterSec: Int = 0, val modelMissing: Boolean = false) : Exception(message)

data class AsrSegment(val start: Double, val end: Double, val text: String)
data class AsrResult(val language: String, val segments: List<AsrSegment>)

/** Whisper (распознавание), чат-модель (перевод) и OpenAI TTS — по OpenAI-совместимому API (Groq или OpenAI). */
class DubApi(private val provider: DubProvider, private val key: String) {
    private val http = OkHttpClient.Builder().callTimeout(120, TimeUnit.SECONDS).readTimeout(100, TimeUnit.SECONDS).build()
    private val base = if (provider == DubProvider.GROQ) "https://api.groq.com/openai/v1" else "https://api.openai.com/v1"
    private val asrModel = if (provider == DubProvider.GROQ) "whisper-large-v3-turbo" else "whisper-1"

    /** Модели меняются (Groq снимает старые): берём список доступных у ключа и выбираем лучшую из подходящих. */
    private fun chatCandidates(): List<String> {
        cache[provider]?.let { l -> return chosen[provider]?.let { c -> listOf(c) + l.filter { it != c } } ?: l }
        val ids = runCatching {
            val req = Request.Builder().url("$base/models").header("Authorization", "Bearer $key").get().build()
            http.newCall(req).execute().use { r ->
                val t = r.body?.string().orEmpty()
                check(r.code, t)
                val a = JSONObject(t).optJSONArray("data") ?: JSONArray()
                (0 until a.length()).map { a.getJSONObject(it).optString("id") }.filter { it.isNotBlank() }
            }
        }.onFailure { if (it is DubException && it.fatal) throw it }.getOrDefault(emptyList())
        val prefer = if (provider == DubProvider.GROQ)
            listOf("llama-3.3-70b-versatile", "openai/gpt-oss-120b", "llama-3.1-70b-versatile", "openai/gpt-oss-20b", "qwen/qwen3-32b", "meta-llama/llama-4-maverick-17b-128e-instruct", "meta-llama/llama-4-scout-17b-16e-instruct", "llama-3.1-8b-instant")
        else listOf("gpt-4o-mini", "gpt-4.1-mini", "gpt-4o", "gpt-4.1", "gpt-5-mini", "gpt-3.5-turbo")
        val bad = listOf("whisper", "tts", "guard", "playai", "orpheus", "embed", "distil", "transcribe", "image", "moderation", "realtime", "audio", "dall", "safeguard")
        val usable = ids.filter { id -> bad.none { it in id.lowercase() } }
        val ordered = prefer.filter { it in ids } + usable.filter { it !in prefer }.sortedByDescending { it.contains("70b") || it.contains("120b") || it.contains("32b") }
        val list = (ordered + prefer).distinct().take(8)
        if (ids.isNotEmpty()) cache[provider] = list
        return list
    }

    private fun check(code: Int, body: String) {
        if (code in 200..299) return
        val msg = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty().take(160)
        when (code) {
            401, 403 -> throw DubException("Ключ ${provider.title} не принят ($code). Проверьте ключ.", fatal = true)
            429 -> throw DubException("Лимит запросов ${provider.title}: $msg", retryAfterSec = 25)
            404 -> throw DubException("${provider.title} вернул 404: $msg", modelMissing = true)
            400 -> throw DubException("${provider.title} вернул 400: $msg", modelMissing = "model" in msg.lowercase() || "response_format" in msg.lowercase() || "json" in msg.lowercase())
            else -> throw DubException("${provider.title} вернул $code: $msg")
        }
    }

    fun transcribe(wav: ByteArray): AsrResult {
        val models = if (provider == DubProvider.GROQ) listOf(asrModel, "whisper-large-v3") else listOf(asrModel)
        var lastErr: DubException? = null
        for (m in models) {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", "chunk.wav", wav.toRequestBody("audio/wav".toMediaType()))
                .addFormDataPart("model", m).addFormDataPart("response_format", "verbose_json").addFormDataPart("temperature", "0")
                .build()
            val req = Request.Builder().url("$base/audio/transcriptions").header("Authorization", "Bearer $key").post(body).build()
            try { return parseAsr(req) } catch (e: DubException) { if (e.modelMissing) lastErr = e else throw e }
        }
        throw DubException(lastErr?.message ?: "Модель распознавания недоступна.", fatal = true)
    }

    private fun parseAsr(req: Request): AsrResult {
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            check(r.code, text)
            val o = JSONObject(text)
            val arr = o.optJSONArray("segments") ?: JSONArray()
            val segs = (0 until arr.length()).mapNotNull { i ->
                val s = arr.getJSONObject(i)
                val t = s.optString("text").trim()
                val noSpeech = s.optDouble("no_speech_prob", 0.0); val lp = s.optDouble("avg_logprob", 0.0)
                if (t.length < 2 || (noSpeech > 0.6 && lp < -1.0) || lp < -1.5 || isHallucination(t)) null
                else AsrSegment(s.optDouble("start"), s.optDouble("end"), t)
            }
            return AsrResult(o.optString("language", "").lowercase(), segs)
        }
    }

    fun translate(lines: List<String>, sourceLanguage: String): List<String> {
        val sys = "You are a film dubbing translator. Translate every numbered line of dialogue from ${sourceLanguage.ifBlank { "the original language" }} into natural spoken Russian. " +
            "Keep each line about as short as the original so it fits the same time, keep the tone and meaning, no explanations. " +
            "Return ONLY JSON: {\"lines\":[...]} with exactly ${lines.size} strings in the same order."
        val user = lines.mapIndexed { i, l -> "${i + 1}. $l" }.joinToString("\n")
        var lastErr: DubException? = null
        for (model in chatCandidates()) {
            for (useJson in listOf(true, false)) {
                val payload = JSONObject().put("model", model).put("temperature", 0.3)
                    .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", sys)).put(JSONObject().put("role", "user").put("content", user)))
                if (useJson) payload.put("response_format", JSONObject().put("type", "json_object"))
                val req = Request.Builder().url("$base/chat/completions").header("Authorization", "Bearer $key")
                    .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
                try {
                    http.newCall(req).execute().use { r ->
                        val text = r.body?.string().orEmpty()
                        check(r.code, text)
                        var content = JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                        content = content.replace(Regex("(?s)<think>.*?</think>"), "")
                        val json = content.substring(content.indexOf('{').coerceAtLeast(0), content.lastIndexOf('}') + 1)
                        val arr = JSONObject(json).optJSONArray("lines") ?: throw DubException("Модель вернула перевод в неожиданном формате.", modelMissing = true)
                        chosen[provider] = model
                        return List(lines.size) { i -> arr.optString(i, "").trim() }
                    }
                } catch (e: DubException) { if (e.modelMissing) lastErr = e else throw e }
            }
        }
        cache.remove(provider)
        throw DubException("Не нашлась рабочая модель перевода у ${provider.title}: ${lastErr?.message.orEmpty().take(120)}", fatal = true)
    }

    /** OpenAI TTS → WAV 24 кГц моно. */
    fun speak(text: String, male: Boolean, speed: Double): ByteArray {
        val payload = JSONObject().put("model", "tts-1").put("input", text).put("voice", if (male) "onyx" else "nova")
            .put("response_format", "wav").put("speed", speed.coerceIn(0.5, 2.0))
        val req = Request.Builder().url("https://api.openai.com/v1/audio/speech").header("Authorization", "Bearer $key")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) check(r.code, r.body?.string().orEmpty())
            return r.body?.bytes() ?: ByteArray(0)
        }
    }

    private companion object {
        val cache = java.util.concurrent.ConcurrentHashMap<DubProvider, List<String>>()
        val chosen = java.util.concurrent.ConcurrentHashMap<DubProvider, String>()
    }

    private fun isHallucination(t: String): Boolean {
        val l = t.lowercase()
        if (listOf("субтитры", "subtitles by", "thanks for watching", "thank you for watching", "продолжение следует", "amara.org", "dimatorzok", "редактор субтитров").any { it in l }) return true
        val words = l.split(Regex("\\s+")).filter { it.isNotBlank() }
        return words.size >= 6 && words.toSet().size <= 2
    }
}
