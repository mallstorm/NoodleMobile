package pro.noodle.mobile.ui.player

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.text.CueGroup
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

internal enum class DubbingPhase {
    OFF,
    PREPARING,
    ACTIVE,
    ERROR,
}

internal data class DubbingUiState(
    val enabled: Boolean = false,
    val phase: DubbingPhase = DubbingPhase.OFF,
    val message: String = "RU AI",
    val sourceLanguage: String = "",
)

/**
 * Live Russian voice-over bound to Media3 subtitle cues.
 *
 * The controller intentionally does not download media or create a second player. It enables one
 * text track in the current Media3 timeline, translates cue text on-device with ML Kit and sends
 * the translated cue to Android TTS while the original audio is ducked. Seeking and source
 * switching remain synchronized because Media3 is still the only playback clock.
 */
internal class RussianDubbingController(
    context: Context,
    private val player: Player,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val languageIdentifier: LanguageIdentifier = LanguageIdentification.getClient()
    private var translator: Translator? = null
    private var translatorSourceLanguage: String = ""
    private var cueJob: Job? = null
    private var enabled = false
    private var ttsReady = false
    private var closed = false
    private var lastCueText = ""
    private var originalVolume = 1f
    private var originalTrackParameters: TrackSelectionParameters? = null
    private lateinit var tts: TextToSpeech

    private val mutableState = MutableStateFlow(DubbingUiState())
    val state: StateFlow<DubbingUiState> = mutableState.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onCues(cueGroup: CueGroup) {
            if (!enabled) return
            val text = cueGroup.cues
                .mapNotNull { it.text?.toString() }
                .joinToString(" ")
                .cleanCueText()
            if (text.isNotBlank() && text != lastCueText) processCue(text)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying && this@RussianDubbingController::tts.isInitialized) tts.stop()
        }
    }

    init {
        tts = TextToSpeech(appContext) { status ->
            // Post initialization work so the lateinit field is assigned even if an OEM TTS
            // implementation invokes OnInit unusually early.
            scope.launch {
                if (closed) return@launch
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    val result = tts.setLanguage(Locale("ru", "RU"))
                    ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
                    tts.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                }
                if (enabled && !ttsReady) {
                    mutableState.value = DubbingUiState(
                        enabled = true,
                        phase = DubbingPhase.ERROR,
                        message = "Русский голос TTS недоступен",
                    )
                }
            }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = Unit
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = Unit
        })
        player.addListener(listener)
    }

    fun toggle() {
        if (enabled) disable() else enable()
    }

    private fun enable() {
        if (closed || enabled) return
        val textGroup = player.currentTracks.groups.firstOrNull { group ->
            group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any { group.isTrackSupported(it) }
        }
        if (textGroup == null) {
            mutableState.value = DubbingUiState(
                enabled = false,
                phase = DubbingPhase.ERROR,
                message = "Для RU-озвучки нужны субтитры",
            )
            return
        }
        val trackIndex = (0 until textGroup.length).firstOrNull { textGroup.isTrackSupported(it) } ?: 0
        originalVolume = player.volume
        originalTrackParameters = player.trackSelectionParameters
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(textGroup.mediaTrackGroup, trackIndex))
            .build()
        player.volume = VOICE_OVER_ORIGINAL_VOLUME
        enabled = true
        lastCueText = ""
        mutableState.value = DubbingUiState(
            enabled = true,
            phase = DubbingPhase.PREPARING,
            message = "RU AI • ожидаю реплику…",
        )
    }

    private fun disable() {
        if (!enabled) return
        enabled = false
        cueJob?.cancel()
        cueJob = null
        if (this::tts.isInitialized) tts.stop()
        player.volume = originalVolume
        originalTrackParameters?.let { player.trackSelectionParameters = it }
        originalTrackParameters = null
        lastCueText = ""
        mutableState.value = DubbingUiState()
    }

    private fun processCue(text: String) {
        lastCueText = text
        cueJob?.cancel()
        cueJob = scope.launch {
            try {
                mutableState.value = mutableState.value.copy(
                    enabled = true,
                    phase = DubbingPhase.PREPARING,
                    message = if (translatorSourceLanguage.isBlank()) "RU AI • определяю язык…" else "RU AI • перевод…",
                )
                val russian = translateToRussian(text)
                if (!enabled || lastCueText != text || !player.isPlaying) return@launch
                if (!ttsReady) {
                    mutableState.value = mutableState.value.copy(
                        phase = DubbingPhase.ERROR,
                        message = "Русский голос TTS недоступен",
                    )
                    return@launch
                }
                tts.setSpeechRate(player.playbackParameters.speed.coerceIn(0.75f, 1.5f))
                val result = tts.speak(
                    russian,
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "noodle-ru-${System.nanoTime()}",
                )
                if (result == TextToSpeech.SUCCESS) {
                    mutableState.value = mutableState.value.copy(
                        enabled = true,
                        phase = DubbingPhase.ACTIVE,
                        message = "RU AI • закадровый перевод",
                        sourceLanguage = translatorSourceLanguage,
                    )
                } else {
                    mutableState.value = mutableState.value.copy(
                        phase = DubbingPhase.ERROR,
                        message = "Ошибка русской озвучки",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (enabled) {
                    mutableState.value = mutableState.value.copy(
                        enabled = true,
                        phase = DubbingPhase.ERROR,
                        message = "RU AI: ${error.message ?: error.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    private suspend fun translateToRussian(text: String): String {
        if (translatorSourceLanguage.isBlank()) {
            val identified = runCatching { languageIdentifier.identifyLanguage(text).awaitTask() }
                .getOrDefault("und")
            translatorSourceLanguage = identified.takeUnless { it == "und" }.orEmpty()
                .ifBlank { TranslateLanguage.ENGLISH }
        }
        val source = TranslateLanguage.fromLanguageTag(translatorSourceLanguage)
            ?: TranslateLanguage.ENGLISH
        translatorSourceLanguage = source
        if (source == TranslateLanguage.RUSSIAN) return text

        var activeTranslator = translator
        if (activeTranslator == null) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(TranslateLanguage.RUSSIAN)
                .build()
            activeTranslator = Translation.getClient(options)
            translator = activeTranslator
            mutableState.value = mutableState.value.copy(
                phase = DubbingPhase.PREPARING,
                message = "RU AI • загрузка модели $source→ru…",
                sourceLanguage = source,
            )
            activeTranslator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitTask()
        }
        return activeTranslator.translate(text).awaitTask().cleanCueText()
    }

    override fun close() {
        if (closed) return
        disable()
        closed = true
        player.removeListener(listener)
        cueJob?.cancel()
        translator?.close()
        translator = null
        languageIdentifier.close()
        if (this::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        scope.cancel()
    }

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { value -> if (continuation.isActive) continuation.resume(value) }
        addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
        addOnCanceledListener { continuation.cancel() }
    }

    private fun String.cleanCueText(): String =
        replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\{[^}]+}"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private companion object {
        const val VOICE_OVER_ORIGINAL_VOLUME = 0.22f
    }
}
