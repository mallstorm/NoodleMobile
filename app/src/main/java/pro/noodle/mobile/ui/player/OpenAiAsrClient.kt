package pro.noodle.mobile.ui.player

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject

internal data class AsrSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val speaker: String = "",
)

internal class OpenAiAsrClient(
    private val http: OkHttpClient = OkHttpClient(),
) {
    suspend fun transcribe(
        audio: File,
        config: DubbingConfig,
        apiKey: String,
        chunkDurationMs: Long,
    ): List<AsrSegment> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "ASR_API_KEY_MISSING" }
        require(config.endpoint.startsWith("https://")) { "ASR_ENDPOINT_MUST_USE_HTTPS" }
        require(config.model.isNotBlank()) { "ASR_MODEL_MISSING" }

        val diarized = config.model.contains("diarize", ignoreCase = true)
        val responseFormat = if (diarized) "diarized_json" else {
            if (config.model.contains("whisper", ignoreCase = true)) "verbose_json" else "json"
        }

        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                audio.name,
                audio.asRequestBody("audio/mp4".toMediaType()),
            )
            .addFormDataPart("model", config.model)
            .addFormDataPart("response_format", responseFormat)
            .apply {
                if (diarized) addFormDataPart("chunking_strategy", "auto")
                if (responseFormat == "verbose_json") {
                    addFormDataPart("timestamp_granularities[]", "segment")
                }
            }
            .build()

        val request = Request.Builder()
            .url(config.endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .post(form)
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty()
                error("ASR_HTTP_${response.code}: ${detail.ifBlank { "transcription failed" }}")
            }
            parse(body, chunkDurationMs)
        }
    }

    private fun parse(json: String, chunkDurationMs: Long): List<AsrSegment> {
        val root = JSONObject(json)
        val array = root.optJSONArray("segments")
        if (array != null && array.length() > 0) {
            return buildList {
                for (index in 0 until array.length()) {
                    val segment = array.optJSONObject(index) ?: continue
                    val text = segment.optString("text").cleanAsrText()
                    if (text.isBlank()) continue
                    val start = (segment.optDouble("start", 0.0) * 1000.0).toLong()
                        .coerceAtLeast(0L)
                    val end = (segment.optDouble("end", start / 1000.0) * 1000.0).toLong()
                        .coerceAtLeast(start + 250L)
                    add(
                        AsrSegment(
                            startMs = start,
                            endMs = end,
                            text = text,
                            speaker = segment.optString("speaker").trim(),
                        )
                    )
                }
            }
        }

        val text = root.optString("text").cleanAsrText()
        if (text.isBlank()) error("ASR_EMPTY_TRANSCRIPT")
        return listOf(
            AsrSegment(
                startMs = 0L,
                endMs = chunkDurationMs.coerceAtLeast(1_000L),
                text = text,
            )
        )
    }

    private fun String.cleanAsrText(): String =
        replace(Regex("\\s+"), " ").trim()
}
