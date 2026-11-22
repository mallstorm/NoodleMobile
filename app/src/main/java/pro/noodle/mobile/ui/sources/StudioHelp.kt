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
        "Пример адаптера",
        listOf(
            HelpBlock.P("Адаптер, созданный автоматически, использует движок auto_catalog. Ниже — сокращённый пример; расшифровка каждого пункта — в следующих разделах."),
            HelpBlock.Code(
                """
                {
                  "version": 3,
                  "id": "example.com",
                  "name": "Example",
                  "base_url": "https://example.com",
                  "engine": "auto_catalog",
                  "enabled": true,
                  "search": {
                    "url_template": "/search/?q={query}",
                    "page_template": "/search/{query}/{page}/",
                    "link_pattern": "^/videos/\\d+/[^/]+/?$"
                  },
                  "view": {
                    "strategies": ["generic_html5", "hls_manifest"],
                    "stream": { "prefer": "", "reject": "preview", "min_seconds": 60, "extract": [] }
                  },
                  "permissions": {
                    "allowed_hosts": ["example.com", "*.example.com", "cdn.example.net"],
                    "allow_javascript": false,
                    "allow_cookies": true
                  },
                  "health": { "query": "mamki", "min_results": 1, "require_playback": true }
                }
                """.trimIndent(),
            ),
        ),
    ),
    HelpSection(
        "Пункты адаптера: основные",
        listOf(
            HelpBlock.Bullets(
                listOf(
                    "version — версия формата adapter.json (поддерживаются 1, 2 и 3). Studio создаёт 3.",
                    "id — уникальное имя адаптера из латиницы, цифр, точки и дефиса (обычно домен). По id хранятся результаты проверок, правила «не то видео», журнал, избранное и история. Если создать адаптер с чужим id, он заменит тот адаптер.",
                    "name — название источника, которое видно в списке источников, на карточках и в фильтрах поиска.",
                    "adapter_version — версия самого адаптера (например 1.0.2). Нужна для обновлений и отката: новая версия заменяет старую, старая остаётся в истории.",
                    "publisher и homepage — кто создал адаптер и адрес сайта. Только для информации.",
                    "base_url — главный адрес сайта (https://…). От него считаются относительные ссылки, он же отправляется как Referer при загрузке обложек и видео. Если сайт переехал на другое зеркало, меняйте именно его.",
                    "engine — «движок»: код приложения, который умеет читать этот тип сайтов. auto_catalog — универсальный (подходит большинству); kvs_videos, noodle_watch_playlist, novideo_embed — специализированные для типовых скриптов сайтов; declarative_catalog и json_api_catalog читают страницы по селекторам или JSON.",
                    "engine_dependencies — какие плеерные движки может подключать адаптер при воспроизведении (jwplayer_config, videojs_config, hls_manifest и др.). Список нужен для проверки совместимости.",
                    "enabled — включён ли адаптер при установке. Потом сайт включается и выключается переключателем в списке источников.",
                    "min_app_version / max_app_version — с какой и по какую версию приложения адаптер совместим. Если версия приложения вне диапазона, адаптер помечается несовместимым и не участвует в поиске.",
                ),
            ),
        ),
    ),
    HelpSection(
        "Пункты адаптера: capabilities и permissions",
        listOf(
            HelpBlock.Bullets(
                listOf(
                    "capabilities.search — сайт умеет искать по запросу.",
                    "capabilities.pagination — у выдачи есть следующие страницы (работает «подгрузка при прокрутке»).",
                    "capabilities.direct_url — можно вставить в поиск прямую ссылку на ролик этого сайта, и он откроется.",
                    "capabilities.embedded_playback — видео играет из встроенного плеера (iframe) на странице; приложение само находит поток внутри него.",
                    "permissions.allowed_hosts — белый список доменов: сайт, его поддомены (*.example.com) и CDN с видео. Запросы к другим доменам блокируются при поиске и разборе страницы. Обложки и найденные потоки на публичных HTTPS-хостах (CDN) разрешены отдельно, но локальная сеть и HTTP закрыты всегда.",
                    "permissions.allow_javascript — разрешает встроенный изолированный браузер: нужен, если ссылка на видео появляется только после выполнения JavaScript. Включайте только когда без него видео не находится: так медленнее.",
                    "permissions.allow_cookies — сохранять cookie сайта между запросами. Нужен сайтам, которые без cookie отдают заглушку или блокируют CDN; cookie хранятся отдельно для каждого адаптера.",
                ),
            ),
        ),
    ),
    HelpSection(
        "Пункты адаптера: search (поиск и карточки)",
        listOf(
            HelpBlock.Bullets(
                listOf(
                    "url_template — адрес первой страницы поиска. Подстановки: {query} (запрос в адресе), {query_plus} (пробелы как «+»), {query_slug} (нижний регистр, пробелы как «-», без спецсимволов — для сайтов с поиском вида /search/не-снимая-джинсов/).",
                    "page_template — адрес страниц со второй и далее. {page} — номер страницы с 1, {page0} — с 0. Если пункта нет, догружается только первая страница, а лента упирается в конец раньше.",
                    "link_pattern — регулярное выражение для пути ссылки на ролик. Только такие ссылки считаются карточками видео, поэтому меню, категории и теги в выдачу не попадают. \\d+ — число, [^/]+ — любой отрезок до «/».",
                    "item_selector — CSS-селектор блока одной карточки (для declarative_catalog). title_selector, url_selector — где в блоке название и ссылка.",
                    "thumbnail_selector и thumbnail_attribute — где в карточке обложка и из какого атрибута брать адрес (src, data-src, data-original). Если сайт подгружает картинки лениво, нужен именно data-атрибут.",
                    "duration_selector и views_selector — где длительность и просмотры. Длительность нужна для фильтров «Короткие / Средние / Длинные» и для отсева 5-секундных превью.",
                    "items_path, id_path, title_path, url_path, thumbnail_path, duration_path, views_path — то же самое для сайтов с JSON-API: путь к списку роликов и к полям внутри каждого.",
                ),
            ),
        ),
    ),
    HelpSection(
        "Пункты адаптера: view (как найти настоящее видео)",
        listOf(
            HelpBlock.Bullets(
                listOf(
                    "view.strategies — список приёмов разбора страницы ролика по порядку: novideo_embed, kvs_flashvars, jwplayer_config, videojs_config, generic_html5 (тег video), hls_manifest и dash_manifest (потоки .m3u8 и .mpd), iframe_chain (переход по вложенным плеерам), oembed_provider, open_graph (метатеги). Чем раньше приём в списке, тем раньше он применяется.",
                    "view.stream.prefer — регулярное выражение: потоки, адрес которых подходит, получают приоритет. Например «_hd|/full/» — выбирать полные HD-файлы.",
                    "view.stream.reject — регулярное выражение отбраковки: подходящие адреса считаются «не то видео» (превью, трейлер, реклама). Сюда автоматически попадают правила, которые Studio вывела из замеров, и ваши нажатия «Это превью / не то».",
                    "view.stream.min_seconds — минимальная длительность настоящего видео. Потоки короче считаются превью. Studio ставит значение сама, если на сайте часто бывают 5–20-секундные анонсы.",
                    "view.stream.extract — дополнительные регулярные выражения, которыми вытаскиваются ссылки на видео из текста страницы, когда стандартные приёмы их не видят. Группа в скобках — сама ссылка.",
                ),
            ),
            HelpBlock.Tip("Правила «не то видео», добавленные кнопкой в плеере, хранятся отдельно от adapter.json и действуют вместе с view.stream.reject. Список правил и журнал последних 20 попыток воспроизведения видны в «Источники → ⋮ → Подробности»."),
        ),
    ),
    HelpSection(
        "Пункты адаптера: health, repair, update",
        listOf(
            HelpBlock.Bullets(
                listOf(
                    "health.query — слово, по которому приложение проверяет адаптер («Проверить все», фоновый мониторинг, поиск новых видео). Выбирайте слово, по которому на сайте всегда много роликов. Сайты-каталоги показывают новые ролики именно по этому запросу, поэтому от него зависит лента «Новое».",
                    "health.min_results — сколько карточек минимум должен вернуть поиск, чтобы проверка считалась пройденной.",
                    "health.require_playback — проверять ли ещё и воспроизведение (поиск → открытие ролика → реальный поток). Выключите, если сайт защищает видео от автоматических проверок, но в приложении играет.",
                    "health.auto_repair — разрешить автоматическую попытку починки, когда проверка не проходит.",
                    "repair.enabled / repair.mode — включена ли починка и насколько смело она действует (conservative — только безопасные правки).",
                    "repair.strategies — какие приёмы починки пробовать: пересборка поиска, пересчёт правила карточки, поиск новых CDN и т. п.",
                    "repair.failure_threshold — сколько проверок подряд должно провалиться, прежде чем запускать починку.",
                    "repair.cooldown_minutes — пауза между попытками починки, чтобы не нагружать сайт.",
                    "repair.auto_quarantine — отключать адаптер, если починка не помогла, чтобы он не портил общий поиск.",
                    "update.feed_url — адрес ленты обновлений, откуда можно забирать новые версии адаптера. update.auto_update — ставить их автоматически.",
                ),
            ),
        ),
    ),
    HelpSection(
        "AI-агент: как нейросеть чинит адаптер",
        listOf(
            HelpBlock.P("Кнопка «Разобрать ошибку нейросетью» (в карточке ошибки и в расширенном режиме) запускает агента на подключённой модели Kilo Code или Cline. Писать задание не нужно: приложение само передаёт отчёт, адрес сайта, тестовый запрос и текущий adapter.json."),
            HelpBlock.Bullets(
                listOf(
                    "Модель не умеет открывать сайты сама. Приложение выдаёт ей инструменты и выполняет их вместо неё: fetch_page (скачать страницу, найти текст по regex), list_cards (карточки и шаблон link_pattern), find_streams (найти и измерить потоки mp4/m3u8), search_only (поиск по адаптеру), run_adapter_test (полная проверка: поиск → страницы → потоки → плеер Media3).",
                    "Каждый ответ модели — один JSON-шаг {thought, tool, args}. Приложение выполняет шаг, возвращает результат, модель делает следующий. До 24 шагов и 12 минут.",
                    "Модель действует по инструкции-плейбуку: сначала повторяет проверку при сетевых сбоях, затем проверяет поиск, карточки и обложки, потом потоки (превью или полное видео), меняет минимум полей и снова прогоняет адаптер.",
                    "Итог принимается только если собственный тест приложения вернул healthy. Если нет, модель получает отчёт и продолжает (до 3 попыток). Результат можно установить кнопкой «Добавить сайт в поиск».",
                    "Безопасность: инструменты только читают сайты (приватные адреса заблокированы), страницы считаются недоверенными данными, адаптер — только декларативный JSON, без кода. Полный журнал агента можно скопировать кнопкой «Скопировать подробный отчёт».",
                ),
            ),
        ),
    ),
    HelpSection(
        "Как читать отчёт проверки",
        listOf(
            HelpBlock.Bullets(
                listOf(
                    "«Поиск: карточек N, с обложкой M, с длительностью K» — сколько найдено роликов и у скольких есть картинка и время. Если обложек нет — проверьте thumbnail_selector и allowed_hosts; если время не указано, отсев превью работает хуже.",
                    "«Обложки: загружено X из Y» — реально скачанные картинки. Ноль значит, что адрес картинки неверный или CDN отказал: нужен другой атрибут обложки или Referer.",
                    "«Карточка N/M» — результат по каждому проверенному ролику: поток найден или нет и почему.",
                    "«✔ / ✘ / ?» напротив потока: ✔ — настоящее видео, ✘ превью — короткий анонс, ✘ — недоступен, «?» — не удалось измерить.",
                    "В квадратных скобках указан приём, которым найден поток: pipeline — обычный разбор, mined — найден поиском по тексту страницы.",
                    "STREAM LEARNING — какие правила Studio вывела по замерам нескольких роликов (prefer, reject, min_seconds, extract) и насколько она уверена.",
                    "Плашки «Сайт» и «Поиск и видео» в списке источников — итог последних проверок. Красная строка под ними — причина. Кнопка «Перенастроить автоматически» запускает Studio заново.",
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
                    "Раз в сутки приложение само проверяет включённые сайты. Если сайт два раза подряд не отдаёт поиск или видео, придёт уведомление «… перестал работать» — нажмите «Пересоздать», и Studio сразу соберёт новый адаптер. Новый адаптер заменяет сломанный штатный. Интервал (2–46 часов) и отключение — в «Ещё → Мониторинг».",
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
