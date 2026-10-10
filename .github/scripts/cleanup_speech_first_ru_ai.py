from pathlib import Path

path = Path("app/src/main/java/pro/noodle/mobile/ui/player/RussianDubbingController.kt")
text = path.read_text()

replacements = [
    ("import androidx.media3.common.TrackSelectionOverride\n", ""),
    ("import androidx.media3.common.TrackSelectionParameters\n", ""),
    ("import androidx.media3.common.text.CueGroup\n", ""),
    ("internal enum class DubbingSource {\n    SUBTITLES,\n    ASR,\n}", "internal enum class DubbingSource {\n    ASR,\n}"),
    ("    private var lastCueText = \"\"\n", ""),
    ("    private var originalTrackParameters: TrackSelectionParameters? = null\n", ""),
    ("""        override fun onCues(cueGroup: CueGroup) {
            if (!enabled || usingAsr) return
            val text = cueGroup.cues
                .mapNotNull { it.text?.toString() }
                .joinToString(" ")
                .cleanCueText()
            if (text.isNotBlank() && text != lastCueText) processSubtitleCue(text)
        }

""", ""),
    ("""                if (usingAsr) {
                    dubbedAudioPlayer.pause()
                    spokenAsrKey = ""
                } else if (this@RussianDubbingController::liveTts.isInitialized) {
                    liveTts.stop()
                }
            } else if (usingAsr) {
                spokenAsrKey = ""
            }
""", """                dubbedAudioPlayer.pause()
                spokenAsrKey = ""
                if (this@RussianDubbingController::liveTts.isInitialized) liveTts.stop()
            } else {
                spokenAsrKey = ""
            }
"""),
    ("                if (enabled && !liveTtsReady && !usingAsr) showTtsUnavailable()\n", "                if (enabled && !liveTtsReady) showTtsUnavailable()\n"),
    ("""    private fun enableSubtitleMode(textGroup: androidx.media3.common.Tracks.Group) {
        val trackIndex = (0 until textGroup.length).firstOrNull { textGroup.isTrackSupported(it) } ?: 0
        rememberPlaybackState()
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(textGroup.mediaTrackGroup, trackIndex))
            .build()
        player.volume = targetOriginalVolume(activeConfig.mode)
        enabled = true
        usingAsr = false
        lastCueText = ""
        mutableState.value = DubbingUiState(
            enabled = true,
            phase = DubbingPhase.PREPARING,
            message = "RU AI • ожидаю реплику…",
            source = DubbingSource.SUBTITLES,
        )
    }

""", ""),
    ("""    private fun rememberPlaybackState() {
        originalVolume = player.volume
        originalTrackParameters = player.trackSelectionParameters
    }
""", """    private fun rememberPlaybackState() {
        originalVolume = player.volume
    }
"""),
    ("        originalTrackParameters?.let { player.trackSelectionParameters = it }\n        originalTrackParameters = null\n", ""),
    ("        lastCueText = \"\"\n", ""),
    ("""    private fun processSubtitleCue(text: String) {
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
                if (!enabled || usingAsr || lastCueText != text || !player.isPlaying) return@launch
                speakLive(
                    russian = russian,
                    speaker = "",
                    segmentDurationMs = 0L,
                    activeMessage = if (activeConfig.mode == DubbingMode.DUB) "RU AI • дубляж" else "RU AI • закадровый перевод",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showSpeechError(error)
            }
        }
    }

""", ""),
    ("                source = if (usingAsr) DubbingSource.ASR else DubbingSource.SUBTITLES,\n", "                source = DubbingSource.ASR,\n"),
]

for old, new in replacements:
    if old not in text:
        raise SystemExit(f"expected cleanup target not found: {old[:100]!r}")
    text = text.replace(old, new, 1)

for forbidden in [
    "DubbingSource.SUBTITLES",
    "enableSubtitleMode",
    "onCues(",
    "CueGroup",
    "TrackSelectionOverride",
    "TrackSelectionParameters",
    "TRACK_TYPE_TEXT",
    "processSubtitleCue",
    "lastCueText",
]:
    if forbidden in text:
        raise SystemExit(f"legacy subtitle dubbing code still present: {forbidden}")

path.write_text(text)
