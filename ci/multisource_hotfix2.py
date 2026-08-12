#!/usr/bin/env python3
from pathlib import Path
import hashlib, json, re, sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "work/NoodleMobile_0.10.0-alpha10_SOURCE")
if not root.is_dir():
    raise SystemExit(f"project root missing: {root}")

# Live-probed source migrations/CDN hosts. Keep trust source-scoped; do not add ad/VAST hosts.
EXTRA_HOSTS = {
    "me.fapaxi.pro": ["fapax.me", "*.fapax.me"],
    "noodlemagazine.com": ["i.vimeocdn.com"],
    "video-laba.click": ["video-laba.link", "*.video-laba.link", "24fastload.net", "*.24fastload.net"],
    "x.fap-guru.pro": ["fap-guru.name", "*.fap-guru.name"],
}

bundled = root / "app/src/main/assets/adapters/bundled"
for adapter_id, extra in EXTRA_HOSTS.items():
    d = bundled / adapter_id
    ap = d / "adapter.json"
    mp = d / "snp-package.json"
    data = json.loads(ap.read_text(encoding="utf-8-sig"))
    perms = data.setdefault("permissions", {})
    hosts = [str(v).strip().lower() for v in perms.get("allowed_hosts", []) if str(v).strip()]
    for h in extra:
        if h not in hosts:
            hosts.append(h)
    perms["allowed_hosts"] = hosts
    ap.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    raw = ap.read_bytes()
    manifest = json.loads(mp.read_text(encoding="utf-8-sig"))
    for item in manifest.get("files", []):
        if item.get("path") == "adapter.json":
            item["sha256"] = hashlib.sha256(raw).hexdigest()
            item["size"] = len(raw)
            break
    else:
        raise SystemExit(f"adapter.json missing from package manifest: {adapter_id}")
    mp.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

# KVS mixed-layout fix: real /video/* cards must not be hidden by /videos/* navigation cards.
kvs = root / "app/src/main/java/pro/noodle/mobile/core/engine/impl/KvsSourceEngine.kt"
text = kvs.read_text(encoding="utf-8")
lines = text.splitlines()
idx = next((i for i, line in enumerate(lines) if line.strip().startswith("var matches = Regex(")), -1)
if idx < 0 or idx + 1 >= len(lines) or "if (matches.isEmpty()) matches = Regex(" not in lines[idx + 1]:
    raise SystemExit("KvsSourceEngine mixed-layout anchor missing")
lines[idx:idx+2] = [
    '        val singleVideoMatches = Regex("<a[^>]*href=([\\\"\'])(/video/[^\\\"\']+)\\\\1[^>]*>([\\\\s\\\\S]*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(html).toList()',
    '        val pluralVideoMatches = Regex("<a[^>]*href=([\\\"\'])(/videos/[^\\\"\']+)\\\\1[^>]*class=([\\\"\'])[^\\\"\']*\\\\bitem\\\\b[^\\\"\']*\\\\3[^>]*>([\\\\s\\\\S]*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(html).toList()',
    '        val matches = singleVideoMatches + pluralVideoMatches',
]
text = "\n".join(lines) + "\n"
text = text.replace(
    '"/videos/feed", "/videos/latest", "/videos/top", "/videos/new", "/videos/popular", "/videos/recent", "/videos/categories", "/videos/tags"',
    '"/videos/feed", "/videos/trending", "/videos/latest", "/videos/top", "/videos/new", "/videos/popular", "/videos/recent", "/videos/categories", "/videos/tags"',
    1,
)
kvs.write_text(text, encoding="utf-8")

# Video-laba live endpoint migrated to /search/?q=... .
legacy = root / "app/src/main/java/pro/noodle/mobile/core/engine/impl/LegacySearchEngines.kt"
text = legacy.read_text(encoding="utf-8")
old = '    override fun buildSearchUrl(request:EngineSearchRequest):String{val b=request.baseUrl.trimEnd(\'/\');if(b.isBlank()||request.query.isBlank())return "";val q=EngineParsing.encodedQuery(request.query);return if(request.page<0)"$b/$q/" else "$b/$q/${(request.page+1).coerceAtLeast(2)}/"}'
new = '    override fun buildSearchUrl(request:EngineSearchRequest):String{val b=request.baseUrl.trimEnd(\'/\');if(b.isBlank()||request.query.isBlank())return "";val q=EngineParsing.encodedQuery(request.query);return if(request.page<0)"$b/search/?q=$q" else "$b/search/?q=$q&page=${(request.page+1).coerceAtLeast(2)}"}'
if old not in text:
    raise SystemExit("NovideoEmbedEngine search URL anchor missing")
legacy.write_text(text.replace(old, new, 1), encoding="utf-8")

# Search resilience: try canonical + common endpoints and parse relative links against final origin.
repo = root / "app/src/main/java/pro/noodle/mobile/core/search/BundledSearchRepository.kt"
text = repo.read_text(encoding="utf-8")
start = text.index('    private suspend fun searchOne(')
end = text.index('    private fun templateSearchUrl', start)
replacement = r'''    private suspend fun searchOne(a:AdapterDefinition,q:String,page:Int,max:Int):Pair<List<VideoItem>,SourceSearchError?>{
        val engine=RuntimeEngineRegistry.find(a.engine) ?: return emptyList<VideoItem>() to SourceSearchError(a.id,a.name,"ENGINE_NOT_IMPLEMENTED","Runtime engine ${a.engine} отсутствует.")
        val req=EngineSearchRequest(baseUrl=a.baseUrl,query=q,page=page,maxItems=max,searchUrlTemplate=a.searchUrlTemplate,itemSelector=a.itemSelector,titleSelector=a.titleSelector,urlSelector=a.urlSelector,thumbnailSelector=a.thumbnailSelector,thumbnailAttribute=a.thumbnailAttribute,durationSelector=a.durationSelector,viewsSelector=a.viewsSelector,itemsPath=a.itemsPath,idPath=a.idPath,titlePath=a.titlePath,urlPath=a.urlPath,thumbnailPath=a.thumbnailPath,durationPath=a.durationPath,viewsPath=a.viewsPath)
        val candidates=searchCandidates(engine.buildSearchUrl(req),templateSearchUrl(req),a.baseUrl,q,page)
        if(candidates.isEmpty())return emptyList<VideoItem>() to SourceSearchError(a.id,a.name,"SEARCH_URL_EMPTY","Engine не сформировал search URL.")
        var lastError:SourceSearchError?=null
        var hadSuccessfulResponse=false
        for(url in candidates){
            val net=http.fetchText(url,NetworkPolicy(a.allowedHosts,allowCookies=a.allowCookies),a.id)
            if(!net.success){lastError=SourceSearchError(a.id,a.name,net.errorCode,net.errorMessage);continue}
            hadSuccessfulResponse=true
            val finalBase=originOf(net.finalUrl,a.baseUrl)
            val parseReq=req.copy(baseUrl=finalBase)
            val parsed=engine.parseSearch(net.text,parseReq)
            val items=parsed.mapNotNull { p ->
                val watch=normalizeAllowed(p.watchPath,a,baseUrl=finalBase) ?: return@mapNotNull null
                val thumb=normalizeAllowed(p.thumbnailUrl,a,allowBlank=true,baseUrl=finalBase).orEmpty()
                VideoItem(id="${a.id}|${p.remoteId.ifBlank{watch}}",remoteId=p.remoteId,watchUrl=watch,title=p.title.ifBlank{p.remoteId.ifBlank{"Без названия"}},thumbnailUrl=thumb,duration=p.durationText,views=p.viewsText,sourceName=a.name,sourceBaseUrl=a.baseUrl,sourceEngine=a.engine,playerPageUrl=watch,favoriteKey="${a.id}|${p.remoteId.ifBlank{watch}}")
            }.distinctBy{it.watchUrl.lowercase()}
            if(items.isNotEmpty())return items to null
        }
        if(hadSuccessfulResponse)return emptyList<VideoItem>() to SourceSearchError(a.id,a.name,"SEARCH_PARSED_ZERO","Источник ответил, но текущий SNP не распознал ни одной видеокарточки.")
        return emptyList<VideoItem>() to (lastError ?: SourceSearchError(a.id,a.name,"SEARCH_FAILED","Не удалось получить результаты поиска."))
    }

    private fun searchCandidates(primary:String,template:String,baseUrl:String,query:String,page:Int):List<String>{
        val b=baseUrl.trimEnd('/');val q=pro.noodle.mobile.core.engine.runtime.EngineParsing.encodedQuery(query);val out=linkedSetOf<String>()
        listOf(primary,template).filter{it.isNotBlank()}.forEach{out+=it}
        if(b.isNotBlank()&&q.isNotBlank()){
            if(page<0){out+="$b/search/?q=$q";out+="$b/search/$q/"}
            else{val p=(page+1).coerceAtLeast(2);out+="$b/search/$p/?q=$q";out+="$b/search/?q=$q&page=$p"}
        }
        return out.take(5)
    }

    private fun originOf(url:String,fallback:String):String=runCatching{val u=URI(url);if(u.scheme.isNullOrBlank()||u.host.isNullOrBlank())fallback else "${u.scheme}://${u.host}${if(u.port>=0)":${u.port}" else ""}"}.getOrDefault(fallback)

'''
text = text[:start] + replacement + text[end:]
text = text.replace(
    '    private fun normalizeAllowed(raw:String,a:AdapterDefinition,allowBlank:Boolean=false):String?{if(raw.isBlank())return if(allowBlank)"" else null;val url=pro.noodle.mobile.core.engine.runtime.EngineParsing.absolute(raw,a.baseUrl);',
    '    private fun normalizeAllowed(raw:String,a:AdapterDefinition,allowBlank:Boolean=false,baseUrl:String=a.baseUrl):String?{if(raw.isBlank())return if(allowBlank)"" else null;val url=pro.noodle.mobile.core.engine.runtime.EngineParsing.absolute(raw,baseUrl);',
    1,
)
repo.write_text(text, encoding="utf-8")

# Security-preserving thumbnail fetch: same SNP policy + SafeDns, with image Accept and source referer.
http = root / "app/src/main/java/pro/noodle/mobile/core/network/SecureHttpClient.kt"
text = http.read_text(encoding="utf-8")
old_sig = '    suspend fun fetchBytes(url: String, policy: NetworkPolicy, sourceKey: String = "default"): BinaryFetchResult = withContext(Dispatchers.IO) {'
new_sig = '    suspend fun fetchBytes(url: String, policy: NetworkPolicy, sourceKey: String = "default", accept: String = "application/zip,application/json,application/octet-stream,*/*;q=0.4", referer: String = ""): BinaryFetchResult = withContext(Dispatchers.IO) {'
if old_sig not in text:
    raise SystemExit("SecureHttpClient.fetchBytes signature anchor missing")
text = text.replace(old_sig, new_sig, 1)
old_req = '            val request = Request.Builder().url(current).header("User-Agent", BuildIdentity.USER_AGENT).header("Accept", "application/zip,application/json,application/octet-stream,*/*;q=0.4").build()'
new_req = '            val requestBuilder = Request.Builder().url(current).header("User-Agent", BuildIdentity.USER_AGENT).header("Accept", accept)\n            if (referer.isNotBlank()) requestBuilder.header("Referer", referer)\n            val request = requestBuilder.build()'
if old_req not in text:
    raise SystemExit("SecureHttpClient.fetchBytes request anchor missing")
text = text.replace(old_req, new_req, 1)
http.write_text(text, encoding="utf-8")

# ViewModel image cache/loader.
vm = root / "app/src/main/java/pro/noodle/mobile/ui/search/SearchViewModel.kt"
text = vm.read_text(encoding="utf-8")
text = text.replace('import android.app.Application\n', 'import android.app.Application\nimport android.graphics.Bitmap\nimport android.graphics.BitmapFactory\n', 1)
text = text.replace('import kotlinx.coroutines.launch\n', 'import kotlinx.coroutines.launch\nimport java.util.concurrent.ConcurrentHashMap\n', 1)
anchor = '    private var resolveJob: Job? = null\n'
insert = '''    private var resolveJob: Job? = null
    private val thumbnailCache = ConcurrentHashMap<String, Bitmap>()
    private val thumbnailMisses = ConcurrentHashMap.newKeySet<String>()

    suspend fun loadThumbnail(video: VideoItem): Bitmap? {
        val url = video.thumbnailUrl.trim()
        if (url.isBlank()) return null
        thumbnailCache[url]?.let { return it }
        if (url in thumbnailMisses) return null
        val adapter = searchRepo.adapterFor(video) ?: return null
        val result = http.fetchBytes(
            url = url,
            policy = pro.noodle.mobile.core.network.NetworkPolicy(adapter.allowedHosts, allowCookies = adapter.allowCookies, maxResponseBytes = 3 * 1024 * 1024),
            sourceKey = adapter.id,
            accept = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.5",
            referer = adapter.baseUrl,
        )
        if (!result.success || result.bytes.isEmpty()) { thumbnailMisses += url; return null }
        val bitmap = BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size)
        if (bitmap == null) { thumbnailMisses += url; return null }
        thumbnailCache[url] = bitmap
        return bitmap
    }
'''
if anchor not in text:
    raise SystemExit("SearchViewModel resolveJob anchor missing")
text = text.replace(anchor, insert, 1)
vm.write_text(text, encoding="utf-8")

# Search result cards: actual preview, useful metadata, source diagnostics; remove raw watch URL.
main = root / "app/src/main/java/pro/noodle/mobile/MainActivity.kt"
text = main.read_text(encoding="utf-8")
text = text.replace('import androidx.compose.foundation.layout.*\n', 'import androidx.compose.foundation.Image\nimport androidx.compose.foundation.layout.*\n', 1)
text = text.replace('import androidx.compose.ui.Alignment\n', 'import androidx.compose.ui.Alignment\nimport androidx.compose.ui.graphics.asImageBitmap\nimport androidx.compose.ui.layout.ContentScale\n', 1)
old_screen = '''@Composable private fun SearchScreen(vm:SearchViewModel=viewModel()){
    val state by vm.state.collectAsState();state.playback?.let{session->val client=remember(session.sourceKey,session.video.id){vm.streamingClient(session)};PlayerScreen(session,client,vm::closePlayer);return}
    Column(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){OutlinedTextField(state.query,vm::setQuery,Modifier.weight(1f),placeholder={Text("Поиск по включённым источникам")},leadingIcon={Icon(Icons.Outlined.Search,null)},singleLine=true);Button(onClick=vm::search,enabled=!state.loading&&state.query.isNotBlank()){Text("Найти")}}
        if(state.loading||state.resolving)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(state.message.isNotBlank())Text(state.message,color=if(state.message.contains(":"))MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if(state.errors.isNotEmpty())Text("Недоступно источников: ${state.errors.size}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(8.dp)){items(state.items,key={it.id}){video->ElevatedCard(onClick={vm.open(video)},modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(video.title,fontWeight=FontWeight.SemiBold,maxLines=2);Text("${video.sourceName} • ${video.duration.ifBlank{"—"}} • ${video.views.ifBlank{"—"}}",style=MaterialTheme.typography.bodySmall);Text(video.watchUrl,style=MaterialTheme.typography.labelSmall,maxLines=1,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}}
    }
}
'''
new_screen = '''@Composable private fun SearchScreen(vm:SearchViewModel=viewModel()){
    val state by vm.state.collectAsState();state.playback?.let{session->val client=remember(session.sourceKey,session.video.id){vm.streamingClient(session)};PlayerScreen(session,client,vm::closePlayer);return}
    Column(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){OutlinedTextField(state.query,vm::setQuery,Modifier.weight(1f),placeholder={Text("Поиск по включённым источникам")},leadingIcon={Icon(Icons.Outlined.Search,null)},singleLine=true);Button(onClick=vm::search,enabled=!state.loading&&state.query.isNotBlank()){Text("Найти")}}
        if(state.loading||state.resolving)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(state.message.isNotBlank())Text(state.message,color=if(state.message.contains(":"))MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if(state.errors.isNotEmpty()){
            Text("Проблемы источников: ${state.errors.size}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            state.errors.take(3).forEach{e->Text("${e.sourceName}: ${e.errorCode}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)}
        }
        LazyColumn(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(8.dp)){items(state.items,key={it.id}){video->
            ElevatedCard(onClick={vm.open(video)},modifier=Modifier.fillMaxWidth()){
                Row(Modifier.padding(10.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically){
                    SearchThumbnail(video,vm)
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)){
                        Text(video.title,fontWeight=FontWeight.SemiBold,maxLines=2)
                        Text(video.sourceName,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                        Text(listOf(video.duration.takeIf{it.isNotBlank()},video.views.takeIf{it.isNotBlank()}).filterNotNull().joinToString(" • ").ifBlank{"Видео"},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.PlayCircle,null)
                }
            }
        }}
    }
}

@Composable private fun SearchThumbnail(video:pro.noodle.mobile.core.model.VideoItem,vm:SearchViewModel){
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue=null,key1=video.thumbnailUrl){value=vm.loadThumbnail(video)}
    Surface(modifier=Modifier.size(width=132.dp,height=82.dp),shape=MaterialTheme.shapes.medium,tonalElevation=2.dp){
        if(bitmap!=null)Image(bitmap!!.asImageBitmap(),contentDescription=null,modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
        else Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Icon(Icons.Outlined.PlayCircle,null,modifier=Modifier.size(34.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}
'''
if old_screen not in text:
    raise SystemExit("SearchScreen anchor missing")
main.write_text(text.replace(old_screen, new_screen, 1), encoding="utf-8")

# Visible build marker.
identity = root / "app/src/main/java/pro/noodle/mobile/core/version/BuildIdentity.kt"
text = identity.read_text(encoding="utf-8")
text = re.sub(r'const val STAGE_LABEL = "[^"]*"', 'const val STAGE_LABEL = "0.10 alpha • Multi-source Hotfix 2"', text, count=1)
identity.write_text(text, encoding="utf-8")

# Regression tests for live parser/search migrations.
test_dir = root / "app/src/test/java/pro/noodle/mobile"
test_dir.mkdir(parents=True, exist_ok=True)
(test_dir / "MultiSourceHotfix2Test.kt").write_text(r'''package pro.noodle.mobile

import org.junit.Assert.*
import org.junit.Test
import pro.noodle.mobile.core.engine.impl.KvsSourceEngine
import pro.noodle.mobile.core.engine.impl.NovideoEmbedEngine
import pro.noodle.mobile.core.engine.runtime.EngineSearchRequest

class MultiSourceHotfix2Test {
    @Test fun kvsMixedLayoutDoesNotLetCatalogLinksHideRealVideos(){
        val html="""<a href='/videos/feed' class='item'>feed</a><a href='/video/real-video-123'><img src='/thumb.jpg' alt='Real'></a>"""
        val items=KvsSourceEngine("kvs_videos", emptySet(), KvsSourceEngine.Mode.VIDEOS).parseSearch(html,EngineSearchRequest("https://example.com","q"))
        assertEquals(1,items.size);assertEquals("/video/real-video-123",items[0].watchPath)
    }
    @Test fun kvsViewStillParsesClassicView(){
        val html="""<ul id='list_videos_common_items'><a href='/view/42/'><img src='/42.jpg' alt='Classic'></a></ul>"""
        val items=KvsSourceEngine("kvs_view", emptySet(), KvsSourceEngine.Mode.VIEW).parseSearch(html,EngineSearchRequest("https://example.com","q"))
        assertEquals(1,items.size);assertEquals("/view/42/",items[0].watchPath)
    }
    @Test fun novideoUsesCurrentSearchEndpoint(){
        val url=NovideoEmbedEngine().buildSearchUrl(EngineSearchRequest("https://video.example","demo"))
        assertEquals("https://video.example/search/?q=demo",url)
    }
}
''', encoding="utf-8")

print("multisource hotfix 2 applied: live endpoints, mixed KVS parsing, source-scoped hosts, secure thumbnails, UI cards, regression tests")
