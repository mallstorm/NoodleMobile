from pathlib import Path

controller = Path("app/src/main/java/pro/noodle/mobile/ui/player/RussianDubbingController.kt")
text = controller.read_text()

old_doc = """/**
 * Russian AI voice-over synchronized to the Media3 playback clock.
 *
 * Subtitle path remains low-latency live TTS. For video without text tracks the controller uses
 * ASR, translates timed segments and pre-renders Russian speech into a persistent audio cache.
 * A dedicated ExoPlayer then starts the prepared speech file at the exact main-player offset.
 */"""
new_doc = """/**
 * Russian AI auto-dubbing synchronized to the Media3 playback clock.
 *
 * Speech-first pipeline: the audio track is always the source, regardless of subtitle availability.
 * Audio is transcribed with speaker timing, translated to Russian and pre-rendered into a persistent
 * speech cache. A dedicated ExoPlayer starts each prepared Russian segment at the main-player clock.
 */"""
if old_doc not in text:
    raise SystemExit("controller documentation block not found")
text = text.replace(old_doc, new_doc, 1)

old_enable = """    private fun enable() {
        if (closed || enabled) return
        activeConfig = preferences.load()
        val textGroup = player.currentTracks.groups.firstOrNull { group ->
            group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any { group.isTrackSupported(it) }
        }
        if (textGroup != null) enableSubtitleMode(textGroup) else enableAsrMode()
    }
"""
new_enable = """    private fun enable() {
        if (closed || enabled) return
        activeConfig = preferences.load()
        // RU AI is deliberately speech-first. Subtitles never decide the dubbing path.
        enableAsrMode()
    }
"""
if old_enable not in text:
    raise SystemExit("enable() block not found")
text = text.replace(old_enable, new_enable, 1)

old_save = '            if (usingAsr) restartAsrPipeline() else applyConfiguredVoice("")\n'
if old_save not in text:
    raise SystemExit("saveConfiguration branch not found")
text = text.replace(old_save, '            restartAsrPipeline()\n', 1)

text = text.replace(
    "val source: DubbingSource = DubbingSource.SUBTITLES,",
    "val source: DubbingSource = DubbingSource.ASR,",
    1,
)
text = text.replace(
    'message = "RU AI • готовлю защищённый ASR…",',
    'message = "RU AI • слушаю речь и готовлю дубляж…",',
    1,
)
text = text.replace("const val CHUNK_MS = 60_000L", "const val CHUNK_MS = 30_000L", 1)
text = text.replace("const val INITIAL_RENDER_AHEAD_MS = 20_000L", "const val INITIAL_RENDER_AHEAD_MS = 12_000L", 1)
text = text.replace("const val PREFETCH_BEFORE_END_MS = 18_000L", "const val PREFETCH_BEFORE_END_MS = 10_000L", 1)
controller.write_text(text)

surface = Path("app/src/main/java/pro/noodle/mobile/ui/player/PlayerVideoSurface.kt")
text = surface.read_text()
old_desc = '"Субтитры переводятся локально. ASR API используется только для видео без субтитров."'
new_desc = '"RU AI всегда переводит именно речь из аудиодорожки. Субтитры для автодубляжа не требуются и не используются как источник."'
if old_desc not in text:
    raise SystemExit("old settings description not found")
text = text.replace(old_desc, new_desc, 1)
text = text.replace(
    'Text("ASR для видео без субтитров", style = MaterialTheme.typography.labelLarge)',
    'Text("Распознавание речи и автодубляж", style = MaterialTheme.typography.labelLarge)',
    1,
)
text = text.replace(
    '"Облачное распознавание может тарифицироваться выбранным провайдером."',
    '"Аудио распознаётся по речи; облачный ASR может тарифицироваться выбранным провайдером."',
    1,
)
surface.write_text(text)
