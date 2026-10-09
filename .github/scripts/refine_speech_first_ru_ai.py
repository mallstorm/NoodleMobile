from pathlib import Path
import re

controller = Path("app/src/main/java/pro/noodle/mobile/ui/player/RussianDubbingController.kt")
cache = Path("app/src/main/java/pro/noodle/mobile/ui/player/DubbingAudioCache.kt")

text = controller.read_text()


def replace_once(old: str, new: str, label: str) -> None:
    global text
    if old not in text:
        raise SystemExit(f"missing block: {label}")
    text = text.replace(old, new, 1)


# Imports: remove subtitle-only track/cue types; add duration inspection for rendered speech.
for obsolete in (
    "import androidx.media3.common.TrackSelectionOverride\n",
    "import androidx.media3.common.TrackSelectionParameters\n",
    "import androidx.media3.common.text.CueGroup\n",
):
    text = text.replace(obsolete, "")
if "import android.media.MediaMetadataRetriever\n" not in text:
    text = text.replace(
        "import android.media.AudioAttributes\n",
        "import android.media.AudioAttributes\nimport android.media.MediaMetadataRetriever\n",
        1,
    )

# Speech is now the only source for RU AI.
text, count = re.subn(
    r"\ninternal enum class DubbingSource \{\n    SUBTITLES,\n    ASR,\n\}\n",
    "\n",
    text,
    count=1,
)
if count != 1:
    raise SystemExit("DubbingSource enum not found")
text = re.sub(r"\n\s*val source: DubbingSource = DubbingSource\.ASR,", "", text, count=1)

# State that only existed to support subtitle mode.
text = text.replace("    private var lastCueText = \"\"\n", "")
text = text.replace("    private var originalTrackParameters: TrackSelectionParameters? = null\n", "")

# Remove cue-based listener completely.
text, count = re.subn(
    r"\n        override fun onCues\(cueGroup: CueGroup\) \{.*?\n        \}\n\n        override fun onIsPlayingChanged",
    "\n        override fun onIsPlayingChanged",
    text,
    count=1,
    flags=re.S,
)
if count != 1:
    raise SystemExit("onCues block not found")

old_playing = '''        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) {
                if (usingAsr) {
                    dubbedAudioPlayer.pause()
                    spokenAsrKey = ""
                } else if (this@RussianDubbingController::liveTts.isInitialized) {
                    liveTts.stop()
                }
            } else if (usingAsr) {
                spokenAsrKey = ""
            }
        }
'''
new_playing = '''        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) {
                dubbedAudioPlayer.pause()
                if (this@RussianDubbingController::liveTts.isInitialized) liveTts.stop()
                spokenAsrKey = ""
                setIdleOriginalVolume()
            } else if (enabled) {
                spokenAsrKey = ""
            }
        }
'''
replace_once(old_playing, new_playing, "onIsPlayingChanged")
text = text.replace(
    "                if (enabled && !liveTtsReady && !usingAsr) showTtsUnavailable()\n",
    "                if (enabled && !liveTtsReady) showTtsUnavailable()\n",
    1,
)

# Remove the old subtitle mode implementation. It must no longer exist even as dead code.
text, count = re.subn(
    r"\n    private fun enableSubtitleMode\(textGroup: androidx\.media3\.common\.Tracks\.Group\) \{.*?\n    \}\n\n    private fun enableAsrMode",
    "\n    private fun enableAsrMode",
    text,
    count=1,
    flags=re.S,
)
if count != 1:
    raise SystemExit("enableSubtitleMode block not found")

# All UI state source markers are obsolete now that there is only speech/ASR.
text = re.sub(r"\n\s*source = DubbingSource\.(?:ASR|SUBTITLES),", "", text)

# Playback-state memory no longer touches subtitle track selection.
old_remember = '''    private fun rememberPlaybackState() {
        originalVolume = player.volume
        originalTrackParameters = player.trackSelectionParameters
    }
'''
new_remember = '''    private fun rememberPlaybackState() {
        originalVolume = player.volume
    }
'''
replace_once(old_remember, new_remember, "rememberPlaybackState")

text = text.replace("        originalTrackParameters?.let { player.trackSelectionParameters = it }\n", "")
text = text.replace("        originalTrackParameters = null\n", "")
text = text.replace("        lastCueText = \"\"\n", "")

# Remove subtitle cue processing function.
text, count = re.subn(
    r"\n    private fun processSubtitleCue\(text: String\) \{.*?\n    \}\n\n    private fun processAsrSegment",
    "\n    private fun processAsrSegment",
    text,
    count=1,
    flags=re.S,
)
if count != 1:
    raise SystemExit("processSubtitleCue block not found")

# Dynamic ducking: keep ambience audible between translated speech, duck hard only during RU speech.
text = text.replace("            player.volume = targetOriginalVolume(activeConfig.mode)\n", "            setIdleOriginalVolume()\n")
text = text.replace("        player.volume = targetOriginalVolume(activeConfig.mode)\n", "        setIdleOriginalVolume()\n")

old_scheduler = '''                if (player.isPlaying) {
                    val segment = segments.firstOrNull {
                        position >= (it.startMs - SEGMENT_EARLY_WINDOW_MS).coerceAtLeast(0L) &&
                            position <= it.endMs + SEGMENT_LATE_WINDOW_MS
                    }
                    if (segment != null) {
                        val key = segmentKey(segment)
                        if (key != spokenAsrKey) {
                            spokenAsrKey = key
                            processAsrSegment(segment, key)
                        }
                    }
                }
'''
new_scheduler = '''                if (player.isPlaying) {
                    val segment = segments.firstOrNull {
                        position >= (it.startMs - SEGMENT_EARLY_WINDOW_MS).coerceAtLeast(0L) &&
                            position <= it.endMs + SEGMENT_LATE_WINDOW_MS
                    }
                    if (segment != null) {
                        setSpeechOriginalVolume()
                        val key = segmentKey(segment)
                        if (key != spokenAsrKey) {
                            spokenAsrKey = key
                            processAsrSegment(segment, key)
                        }
                    } else {
                        setIdleOriginalVolume()
                    }
                }
'''
replace_once(old_scheduler, new_scheduler, "ASR scheduler ducking")

# Replace rendered-TTS path with duration-aware two-pass fitting and cache key based on translated speech.
pattern = r"    private suspend fun renderAsrSegment\(segment: AsrSegment\): File\? = renderMutex\.withLock \{.*?\n    \}\n\n    private suspend fun awaitRenderTtsReady"
replacement = '''    private suspend fun renderAsrSegment(segment: AsrSegment): File? = renderMutex.withLock {
        if (!enabled || !usingAsr) return@withLock null
        awaitRenderTtsReady()
        val mediaKey = activeMediaKey.ifBlank {
            player.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty()
        }
        if (mediaKey.isBlank()) return@withLock null
        val voice = selectVoice(renderTts, segment.speaker) ?: return@withLock null
        val russian = translateToRussian(segment.text)
        if (russian.isBlank()) return@withLock null
        val target = audioCache.targetFile(
            mediaKey = mediaKey,
            model = activeConfig.model,
            voiceName = voice.name,
            segment = segment,
            russianText = russian,
        )
        audioCache.existing(target)?.let { return@withLock it }

        val segmentDurationMs = (segment.endMs - segment.startMs).coerceAtLeast(400L)
        val estimatedMs = (russian.length * APPROX_MS_PER_CHARACTER).toFloat()
        val initialRate = (estimatedMs / segmentDurationMs.toFloat()).coerceIn(0.78f, 1.65f)
        val temp = audioCache.tempFile(target)
        try {
            renderSpeechToFile(russian, voice, initialRate, temp)
            val actualMs = audioDurationMs(temp)
            if (actualMs > 0L) {
                val ratio = actualMs.toFloat() / segmentDurationMs.toFloat()
                if (ratio !in TTS_DURATION_FIT_MIN..TTS_DURATION_FIT_MAX) {
                    val correctedRate = (initialRate * ratio).coerceIn(MIN_RENDER_SPEECH_RATE, MAX_RENDER_SPEECH_RATE)
                    if (abs(correctedRate - initialRate) >= MIN_RERENDER_RATE_DELTA) {
                        temp.delete()
                        renderSpeechToFile(russian, voice, correctedRate, temp)
                    }
                }
            }
            val committed = audioCache.commit(temp, target)
            withContext(Dispatchers.IO) { audioCache.prune() }
            committed
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private suspend fun renderSpeechToFile(
        russian: String,
        voice: Voice,
        speechRate: Float,
        output: File,
    ) {
        renderTts.voice = voice
        renderTts.setSpeechRate(speechRate)
        val utteranceId = "noodle-render-${System.nanoTime()}"
        val waiter = CompletableDeferred<Unit>()
        renderWaiters[utteranceId] = waiter
        try {
            val result = renderTts.synthesizeToFile(russian, Bundle(), output, utteranceId)
            if (result != TextToSpeech.SUCCESS) error("RU_TTS_RENDER_START_FAILED")
            withTimeout(TTS_RENDER_TIMEOUT_MS) { waiter.await() }
        } finally {
            renderWaiters.remove(utteranceId)
        }
    }

    private fun audioDurationMs(file: File): Long = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }.getOrDefault(0L)

    private suspend fun awaitRenderTtsReady'''
text, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
if count != 1:
    raise SystemExit("renderAsrSegment block not found")

# Ensure speech starts with hard ducking whether prepared or live fallback is used.
text = text.replace(
    '''    private fun processAsrSegment(segment: AsrSegment, expectedKey: String) {
        val prepared = preparedAudioFiles[expectedKey]?.takeIf { it.isFile }
''',
    '''    private fun processAsrSegment(segment: AsrSegment, expectedKey: String) {
        setSpeechOriginalVolume()
        val prepared = preparedAudioFiles[expectedKey]?.takeIf { it.isFile }
''',
    1,
)
text = text.replace(
    '''    private fun playPreparedSegment(segment: AsrSegment, expectedKey: String, file: File) {
        if (!enabled || !usingAsr || spokenAsrKey != expectedKey || !player.isPlaying) return
''',
    '''    private fun playPreparedSegment(segment: AsrSegment, expectedKey: String, file: File) {
        if (!enabled || !usingAsr || spokenAsrKey != expectedKey || !player.isPlaying) return
        setSpeechOriginalVolume()
''',
    1,
)
text = text.replace(
    '''        applyConfiguredVoice(speaker)
''',
    '''        setSpeechOriginalVolume()
        applyConfiguredVoice(speaker)
''',
    1,
)

# Remove now-invalid source field from live fallback state copy if still present.
text = re.sub(
    r"\n\s*source = if \(usingAsr\) DubbingSource\.ASR else DubbingSource\.SUBTITLES,",
    "",
    text,
)

# Replace fixed duck level helper with speech/idle levels.
old_volume = '''    private fun targetOriginalVolume(mode: DubbingMode): Float =
        if (mode == DubbingMode.DUB) DUB_ORIGINAL_VOLUME else VOICE_OVER_ORIGINAL_VOLUME
'''
new_volume = '''    private fun setSpeechOriginalVolume() {
        if (!enabled) return
        player.volume = originalVolume.coerceAtMost(targetSpeechOriginalVolume(activeConfig.mode))
    }

    private fun setIdleOriginalVolume() {
        if (!enabled) return
        player.volume = originalVolume.coerceAtMost(targetIdleOriginalVolume(activeConfig.mode))
    }

    private fun targetSpeechOriginalVolume(mode: DubbingMode): Float =
        if (mode == DubbingMode.DUB) DUB_SPEECH_ORIGINAL_VOLUME else VOICE_OVER_SPEECH_ORIGINAL_VOLUME

    private fun targetIdleOriginalVolume(mode: DubbingMode): Float =
        if (mode == DubbingMode.DUB) DUB_IDLE_ORIGINAL_VOLUME else VOICE_OVER_IDLE_ORIGINAL_VOLUME
'''
replace_once(old_volume, new_volume, "volume helper")

# Lower-latency rolling ASR windows.
text = text.replace("        const val VOICE_OVER_ORIGINAL_VOLUME = 0.22f\n", "        const val VOICE_OVER_SPEECH_ORIGINAL_VOLUME = 0.22f\n")
text = text.replace("        const val DUB_ORIGINAL_VOLUME = 0.05f\n", "        const val DUB_SPEECH_ORIGINAL_VOLUME = 0.05f\n")
text = text.replace("        const val CHUNK_MS = 30_000L\n", "        const val VOICE_OVER_IDLE_ORIGINAL_VOLUME = 0.72f\n        const val DUB_IDLE_ORIGINAL_VOLUME = 0.32f\n        const val CHUNK_MS = 20_000L\n")
text = text.replace("        const val INITIAL_RENDER_AHEAD_MS = 12_000L\n", "        const val INITIAL_RENDER_AHEAD_MS = 8_000L\n")
text = text.replace("        const val PREFETCH_BEFORE_END_MS = 10_000L\n", "        const val PREFETCH_BEFORE_END_MS = 7_000L\n")
text = text.replace(
    "        const val TTS_RENDER_TIMEOUT_MS = 45_000L\n",
    "        const val TTS_RENDER_TIMEOUT_MS = 45_000L\n        const val TTS_DURATION_FIT_MIN = 0.88f\n        const val TTS_DURATION_FIT_MAX = 1.12f\n        const val MIN_RENDER_SPEECH_RATE = 0.72f\n        const val MAX_RENDER_SPEECH_RATE = 1.80f\n        const val MIN_RERENDER_RATE_DELTA = 0.06f\n",
    1,
)

# Sanity: there must be no subtitle-source mechanics left in the controller.
for forbidden in (
    "DubbingSource",
    "enableSubtitleMode",
    "onCues(",
    "TRACK_TYPE_TEXT",
    "TrackSelectionOverride",
    "CueGroup",
    "processSubtitleCue",
):
    if forbidden in text:
        raise SystemExit(f"subtitle residue remains: {forbidden}")

controller.write_text(text)

# Cache must depend on the actual translated Russian payload, not only the source transcript.
cache_text = cache.read_text()
cache_text = cache_text.replace(
    '''        voiceName: String,
        segment: AsrSegment,
    ): File {''',
    '''        voiceName: String,
        segment: AsrSegment,
        russianText: String,
    ): File {''',
    1,
)
cache_text = cache_text.replace(
    '''            append(segment.speaker).append('|')
            append(segment.text)
''',
    '''            append(segment.speaker).append('|')
            append(segment.text).append('|')
            append(russianText)
''',
    1,
)
cache_text = cache_text.replace('        const val CACHE_VERSION = "v1"', '        const val CACHE_VERSION = "v2"', 1)
if "russianText: String" not in cache_text or 'CACHE_VERSION = "v2"' not in cache_text:
    raise SystemExit("cache migration failed")
cache.write_text(cache_text)

print("speech-first RU AI refinement applied")
