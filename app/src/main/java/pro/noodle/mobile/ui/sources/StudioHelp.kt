package pro.noodle.mobile.ui.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private data class HelpSection(val title: String, val blocks: List<HelpBlock>)

private sealed interface HelpBlock {
    data class P(val text: String) : HelpBlock
    data class Steps(val items: List<String>) : HelpBlock
    data class Bullets(val items: List<String>) : HelpBlock
    data class Code(val text: String) : HelpBlock
    data class Tip(val text: String) : HelpBlock
}

private val helpSections = listOf(
    HelpSection(
        "Что такое SNP-адаптер",
        listOf(
            HelpBlock.P("SNP-адаптер — это небольшое описание сайта, по которому Noodle умеет искать на нём видео, показывать превью, длительность и просмотры, открывать ролик в плеере, перематывать его и скачивать."),
            HelpBlock.P("Адаптер не содержит программного кода: только адрес поиска, правило, по которому распознаются карточки видео, и список доменов, к которым приложению разрешено обращаться. Поэтому адаптер безопасен и его легко пересоздать, если сайт поменял дизайн."),
        ),
    ),
    HelpSection(
        "Быстрое создание за 3 шага",
        listOf(
            HelpBlock.Steps(
                listOf(
                    "Откройте «Ещё → Добавить сайт» или «Источники → Добавить сайт».",
                    "В поле «Адрес сайта» введите домен, например example.com. Протокол https:// и путь страницы указывать не нужно.",
                    "В поле «Проверочный запрос» введите слово, по которому на этом сайте точно есть видео, и нажмите «Найти адаптер автоматически».",
                ),
            ),
            HelpBlock.P("Когда Studio закончит, появится карточка результата с найденными роликами. Если всё зелёное — нажмите «Добавить сайт в поиск». Сайт сразу включится и начнёт участвовать в общем поиске вместе с остальными источниками."),
            HelpBlock.Tip("Обычно создание занимает 20–60 секунд. Экран можно не закрывать — прогресс показывается по шагам."),
        ),
    ),
    HelpSection(
        "Как выбрать проверочный запрос",
        listOf(
            HelpBlock.Bullets(
                listOf(
                    "Берите слово, по которому на сайте много роликов: популярный жанр, тег, имя — то, что вы сами ввели бы в поиск сайта.",
                    "Лучше одно-два слова. Длинные фразы часто дают пустую выдачу.",
                    "Не используйте слова, которые входят в адрес страниц сайта (например «video», если ссылки выглядят как /videos/…): так Studio хуже отличает настоящий поиск от главной страницы.",
                    "Если результат «Карточки не распознаны» — попробуйте другое слово, иногда этого достаточно.",
                ),
            ),
        ),
    ),
    HelpSection(
        "Что Studio делает автоматически",
        listOf(
            HelpBlock.Steps(
                listOf(
                    "Открывает главную страницу и запоминает настоящий домен сайта (с учётом переадресаций, например на www).",
                    "Ищет поиск, который объявил сам сайт: форму поиска, OpenSearch и разметку schema.org.",
                    "Проверяет около двадцати распространённых вариантов адреса поиска (/search/?q=…, /?s=…, /search/запрос/ и другие).",
                    "На каждой странице результатов находит повторяющиеся ссылки с превью и вычисляет правило карточки видео (link_pattern). Меню, категории, теги и профили отбрасываются.",
                    "Сравнивает выдачу с главной страницей, чтобы не принять каталог за поиск, и учитывает, сколько названий совпадает с запросом.",
                    "Находит постраничную навигацию и проверяет, что вторая страница действительно содержит новые ролики.",
                    "Открывает несколько карточек и через рабочие движки плеера (KVS, JW Player, Video.js, HLS, DASH, iframe и другие) ищет настоящий поток. Короткие превью-ролики не засчитываются.",
                    "Финально проверяет воспроизведение в самом плеере Media3. Если поток спрятан в JavaScript, Studio повторяет проверку через встроенный изолированный браузер.",
                ),
            ),
        ),
    ),
    HelpSection(
        "Что означает результат",
        listOf(
            HelpBlock.Bullets(
                listOf(
                    "«Адаптер готов» (зелёный) — поиск и воспроизведение подтверждены. Можно добавлять.",
                    "«Не удалось собрать рабочий адаптер» (красный) — ниже указана причина. Если адаптер всё же собран, доступна кнопка «Всё равно добавить»: сайт будет искаться, но часть роликов может не открываться.",
                    "«Воспроизведение подтверждено: N» — сколько проверенных карточек дошло до готовности в плеере. Источник считается рабочим, если открывается большинство проверенных роликов.",
                ),
            ),
        ),
    ),
    HelpSection(
        "Если не получилось",
        listOf(
            HelpBlock.Bullets(
                listOf(
                    "«Сайт не открылся» или ошибка 403 — сайт недоступен, требует VPN или защищён от ботов (Cloudflare и т. п.). Проверьте, открывается ли он в браузере на телефоне.",
                    "«Ни один вариант поиска не открылся» — у сайта нестандартный поиск. Откройте поиск на сайте в браузере, скопируйте адрес страницы результатов и укажите его в расширенном режиме (поле url_template, запрос замените на {query}).",
                    "«Карточки видео не распознаны» — попробуйте другой запрос. Если не помогает — включите расширенный режим и нажмите «Создать адаптер»: там дополнительно работает анализ через встроенный браузер и AI-помощник.",
                    "Поиск работает, а видео не открывается — сайт отдаёт поток только после выполнения JavaScript или защищает его DRM. Первое Studio обходит встроенным браузером, DRM-видео воспроизвести нельзя.",
                    "Сайт работал, а потом перестал — сайт сменил дизайн. Удалите адаптер и создайте заново, это занимает минуту.",
                ),
            ),
        ),
    ),
    HelpSection(
        "Расширенный режим",
        listOf(
            HelpBlock.P("Внизу экрана Studio есть «Расширенный режим». Он нужен, если автоматическое создание не справилось или вы хотите поправить адаптер вручную."),
            HelpBlock.Bullets(
                listOf(
                    "«Без AI / Kilo Code / Cline» — выбор помощника. С AI нужно войти в аккаунт и выбрать модель; AI получает уже проверенные приложением факты и исправляет только то, что мешает работе.",
                    "«Локальный черновик» — быстрый анализ без проверки.",
                    "«Создать адаптер» — прежний многоступенчатый конвейер: HTTP-анализ, расширенный каталог маршрутов, встроенный браузер и при необходимости AI.",
                    "Поле adapter.json можно редактировать прямо здесь. «Live test» проверяет поиск и воспроизведение, «Сохранить custom SNP» сохраняет адаптер выключенным для ручной проверки.",
                ),
            ),
        ),
    ),
    HelpSection(
        "Справочник: поля адаптера",
        listOf(
            HelpBlock.P("Адаптер, созданный автоматически, использует движок auto_catalog. Главные поля:"),
            HelpBlock.Code(
                """
                "engine": "auto_catalog",
                "search": {
                  "url_template": "/search/?q={query}",
                  "page_template": "/search/{query}/{page}/",
                  "link_pattern": "^/videos/\\d+/[^/]+/?$"
                },
                "permissions": {
                  "allowed_hosts": ["example.com", "*.example.com", "cdn.example.net"],
                  "allow_javascript": false,
                  "allow_cookies": true
                }
                """.trimIndent(),
            ),
            HelpBlock.Bullets(
                listOf(
                    "url_template — адрес первой страницы поиска. {query} заменяется запросом, {query_plus} — запросом с «+» вместо пробелов.",
                    "page_template — адрес следующих страниц. {page} — номер страницы, начиная с 1 (а {page0} — с 0). Если поля нет, догружается только первая страница.",
                    "link_pattern — регулярное выражение для пути ссылки на ролик. \\d+ — число, [^/]+ — любое слово до «/».",
                    "allowed_hosts — домены, к которым разрешено обращаться: сайт, его поддомены и CDN с видео. Запросы к другим доменам блокируются.",
                    "allow_javascript — разрешить встроенный браузер для сайтов, где поток появляется только после JavaScript.",
                    "health.query — запрос, которым приложение проверяет адаптер в «Проверить все».",
                ),
            ),
        ),
    ),
    HelpSection(
        "Управление источниками",
        listOf(
            HelpBlock.Bullets(
                listOf(
                    "Переключатель в карточке источника включает и выключает сайт в общем поиске.",
                    "Значок «Проверить все» в списке источников по очереди проверяет поиск и воспроизведение каждого включённого сайта.",
                    "Метки «Сайт» и «Поиск и видео» показывают результат последней проверки: «работает», «ошибка» или «не проверено».",
                    "Меню «⋮» в карточке: подробности, отдельные проверки, история версий и откат, экспорт в ZIP и удаление своего адаптера.",
                    "Кнопка «+» вверху импортирует адаптер из ZIP — так можно передать адаптер на другое устройство.",
                ),
            ),
        ),
    ),
    HelpSection(
        "Безопасность",
        listOf(
            HelpBlock.Bullets(
                listOf(
                    "Адаптер — только описание; он не может выполнить код на телефоне.",
                    "Приложение обращается только к доменам из allowed_hosts и к публичным HTTPS-адресам, найденным при разборе плеера; локальная сеть и HTTP запрещены.",
                    "Встроенный браузер изолирован: без доступа к файлам, без всплывающих окон, cookie очищаются перед каждой загрузкой.",
                    "Видео с DRM-защитой приложение не воспроизводит и не обходит.",
                ),
            ),
        ),
    ),
)

@Composable
internal fun StudioHelpDialog(onDismiss: () -> Unit) {
    var expanded by remember { mutableStateOf(setOf(0, 1)) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад") }
                    Column {
                        Text("Справка SNP Studio", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Как создавать и проверять адаптеры", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(helpSections.size) { index ->
                        val section = helpSections[index]
                        val open = index in expanded
                        ElevatedCard(
                            onClick = { expanded = if (open) expanded - index else expanded + index },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${index + 1}. ${section.title}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                    Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                                }
                                if (open) section.blocks.forEach { HelpBlockView(it) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HelpBlockView(block: HelpBlock) {
    val body = MaterialTheme.typography.bodyMedium
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (block) {
        is HelpBlock.P -> Text(block.text, style = body, color = muted)
        is HelpBlock.Steps -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            block.items.forEachIndexed { i, item ->
                Row {
                    Text("${i + 1}.", Modifier.width(22.dp), style = body, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(item, style = body, color = muted)
                }
            }
        }
        is HelpBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            block.items.forEach { item ->
                Row {
                    Text("•", Modifier.width(16.dp), style = body, color = MaterialTheme.colorScheme.secondary)
                    Text(item, style = body, color = muted)
                }
            }
        }
        is HelpBlock.Code -> Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
            Text(block.text, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        is HelpBlock.Tip -> Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(10.dp)) {
                Text("💡", style = body)
                Spacer(Modifier.width(8.dp))
                Text(block.text, style = body, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
    Spacer(Modifier.height(0.dp))
}
