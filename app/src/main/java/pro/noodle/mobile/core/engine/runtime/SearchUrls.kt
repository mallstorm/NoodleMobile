package pro.noodle.mobile.core.engine.runtime

import pro.noodle.mobile.core.adapter.AdapterDefinition

/**
 * Single source of truth for building search URLs.
 *
 * Paging convention: page = -1 is the first page (as SearchViewModel requests it), page = N >= 1 is
 * the (N+1)-th page. `{page}` placeholders receive the human page number (first page = 1).
 *
 * A template written into adapter.json (usually confirmed live by Studio) always wins over the
 * engine's built-in convention; a template without `{page}` is never reused for later pages so that
 * infinite scroll does not silently re-download page one.
 */
object SearchUrls {
    fun candidates(engine: SourceEngine, request: EngineSearchRequest): List<String> {
        val out = linkedSetOf<String>()
        val firstPage = request.page < 0
        if (!firstPage && request.searchPageTemplate.isNotBlank()) out += render(request.searchPageTemplate, request)
        if (request.searchUrlTemplate.isNotBlank() && (firstPage || request.searchUrlTemplate.contains("{page}", true))) {
            out += render(request.searchUrlTemplate, request)
        }
        engine.buildSearchUrl(request).takeIf { it.isNotBlank() }?.let { url ->
            val staleFirstPage = !firstPage && request.searchUrlTemplate.isNotBlank() &&
                !request.searchUrlTemplate.contains("{page}", true) &&
                url.equals(render(request.searchUrlTemplate, request), true)
            if (!staleFirstPage) out += url
        }
        return out.filter { it.isNotBlank() }
    }

    fun primary(engine: SourceEngine, request: EngineSearchRequest): String = candidates(engine, request).firstOrNull().orEmpty()

    fun render(template: String, request: EngineSearchRequest): String {
        val page = (request.page + 1).coerceAtLeast(1)
        val raw = template
            .replace("{base_url}", request.baseUrl.trimEnd('/'), true)
            .replace("{query}", EngineParsing.encodedQuery(request.query), true)
            .replace("{query_plus}", EngineParsing.encodedQuery(request.query).replace("%20", "+"), true)
            .replace("{page}", page.toString(), true)
            .replace("{page0}", (page - 1).toString(), true)
        return EngineParsing.absolute(raw, request.baseUrl)
    }
}

fun AdapterDefinition.searchRequest(query: String, page: Int = -1, maxItems: Int = 40, baseUrlOverride: String = baseUrl) = EngineSearchRequest(
    baseUrl = baseUrlOverride,
    query = query,
    page = page,
    maxItems = maxItems,
    searchUrlTemplate = searchUrlTemplate,
    searchPageTemplate = searchPageTemplate,
    linkPattern = linkPattern,
    itemSelector = itemSelector,
    titleSelector = titleSelector,
    urlSelector = urlSelector,
    thumbnailSelector = thumbnailSelector,
    thumbnailAttribute = thumbnailAttribute,
    durationSelector = durationSelector,
    viewsSelector = viewsSelector,
    itemsPath = itemsPath,
    idPath = idPath,
    titlePath = titlePath,
    urlPath = urlPath,
    thumbnailPath = thumbnailPath,
    durationPath = durationPath,
    viewsPath = viewsPath,
)
