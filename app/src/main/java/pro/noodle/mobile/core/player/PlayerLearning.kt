package pro.noodle.mobile.core.player

import android.content.Context
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Правила, которые пользователь «вписывает» в адаптер кнопкой «Это превью / не то видео».
 * Хранятся рядом с адаптером (по id источника) и добавляются к stream.reject при каждом разборе страницы.
 */
class StreamRuleStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("noodle_stream_rules", Context.MODE_PRIVATE)

    fun rejectFor(sourceKey: String): String =
        prefs.getStringSet(key(sourceKey), emptySet()).orEmpty().filter { it.isNotBlank() }.joinToString("|")

    fun rules(sourceKey: String): List<String> = prefs.getStringSet(key(sourceKey), emptySet()).orEmpty().sorted()

    /** Превращает конкретный URL в обобщённый шаблон: хост + путь, где числа заменены на \d+. */
    fun addReject(sourceKey: String, url: String): String? {
        val pattern = patternFor(url) ?: return null
        val set = prefs.getStringSet(key(sourceKey), emptySet()).orEmpty().toMutableSet()
        if (set.add(pattern)) prefs.edit().putStringSet(key(sourceKey), set.take(40).toSet()).apply()
        return pattern
    }

    fun clear(sourceKey: String) { prefs.edit().remove(key(sourceKey)).apply() }

    private fun key(sourceKey: String) = "reject_" + sourceKey.lowercase()

    companion object {
        fun patternFor(url: String): String? {
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            val host = uri.host ?: return null
            val path = uri.path.orEmpty()
            val file = path.substringAfterLast('/')
            val dir = path.substringBeforeLast('/', "")
            // Если на сервере у превью и полных видео общий каталог — режем по каталогу и имени без номеров.
            fun gen(part: String) = Regex("""\d+""").split(part).joinToString("\\d+") { if (it.isEmpty()) "" else Regex.escape(it) }
            // Обобщаем номера только если путь явно похож на превью; иначе правило точное
            // (иначе общий шаблон «хост/каталог/номер.mp4» отбросил бы и полные видео).
            val previewish = Regex("preview|trailer|teaser|thumb|mini|short|clip|sample|hover|snippet", RegexOption.IGNORE_CASE).containsMatchIn(path)
            return if (previewish) Regex.escape(host) + gen(dir) + "/" + gen(file)
            else Regex.escape(host + path)
        }
    }
}

/** Журнал последних попыток воспроизведения (кто выбран, за сколько секунд стартовал, чем закончилось). */
class PlaybackLogStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("noodle_playback_log", Context.MODE_PRIVATE)
    private val fmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun add(sourceKey: String, title: String, url: String, startSeconds: Double, outcome: String) {
        val host = runCatching { URI(url).host }.getOrNull().orEmpty()
        val line = "${fmt.format(Date())} | ${title.take(40).replace('\n', ' ')} | $host | " +
            (if (startSeconds >= 0) "старт %.1f с".format(Locale.US, startSeconds) else "без старта") + " | $outcome"
        val list = (listOf(line) + lines(sourceKey)).take(MAX)
        prefs.edit().putString("log_" + sourceKey.lowercase(), list.joinToString("\n")).apply()
    }

    fun lines(sourceKey: String): List<String> =
        prefs.getString("log_" + sourceKey.lowercase(), "").orEmpty().split('\n').filter { it.isNotBlank() }

    fun dump(sourceKey: String): String = lines(sourceKey).joinToString("\n")

    private companion object { const val MAX = 20 }
}
