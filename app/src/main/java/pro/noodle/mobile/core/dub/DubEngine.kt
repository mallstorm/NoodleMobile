package pro.noodle.mobile.core.dub

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.media3.common.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import pro.noodle.mobile.core.version.BuildIdentity
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

/**
 * Автоматическая русская озвучка: звук видео режется на куски по ~26 с, Whisper распознаёт речь с таймкодами,
 * чат-модель переводит реплики, TTS их озвучивает, а плеер проигрывает дубляж поверх приглушённого оригинала.
 * Обработка идёт с опережением, чтобы реплики были готовы к моменту воспроизведения.
 */
class DubEngine(context: Context) {
    private val app = context.applicationContext

    private class Ready(val startMs: Long, val endMs: Long, val pcm: ShortArray, val rate: Int)
    private class Active(val track: AudioTrack, val endWall: Long)

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private var job: Job? = null
    private var scope: CoroutineScope? = null
    private var player: Player? = null
    private val ready = ConcurrentHashMap<Long, Ready>()
    private val doneChunks = ConcurrentHashMap.newKeySet<Int>()
    private val played = HashSet<Long>()
    private val actives = ArrayList<Active>()
    @Volatile private var playheadMs = 0L
    private var tts: TextToSpeech? = null
    private var key = ""
    private var cacheFile: File? = null
    private var cache = JSONObject()

    fun start(player: Player, url: String, referer: String, videoKey: String) {
        stop()
        val cfg = DubConfig.flow.value
        if (cfg.key.isBlank()) { _status.value = "Озвучка: укажите ключ ${cfg.provider.title} в настройках озвучки."; return }
        if (cfg.tts == TtsEngine.OPENAI && cfg.openaiKey.isBlank()) { _status.value = "Озвучка: для голоса OpenAI нужен ключ OpenAI."; return }
        this.player = player
        key = videoKey
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = sc
        val hash = (videoKey + "|" + cfg.provider.name).hashCode().toUInt().toString(16)
        val dir = File(app.cacheDir, "dub").apply { mkdirs() }
        cacheFile = File(dir, "$hash.json")
        cache = runCatching { JSONObject(cacheFile!!.readText()) }.getOrDefault(JSONObject())
        job = sc.launch { pipeline(cfg, url, referer) }
        sc.launch(Dispatchers.Main) { scheduler() }
        _status.value = "Озвучка: запуск…"
    }

    fun stop() {
        job?.cancel(); job = null
        scope?.cancel(); scope = null
        val p = player
        player = null
        ready.clear(); doneChunks.clear(); played.clear()
        for (a in actives) runCatching { a.track.stop(); a.track.release() }
        actives.clear()
        runCatching { tts?.shutdown() }; tts = null
        if (p != null) runCatching { android.os.Handler(android.os.Looper.getMainLooper()).post { p.volume = 1f } }
    }

    // ---------------------------------------------------------------- воспроизведение (главный поток)
    private suspend fun scheduler() {
        var lastPos = -1L
        while (scope?.isActive == true) {
            val p = player ?: break
            val pos = p.currentPosition
            playheadMs = pos
            val now = System.currentTimeMillis()
            if (lastPos >= 0 && kotlin.math.abs(pos - lastPos) > 1500 && p.isPlaying) { stopTracks(); played.removeAll { it > pos - 500 } }
            lastPos = pos
            if (!p.playWhenReady || !p.isPlaying) {
                if (actives.isNotEmpty()) stopTracks()
            } else {
                val speed = p.playbackParameters.speed
                for (r in ready.values) {
                    if (r.startMs in (pos - 150)..(pos + 90) && r.endMs > pos && played.add(r.startMs)) {
                        runCatching { playSegment(r, max(0L, pos - r.startMs), speed) }
                    }
                }
            }
            actives.removeAll { a -> if (now > a.endWall) { runCatching { a.track.release() }; true } else false }
            val duck = DubConfig.flow.value.originalVolume
            p.volume = if (actives.isNotEmpty()) duck else 1f
            delay(40)
        }
    }

    private fun stopTracks() {
        for (a in actives) runCatching { a.track.stop(); a.track.release() }
        actives.clear()
    }

    private fun playSegment(r: Ready, offsetMs: Long, speed: Float) {
        val offFrames = (offsetMs * r.rate / 1000).toInt()
        if (offFrames >= r.pcm.size) return
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(r.rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(r.pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
        track.write(r.pcm, 0, r.pcm.size)
        if (offFrames > 0) track.setPlaybackHeadPosition(offFrames)
        if (speed != 1f) runCatching { track.playbackParams = PlaybackParams().setSpeed(speed) }
        track.play()
        val remainMs = ((r.pcm.size - offFrames) * 1000L / r.rate / max(0.25f, speed)).toLong()
        actives.add(Active(track, System.currentTimeMillis() + remainMs + 150))
    }

    // ---------------------------------------------------------------- подготовка реплик (фон)
    private suspend fun pipeline(cfg: DubState, url: String, referer: String) {
        val api = DubApi(cfg.provider, cfg.key)
        val ttsApi = if (cfg.tts == TtsEngine.OPENAI) DubApi(DubProvider.OPENAI, cfg.openaiKey) else null
        val headers = buildMap {
            put("User-Agent", BuildIdentity.USER_AGENT)
            if (referer.isNotBlank()) { put("Referer", referer) }
        }
        var failures = 0
        var lastMale = false
        try {
            while (currentCoroutineContext().isActive) {
                val pos = playheadMs
                val first = (pos / CHUNK_MS).toInt()
                val lastIdx = ((pos + AHEAD_MS) / CHUNK_MS).toInt()
                val idx = (first..lastIdx).firstOrNull { it !in doneChunks }
                if (idx == null) {
                    val aheadTo = (doneChunks.maxOrNull() ?: first) * CHUNK_MS + CHUNK_MS
                    _status.value = "Озвучка: готово на ${max(0L, aheadTo - pos) / 1000} с вперёд"
                    delay(1500); continue
                }
                val startMs = idx * CHUNK_MS
                _status.value = "Озвучка: обрабатываю ${fmt(startMs)}…"
                try {
                    val lines = chunkLines(api, headers, url, idx, startMs, cfg)
                    if (lines == null) { // сорвалось декодирование звука
                        failures++
                        if (failures >= 3) { _status.value = "Озвучка: не удалось прочитать звук этого видео (формат или защита)."; return }
                        delay(3000); continue
                    }
                    failures = 0
                    for (l in lines) {
                        if (ready.containsKey(l.startMs)) continue
                        val male = l.male ?: lastMale
                        lastMale = male
                        synthLine(l, male, cfg, ttsApi)?.let { ready[l.startMs] = it }
                    }
                    doneChunks.add(idx)
                } catch (e: DubException) {
                    if (e.fatal) { _status.value = "Озвучка остановлена: ${e.message}"; return }
                    _status.value = "Озвучка: ${e.message} Повтор через ${max(e.retryAfterSec, 5)} с…"
                    delay(max(e.retryAfterSec, 5) * 1000L)
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    failures++
                    if (failures >= 4) { _status.value = "Озвучка остановлена: ${e.message ?: e.javaClass.simpleName}"; return }
                    delay(4000)
                }
            }
        } finally {
            runCatching { cacheFile?.writeText(cache.toString()) }
        }
    }

    private class Line(val startMs: Long, val endMs: Long, val ru: String, val male: Boolean?)

    private suspend fun chunkLines(api: DubApi, headers: Map<String, String>, url: String, idx: Int, startMs: Long, cfg: DubState): List<Line>? {
        val ck = idx.toString()
        cache.optJSONArray(ck)?.let { arr ->
            return (0 until arr.length()).map { i -> arr.getJSONObject(i).let { o -> Line(o.getLong("s"), o.getLong("e"), o.getString("t"), if (o.has("m")) o.getBoolean("m") else null) } }
        }
        val pcm = withContext(Dispatchers.IO) { DubAudio.decode(url, headers, startMs, CHUNK_MS + 2_000) } ?: return null
        val out = ArrayList<Line>()
        if (DubAudio.rms(pcm) > 80.0) {
            val asr = api.transcribe(Wav.encode(pcm, DubAudio.RATE))
            val segs = asr.segments.filter { it.start * 1000 < CHUNK_MS }
            if (asr.language.startsWith("russian") || asr.language == "ru") { cache.put(ck, JSONArray()); return emptyList() }
            if (segs.isNotEmpty()) {
                val ru = api.translate(segs.map { it.text }, asr.language)
                segs.forEachIndexed { i, s ->
                    val t = ru.getOrNull(i).orEmpty()
                    if (t.isBlank()) return@forEachIndexed
                    val male = DubAudio.isMale(pcm, (s.start * DubAudio.RATE).toInt(), (s.end * DubAudio.RATE).toInt())
                    out.add(Line(startMs + (s.start * 1000).toLong(), startMs + (s.end * 1000).toLong(), t, male))
                }
            }
        }
        val arr = JSONArray()
        out.forEach { l -> arr.put(JSONObject().put("s", l.startMs).put("e", l.endMs).put("t", l.ru).also { o -> l.male?.let { o.put("m", it) } }) }
        cache.put(ck, arr)
        return out
    }

    // ---------------------------------------------------------------- озвучка реплики
    private suspend fun synthLine(l: Line, male: Boolean, cfg: DubState, openai: DubApi?): Ready? {
        val slot = max(600L, l.endMs - l.startMs + 250)
        var speed = 1.05
        var pcmRate: Pair<Int, ShortArray>? = null
        repeat(2) { attempt ->
            val r = if (openai != null) Wav.decode(openai.speak(l.ru, male, speed)) else systemSynth(l.ru, male, speed)
            if (r == null) return null
            pcmRate = r
            val durMs = r.second.size * 1000L / r.first
            if (durMs <= slot || attempt == 1) return@repeat
            speed = min(2.0, speed * durMs / slot)
        }
        val (rate, pcm0) = pcmRate ?: return null
        val maxLen = (slot * 1.3 * rate / 1000).toInt()
        val pcm = if (pcm0.size > maxLen) pcm0.copyOf(maxLen).also { a -> val f = min(a.size, rate / 12); for (i in 0 until f) a[a.size - 1 - i] = (a[a.size - 1 - i] * (i.toFloat() / f)).toInt().toShort() } else pcm0
        return Ready(l.startMs, l.startMs + pcm.size * 1000L / rate, pcm, rate)
    }

    private suspend fun systemSynth(text: String, male: Boolean, speed: Double): Pair<Int, ShortArray>? {
        val engine = tts ?: initTts() ?: throw DubException("Нет системного синтеза речи для русского. Настройки Android → Язык → Синтез речи.", fatal = true)
        val file = File(app.cacheDir, "dub/tts_${System.nanoTime()}.wav")
        val id = "u${System.nanoTime()}"
        val ok = withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine<Boolean> { cont ->
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(true) }
                    @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(false) }
                })
                engine.setPitch(if (male) 0.82f else 1.12f)
                engine.setSpeechRate(speed.toFloat())
                val res = engine.synthesizeToFile(text, Bundle(), file, id)
                if (res != TextToSpeech.SUCCESS && cont.isActive) cont.resume(false)
            }
        } ?: false
        val r = if (ok) Wav.decode(file) else null
        runCatching { file.delete() }
        return r
    }

    private suspend fun initTts(): TextToSpeech? {
        val ok = CompletableOk()
        val engine = withContext(Dispatchers.Main) {
            var holder: TextToSpeech? = null
            holder = TextToSpeech(app) { status -> ok.complete(status == TextToSpeech.SUCCESS) }
            holder
        }
        val success = withTimeoutOrNull(8_000) { ok.await() } ?: false
        if (!success || engine == null) { runCatching { engine?.shutdown() }; return null }
        val avail = engine.setLanguage(Locale("ru", "RU"))
        if (avail == TextToSpeech.LANG_MISSING_DATA || avail == TextToSpeech.LANG_NOT_SUPPORTED) { engine.shutdown(); return null }
        tts = engine
        return engine
    }

    private class CompletableOk {
        private val d = kotlinx.coroutines.CompletableDeferred<Boolean>()
        fun complete(v: Boolean) { d.complete(v) }
        suspend fun await() = d.await()
    }

    private fun fmt(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

    companion object {
        private const val CHUNK_MS = 26_000L
        private const val AHEAD_MS = 80_000L
    }
}
