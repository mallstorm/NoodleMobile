package pro.noodle.mobile.core.search

import android.content.Context
import android.content.SharedPreferences
import pro.noodle.mobile.core.model.VideoItem

/**
 * Отсев «не-видео» карточек: служебные страницы сайта («Информация», «Контакты», «Правила»…),
 * которые адаптер принял за ролик, и карточки, которые уже один раз не открылись
 * («MP4/HLS/DASH URL не найден») — такие запоминаются на 14 дней и в выдачу не попадают.
 */
object ServiceCardFilter {
    private const val PREFS = "noodle_dead_cards"
    private const val TTL_MS = 14L * 24 * 3600 * 1000
    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private val titleStop = setOf(
        "информация", "инфо", "контакты", "контакт", "обратная связь", "связь с нами", "правила", "правила сайта",
        "реклама", "для рекламодателей", "о сайте", "о нас", "об авторе", "правообладателям", "для правообладателей",
        "dmca", "пользовательское соглашение", "политика конфиденциальности", "конфиденциальность", "соглашение",
        "вход", "войти", "регистрация", "авторизация", "главная", "главная страница", "помощь", "faq", "карта сайта",
        "сообщить об ошибке", "пожаловаться", "жалоба", "добавить видео", "добавить фильм", "новости", "rss",
        "about", "about us", "contact", "contacts", "contact us", "privacy", "privacy policy", "terms", "terms of use",
        "login", "log in", "sign in", "sign up", "register", "home", "help", "sitemap", "advertising", "feedback", "rules",
        "information", "info", "support", "cookies", "cookie policy", "2257", "18 u.s.c. 2257",
    )
    private val pathStop = Regex(
        "^/(?:info|information|informatsiya|contacts?|kontakty|feedback|rules|pravila|dmca|about|about-us|abuse|sitemap|login|register|registration|signup|signin|privacy|privacy-policy|terms|tos|adv|ads|advertising|reklama|help|faq|support|2257|cookies?|user|users|profile|account|lostpassword|admin|tags?|categories|category|cat|genres?|actors?|models?|studios?|channels?)/?$",
        RegexOption.IGNORE_CASE,
    )

    private fun norm(s: String) = s.lowercase().replace('ё', 'е').replace(Regex("[^a-zа-я0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun key(url: String) = url.substringBefore('#').trimEnd('/').lowercase()

    fun isServiceCard(v: VideoItem): Boolean {
        if (norm(v.title) in titleStop.map(::norm)) return true
        val path = runCatching { java.net.URI(v.watchUrl).path.orEmpty() }.getOrDefault("")
        return path.isNotEmpty() && path != "/" && pathStop.matches(path)
    }

    fun isDead(url: String): Boolean {
        val p = prefs ?: return false
        val at = p.getLong(key(url), 0L)
        if (at == 0L) return false
        if (System.currentTimeMillis() - at > TTL_MS) { p.edit().remove(key(url)).apply(); return false }
        return true
    }

    fun markDead(url: String) {
        if (url.isBlank()) return
        prefs?.edit()?.putLong(key(url), System.currentTimeMillis())?.apply()
    }

    fun isNoMediaCode(code: String) = code == "PLAYER_CONFIG_MEDIA_NOT_FOUND" || code.endsWith("_MEDIA_NOT_FOUND")

    /**
     * [batch] — карточки одного источника из одного ответа. Если у большинства есть длительность,
     * а у карточки нет ни длительности, ни превью — это не ролик, а ссылка меню/раздела.
     */
    fun filterBatch(batch: List<VideoItem>): List<VideoItem> {
        val withDuration = batch.count { it.duration.isNotBlank() }
        val strict = batch.size >= 3 && withDuration * 2 >= batch.size
        return batch.filter { v ->
            !isServiceCard(v) && !isDead(v.watchUrl) &&
                !(strict && v.duration.isBlank() && v.thumbnailUrl.isBlank() && v.views.isBlank())
        }
    }
}
