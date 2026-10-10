from pathlib import Path

path = Path("app/src/main/java/pro/noodle/mobile/ui/player/RussianDubbingController.kt")
text = path.read_text()


def replace_once(old: str, new: str, label: str) -> None:
    global text
    if old not in text:
        raise SystemExit(f"missing target: {label}")
    text = text.replace(old, new, 1)


replace_once(
    "    private val audioCache = DubbingAudioCache(appContext)\n    private val dubbedAudioPlayer = ExoPlayer.Builder(appContext).build()\n",
    "    private val audioCache = DubbingAudioCache(appContext)\n    private val timelineAssembler = DubbingTimelineAssembler(appContext)\n    private val dubbedAudioPlayer = ExoPlayer.Builder(appContext).build()\n",
    "timeline assembler field",
)

replace_once(
    "    private val preparedAudioFiles = mutableMapOf<String, File>()\n    private val preparedChunks = mutableSetOf<Long>()\n",
    "    private val preparedAudioFiles = mutableMapOf<String, File>()\n    private val preparedTimelineChunks = mutableMapOf<Long, File>()\n    private val preparedChunks = mutableSetOf<Long>()\n",
    "prepared timeline map",
)

replace_once(
    "    private var activeConfig = preferences.load()\n    private var activeMediaKey = \"\"\n",
    "    private var activeConfig = preferences.load()\n    private var activeMediaKey = \"\"\n    private var activeTimelineChunkStart = NO_TIMELINE_CHUNK\n",
    "active timeline state",
)

# Both restart and disable clear the rendered/timeline session state.
text = text.replace(
    "        preparedAudioFiles.clear()\n        preparedChunks.clear()\n        dubbedAudioPlayer.stop()\n",
    "        preparedAudioFiles.clear()\n        preparedTimelineChunks.clear()\n        preparedChunks.clear()\n        activeTimelineChunkStart = NO_TIMELINE_CHUNK\n        dubbedAudioPlayer.stop()\n",
    2,
)
if text.count("preparedTimelineChunks.clear()") != 2:
    raise SystemExit("expected restart+disable timeline cleanup")

replace_once(
    "                player.seekTo(anchor)\n                if (resumePlayback) player.play()\n                runAsrScheduler()\n",
    "                player.seekTo(anchor)\n                if (resumePlayback) player.play()\n                // Start the next speech window immediately so ASR + translation + TTS have a full\n                // chunk of lead time before the timeline boundary.\n                queueAsrChunk(initialStart + CHUNK_MS, announce = false)\n                runAsrScheduler()\n",
    "initial next-chunk prefetch",
)

old_scheduler = '''    private suspend fun runAsrScheduler() {
        while (scope.isActive && enabled && usingAsr) {
            val position = player.currentPosition.coerceAtLeast(0L)
            if (abs(position - lastPlayerPositionMs) > SEEK_RESET_THRESHOLD_MS) {
                dubbedAudioPlayer.stop()
                spokenAsrKey = ""
            }
            lastPlayerPositionMs = position

            val chunkStart = chunkStartFor(position)
            val segments = asrChunks[chunkStart]
            if (segments == null && asrLoadJobs[chunkStart]?.isActive != true) {
                queueAsrChunk(chunkStart, announce = true)
            } else if (segments != null) {
                queueRenderChunk(chunkStart, segments, announce = false)
                if (player.isPlaying) {
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
            }

            val offset = position - chunkStart
            if (offset >= CHUNK_MS - PREFETCH_BEFORE_END_MS) {
                queueAsrChunk(chunkStart + CHUNK_MS, announce = false)
            }
            delay(SCHEDULER_TICK_MS)
        }
    }
'''
new_scheduler = '''    private suspend fun runAsrScheduler() {
        while (scope.isActive && enabled && usingAsr) {
            val position = player.currentPosition.coerceAtLeast(0L)
            if (abs(position - lastPlayerPositionMs) > SEEK_RESET_THRESHOLD_MS) {
                stopDubbedAudioPlayback()
                spokenAsrKey = ""
            }
            lastPlayerPositionMs = position

            val chunkStart = chunkStartFor(position)
            val segments = asrChunks[chunkStart]
            if (segments == null && asrLoadJobs[chunkStart]?.isActive != true) {
                queueAsrChunk(chunkStart, announce = true)
            } else if (segments != null) {
                queueRenderChunk(chunkStart, segments, announce = false)
                if (player.isPlaying) {
                    val activeSegment = segments.firstOrNull {
                        position >= (it.startMs - SEGMENT_EARLY_WINDOW_MS).coerceAtLeast(0L) &&
                            position <= it.endMs + SEGMENT_LATE_WINDOW_MS
                    }
                    val timeline = preparedTimelineChunks[chunkStart]?.takeIf { it.isFile }
                    if (timeline != null) {
                        playOrSyncTimelineChunk(chunkStart, timeline, position)
                        if (activeSegment != null) setSpeechOriginalVolume() else setIdleOriginalVolume()
                    } else {
                        // Until the continuous chunk is ready, retain the existing low-latency
                        // prepared-segment/live-TTS path instead of stalling playback.
                        if (activeTimelineChunkStart != NO_TIMELINE_CHUNK) stopDubbedAudioPlayback()
                        if (activeSegment != null) {
                            setSpeechOriginalVolume()
                            val key = segmentKey(activeSegment)
                            if (key != spokenAsrKey) {
                                spokenAsrKey = key
                                processAsrSegment(activeSegment, key)
                            }
                        } else {
                            setIdleOriginalVolume()
                            if (activeTimelineChunkStart == NO_TIMELINE_CHUNK && dubbedAudioPlayer.isPlaying) {
                                dubbedAudioPlayer.stop()
                            }
                        }
                    }
                }
            }

            val offset = position - chunkStart
            if (offset >= CHUNK_MS - PREFETCH_BEFORE_END_MS) {
                queueAsrChunk(chunkStart + CHUNK_MS, announce = false)
            }
            delay(SCHEDULER_TICK_MS)
        }
    }
'''
replace_once(old_scheduler, new_scheduler, "ASR scheduler")

old_queue_render = '''    private fun queueRenderChunk(startMs: Long, segments: List<AsrSegment>, announce: Boolean) {
        if (segments.isEmpty() || startMs in preparedChunks || asrRenderJobs[startMs]?.isActive == true) return
        val job = scope.launch {
            try {
                prepareAsrSegments(segments, announce)
                preparedChunks += startMs
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Per-segment live TTS remains the fallback if pre-rendering is unavailable.
            } finally {
                asrRenderJobs.remove(startMs)
            }
        }
        asrRenderJobs[startMs] = job
    }
'''
new_queue_render = '''    private fun queueRenderChunk(startMs: Long, segments: List<AsrSegment>, announce: Boolean) {
        if (segments.isEmpty() || startMs in preparedChunks || asrRenderJobs[startMs]?.isActive == true) return
        val job = scope.launch {
            try {
                val readyCount = prepareAsrSegments(segments, announce)
                if (!enabled || !usingAsr) return@launch
                if (readyCount == segments.size) {
                    val timeline = buildTimelineChunk(startMs, segments)
                    preparedTimelineChunks[startMs] = timeline
                    mutableState.value = mutableState.value.copy(
                        phase = DubbingPhase.ACTIVE,
                        message = "RU AI • непрерывная русская дорожка готова",
                    )
                }
                // A chunk is considered prepared after one complete pass. If a TTS engine cannot
                // render one segment or emits an unsupported WAV format, the scheduler keeps the
                // proven per-segment/live fallback for this chunk rather than retrying every 120 ms.
                preparedChunks += startMs
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                preparedChunks += startMs
                // Per-segment prepared audio / live TTS remains the fallback.
            } finally {
                asrRenderJobs.remove(startMs)
            }
        }
        asrRenderJobs[startMs] = job
    }
'''
replace_once(old_queue_render, new_queue_render, "chunk render queue")

insert_before = '''    private fun processAsrSegment(segment: AsrSegment, expectedKey: String) {
'''
helpers = '''    private suspend fun buildTimelineChunk(startMs: Long, segments: List<AsrSegment>): File {
        val mediaKey = activeMediaKey.ifBlank {
            player.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty()
        }
        if (mediaKey.isBlank()) error("RU_TIMELINE_MEDIA_KEY_MISSING")
        val speech = segments.map { segment ->
            val file = preparedAudioFiles[segmentKey(segment)]?.takeIf { it.isFile }
                ?: error("RU_TIMELINE_SEGMENT_MISSING")
            DubbingTimelineAssembler.SpeechFile(segment, file)
        }
        val endMs = chunkEndFor(startMs)
        return withContext(Dispatchers.IO) {
            timelineAssembler.assemble(
                mediaKey = mediaKey,
                chunkStartMs = startMs,
                chunkEndMs = endMs,
                speech = speech,
            )
        }
    }

    private fun playOrSyncTimelineChunk(startMs: Long, file: File, mediaPositionMs: Long) {
        if (!enabled || !usingAsr || !player.isPlaying || !file.isFile) return
        val chunkEnd = chunkEndFor(startMs)
        val maxOffset = (chunkEnd - startMs - 1L).coerceAtLeast(0L)
        val expectedOffset = (mediaPositionMs - startMs).coerceIn(0L, maxOffset)
        val mediaId = "ru-timeline:$startMs:${file.name}"
        val currentId = dubbedAudioPlayer.currentMediaItem?.mediaId

        if (activeTimelineChunkStart != startMs || currentId != mediaId) {
            cueJob?.cancel()
            cueJob = null
            if (this::liveTts.isInitialized) liveTts.stop()
            dubbedAudioPlayer.stop()
            dubbedAudioPlayer.playbackParameters = player.playbackParameters
            dubbedAudioPlayer.setMediaItem(
                MediaItem.Builder()
                    .setMediaId(mediaId)
                    .setUri(Uri.fromFile(file))
                    .build(),
                expectedOffset,
            )
            dubbedAudioPlayer.prepare()
            dubbedAudioPlayer.playWhenReady = true
            activeTimelineChunkStart = startMs
            spokenAsrKey = ""
            mutableState.value = mutableState.value.copy(
                enabled = true,
                phase = DubbingPhase.ACTIVE,
                message = if (activeConfig.mode == DubbingMode.DUB) {
                    "RU AI • непрерывный синхронный дубляж"
                } else {
                    "RU AI • непрерывный синхронный закадровый"
                },
                sourceLanguage = translatorSourceLanguage,
            )
            return
        }

        if (!dubbedAudioPlayer.playWhenReady) dubbedAudioPlayer.playWhenReady = true
        val driftMs = dubbedAudioPlayer.currentPosition - expectedOffset
        if (abs(driftMs) >= TIMELINE_DRIFT_CORRECTION_MS) {
            dubbedAudioPlayer.seekTo(expectedOffset)
        }
    }

    private fun stopDubbedAudioPlayback() {
        dubbedAudioPlayer.stop()
        activeTimelineChunkStart = NO_TIMELINE_CHUNK
    }

    private fun chunkEndFor(startMs: Long): Long {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        return if (duration != null) {
            (startMs + CHUNK_MS).coerceAtMost(duration)
        } else {
            startMs + CHUNK_MS
        }
    }

'''
replace_once(insert_before, helpers + insert_before, "timeline helpers insertion")

# Individual-segment playback shares the same secondary ExoPlayer; mark it as non-timeline.
replace_once(
    "        setSpeechOriginalVolume()\n        val offsetMs = (player.currentPosition - segment.startMs)\n",
    "        setSpeechOriginalVolume()\n        activeTimelineChunkStart = NO_TIMELINE_CHUNK\n        val offsetMs = (player.currentPosition - segment.startMs)\n",
    "prepared segment timeline reset",
)

# Live fallback must not leave a stale prepared/timeline file running underneath Android TTS.
replace_once(
    "        setSpeechOriginalVolume()\n        applyConfiguredVoice(speaker)\n",
    "        setSpeechOriginalVolume()\n        stopDubbedAudioPlayback()\n        applyConfiguredVoice(speaker)\n",
    "live fallback timeline stop",
)

replace_once(
    "        const val PREFETCH_BEFORE_END_MS = 7_000L\n",
    "        const val PREFETCH_BEFORE_END_MS = 15_000L\n",
    "prefetch lead",
)

replace_once(
    "        const val MIN_RERENDER_RATE_DELTA = 0.06f\n        const val MAX_VOICE_CHOICES = 8\n",
    "        const val MIN_RERENDER_RATE_DELTA = 0.06f\n        const val TIMELINE_DRIFT_CORRECTION_MS = 120L\n        const val NO_TIMELINE_CHUNK = Long.MIN_VALUE\n        const val MAX_VOICE_CHOICES = 8\n",
    "timeline constants",
)

for required in [
    "DubbingTimelineAssembler(appContext)",
    "preparedTimelineChunks",
    "playOrSyncTimelineChunk",
    "TIMELINE_DRIFT_CORRECTION_MS",
    "непрерывный синхронный дубляж",
]:
    if required not in text:
        raise SystemExit(f"missing continuous-timeline result: {required}")

path.write_text(text)
