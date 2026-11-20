package pro.noodle.mobile.core.dub

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class DubException(message: String, val fatal: Boolean = false, val retryAfterSec: Int = 0) : Exception(message)

data class AsrSegment(val start: Double, val end: Double, val text: String)
data class AsrResult(val language: String, val segments: List<AsrSegment>)

/** Whisper (распознавание), чат-модель (перевод) и OpenAI TTS — по OpenAI-совместимому API (Groq или OpenAI). */
class DubApi(private val provider: DubProvider, private val key: String) {
    private val http = OkHttpClient.Builder().callTimeout(120, TimeUnit.SECONDS).readTimeout(100, TimeUnit.SECONDS).build()
    private val base = if (provider == DubProvider.GROQ) "https://api.groq.com/openai/v1" else "https://api.openai.com/v1"
    private val asrModel = if (provider == DubProvider.GROQ) "whisper-large-v3-turbo" else "whisper-1"
    private val chatModel = if (provider == DubProvider.GROQ) "llama-3.3-70b-versatile" else "gpt-4o-mini"

    private fun check(code: Int, body: String) {
        if (code in 200..299) return
        val msg = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty().take(160)
        when (code) {
            401, 403 -> throw DubException("Ключ ${provider.title} не принят ($code). Проверьте ключ.", fatal = true)
            429 -> throw DubException("Лимит запросов ${provider.title}: $msg", retryAfterSec = 25)
            else -> throw DubException("${provider.title} вернул $code: $msg")
        }
    }

    fun transcribe(wav: ByteArray): AsrResult {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "chunk.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", asrModel).addFormDataPart("response_format", "verbose_json").addFormDataPart("temperature", "0")
            .build()
        val req = Request.Builder().url("$base/audio/transcriptions").header("Authorization", "Bearer $key").post(body).build()
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
        val payload = JSONObject().put("model", chatModel).put("temperature", 0.3)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", sys)).put(JSONObject().put("role", "user").put("content", user)))
        val req = Request.Builder().url("$base/chat/completions").header("Authorization", "Bearer $key")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            check(r.code, text)
            val content = JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            val json = content.substring(content.indexOf('{').coerceAtLeast(0), content.lastIndexOf('}') + 1)
            val arr = JSONObject(json).optJSONArray("lines") ?: throw DubException("Модель вернула перевод в неожиданном формате.")
            return List(lines.size) { i -> arr.optString(i, "").trim() }
        }
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

    private fun isHallucination(t: String): Boolean {
        val l = t.lowercase()
        if (listOf("субтитры", "subtitles by", "thanks for watching", "thank you for watching", "продолжение следует", "amara.org", "dimatorzok", "редактор субтитров").any { it in l }) return true
        val words = l.split(Regex("\\s+")).filter { it.isNotBlank() }
        return words.size >= 6 && words.toSet().size <= 2
    }
}
