package pro.noodle.mobile

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import pro.noodle.mobile.core.adapter.AdapterValidator
import pro.noodle.mobile.core.engine.runtime.RuntimeEngineRegistry
import pro.noodle.mobile.core.engine.runtime.SearchUrls
import pro.noodle.mobile.core.engine.runtime.searchRequest
import pro.noodle.mobile.core.network.NetworkPolicy
import pro.noodle.mobile.core.network.SecureHttpClient
import pro.noodle.mobile.core.resolver.ResolverFetcher
import pro.noodle.mobile.core.resolver.ResolverPipeline
import pro.noodle.mobile.core.studio.auto.SnpAutoBuilder
import java.io.File

/**
 * Real-network probe, skipped unless NOODLE_LIVE_STUDIO=1 (CI "live" job only).
 * 1) Checks every bundled SNP: search → cards → static resolver.
 * 2) Runs the SNP Studio auto-builder against the same sites (plus NOODLE_LIVE_SITES) as a user would.
 */
class LiveStudioProbeTest {
    private val http = SecureHttpClient()

    @Test
    fun liveProbe() = runBlocking {
        assumeTrue(System.getenv("NOODLE_LIVE_STUDIO") == "1")
        val out = File(System.getenv("NOODLE_LIVE_OUT") ?: "build/live-studio").apply { mkdirs() }
        val report = StringBuilder()
        val bundledDir = listOf(File("src/main/assets/adapters/bundled"), File("app/src/main/assets/adapters/bundled")).first { it.isDirectory }
        val hosts = mutableListOf<String>()

        report.appendLine("=== BUNDLED SNP LIVE CHECK ===")
        bundledDir.listFiles().orEmpty().filter { File(it, "adapter.json").isFile }.sortedBy { it.name }.forEach { dir ->
            val v = AdapterValidator.validate(File(dir, "adapter.json").readText())
            val d = v.definition
            if (!v.isValid || d == null) { report.appendLine("${dir.name}: INVALID ${v.errorCode} ${v.errorMessage}"); return@forEach }
            hosts += d.baseUrl
            val engine = RuntimeEngineRegistry.find(d.engine)!!
            val req = d.searchRequest(d.healthQuery.ifBlank { "video" }, -1, 30)
            var line = "${d.id} [${d.engine}]"
            var items = emptyList<pro.noodle.mobile.core.engine.runtime.EngineSearchItem>()
            for (url in SearchUrls.candidates(engine, req)) {
                val net = http.fetchText(url, NetworkPolicy(d.allowedHosts, allowCookies = true), "live:${d.id}")
                line += "\n   search ${net.statusCode} ${if (net.success) "ok" else net.errorCode} $url -> ${net.finalUrl}"
                if (!net.success) continue
                items = engine.parseSearch(net.text, req)
                line += " cards=${items.size}"
                if (items.isNotEmpty()) break
            }
            items.take(2).forEach { item ->
                val page = pro.noodle.mobile.core.engine.runtime.EngineParsing.absolute(item.watchPath, d.baseUrl)
                val net = http.fetchText(page, NetworkPolicy(emptyList(), allowCookies = true), "live:${d.id}")
                val resolved = if (net.success) ResolverPipeline.resolve(net.text, net.finalUrl.ifBlank { page }, d.engine, d.viewStrategies, ResolverFetcher { u ->
                    http.fetchTextBlocking(u, NetworkPolicy(emptyList(), allowCookies = true), "live:${d.id}").takeIf { it.success }?.text
                }) else null
                line += "\n   view ${net.statusCode} $page -> sources=${resolved?.sources?.size ?: 0} ${resolved?.sources?.firstOrNull()?.let { "${it.protocol} ${it.url.take(90)}" } ?: resolved?.errorCode.orEmpty()}"
                if (resolved != null && net.success) {
                    // Real stream or a 5-second preview? Measure like the app does at playback time.
                    val sel = withTimeoutOrNull(40_000) {
                        pro.noodle.mobile.core.resolver.StreamSelector(http.streamingClient(NetworkPolicy(emptyList(), allowCookies = true), "live:${d.id}"), resolved.playerPageUrl.ifBlank { page })
                            .select(resolved.sources, pro.noodle.mobile.core.resolver.DurationText.seconds(item.durationText), pro.noodle.mobile.core.resolver.StreamHints(d.streamPrefer, d.streamReject, d.streamMinSeconds, d.streamExtract), listOf(page to net.text))
                    }
                    line += "\n   stream: ${sel?.summary ?: "TIMEOUT"} (expected card duration=${item.durationText})"
                    sel?.ranked?.take(6)?.forEach { r -> line += "\n      ${r.verdict} ${r.source.url.take(100)} — ${r.facts?.brief().orEmpty()}" }
                }
            }
            report.appendLine(line)
        }

        val extra = System.getenv("NOODLE_LIVE_SITES").orEmpty().split(',', ' ', '\n').map { it.trim() }.filter { it.isNotBlank() }
        val query = System.getenv("NOODLE_LIVE_QUERY").orEmpty().ifBlank { "video" }
        val builder = SnpAutoBuilder(http)
        report.appendLine("\n=== SNP STUDIO AUTO-BUILD (query=\"$query\") ===")
        val extraSites = extra.map { it.substringBefore('|') }
        val perSiteQuery = extra.filter { '|' in it }.associate { it.substringBefore('|') to it.substringAfter('|') }
        (hosts + extraSites).distinct().forEach { site ->
            val siteQuery = perSiteQuery[site] ?: query
            val r = withTimeoutOrNull(150_000) { builder.build(site, siteQuery) }
            if (r == null) { report.appendLine("$site: TIMEOUT"); return@forEach }
            report.appendLine("\n--- $site ---")
            report.append(r.report())
            val slug = site.replace(Regex("[^A-Za-z0-9.]"), "_")
            if (r.adapterJson.isNotBlank()) File(out, "$slug.adapter.json").writeText(r.adapterJson)
            if (site in extraSites) {
                // Raw HTML of the pages Studio looked at, for offline diagnosis.
                val dumps = linkedMapOf("home" to (SnpAutoBuilder.normalizeInput(site) ?: site))
                r.candidates.filter { it.loaded }.sortedByDescending { it.score }.take(4).forEachIndexed { i, c -> dumps["search$i"] = c.url }
                r.samples.take(2).forEachIndexed { i, it -> dumps["view$i"] = it.watchPath }
                System.getenv("NOODLE_LIVE_URLS").orEmpty().split(' ').filter { it.startsWith("http") }.forEachIndexed { i, u -> dumps["extra$i"] = u }
                dumps.forEach { (name, url) ->
                    val net = http.fetchText(url, NetworkPolicy(emptyList(), allowCookies = true, maxResponseBytes = 6 * 1024 * 1024), "live-dump")
                    File(out, "$slug.$name.html").writeText("<!-- ${net.statusCode} ${net.finalUrl} -->\n" + net.text)
                }
            }
        }
        File(out, "REPORT.txt").writeText(report.toString())
        println(report)
    }
}
