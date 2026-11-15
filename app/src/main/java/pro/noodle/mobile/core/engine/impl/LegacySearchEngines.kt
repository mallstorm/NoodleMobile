package pro.noodle.mobile.core.engine.impl

import pro.noodle.mobile.core.engine.runtime.*
import java.util.Base64

class NoodleWatchEngine:SourceEngine{
    override val id="noodle_watch_playlist";override val aliases=setOf("noodle","playlist")
    override fun buildSearchUrl(request:EngineSearchRequest):String{val b=request.baseUrl.trimEnd('/');if(b.isBlank()||request.query.isBlank())return "";var u="$b/video/${EngineParsing.encodedQuery(request.query)}";if(request.page>=0)u+="?p=${request.page}";return u}
    override fun parseSearch(text:String,request:EngineSearchRequest):List<EngineSearchItem>{
        val re=Regex("<a[^>]*href=([\"'])/watch/([^\"']+)\\1[^>]*class=([\"'])[^\"']*item_link[^\"']*\\3[^>]*>([\\s\\S]*?)</a>",setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL))
        return re.findAll(text).map{m->val id=m.groupValues[2];val b=m.groupValues[4];val thumb=EngineParsing.first(b,"data-src=[\"']([^\"']+)[\"']").ifBlank{EngineParsing.first(b,"<img[^>]+src=[\"']([^\"']+)[\"']")};EngineSearchItem(id,"/watch/$id",EngineParsing.stripTags(EngineParsing.first(b,"<div[^>]*class=[\"']title[\"'][^>]*>([\\s\\S]*?)</div>")).ifBlank{EngineParsing.htmlDecode(EngineParsing.first(b,"alt=[\"']([^\"']+)[\"']"))}.ifBlank{id},EngineParsing.absolute(thumb,request.baseUrl),EngineParsing.stripTags(EngineParsing.first(b,"<div[^>]*class=[\"']m_time[\"'][^>]*>([\\s\\S]*?)</div>")),EngineParsing.stripTags(EngineParsing.first(b,"<div[^>]*class=[\"']m_views[\"'][^>]*>([\\s\\S]*?)</div>")))}.take(request.maxItems.coerceAtLeast(1)).toList()
    }
}

class NovideoEmbedEngine:SourceEngine{
    override val id="novideo_embed";override val aliases=setOf("novideo","novideo-embed","iframe_flashvars")
    override fun buildSearchUrl(request: EngineSearchRequest): String {
        val base = request.baseUrl.trimEnd('/')
        val slug = request.query.trim().lowercase().replace(Regex("[\\s_]+"), "-")
        if (base.isBlank() || !slug.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) return ""
        // This CMS serves category pages, not a general /search/?q= endpoint.
        // A non-existing category fails normally with HTTP 404; never reinterpret the homepage.
        return if (request.page < 0) "$base/$slug/"
        else "$base/$slug/${(request.page + 1).coerceAtLeast(2)}/"
    }
    override fun parseSearch(text:String,request:EngineSearchRequest):List<EngineSearchItem>{
        val re=Regex("<a[^>]*href=([\"'])([^\"']*(?:/out/\\?ps=|/novideo/)[^\"']*)\\1[^>]*>([\\s\\S]*?)</a>",setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL))
        return re.findAll(text).mapNotNull{m->val view=EngineParsing.htmlDecode(m.groupValues[2]);var path=Regex("https?://[^/]+(/novideo/[0-9]+-[0-9]+\\.html)",RegexOption.IGNORE_CASE).find(view)?.groupValues?.getOrNull(1).orEmpty();if(path.isBlank())path=Regex("(/novideo/[0-9]+-[0-9]+\\.html)",RegexOption.IGNORE_CASE).find(view)?.groupValues?.getOrNull(1).orEmpty();if(path.isBlank()){val ps=Regex("/out/\\?ps=([^\"'\\s<>]+)",RegexOption.IGNORE_CASE).find(view)?.groupValues?.getOrNull(1).orEmpty();path=decodeOutPs(ps)};if(path.isBlank())path=view;val id=Regex("/novideo/([^/\"?#]+)\\.html",RegexOption.IGNORE_CASE).find(path)?.groupValues?.getOrNull(1).orEmpty();if(id.isBlank())return@mapNotNull null;val b=m.groupValues[3];val thumb=EngineParsing.first(b,"<img[^>]+(?:data-src|src)=[\"']([^\"']+)[\"']");val title=EngineParsing.stripTags(EngineParsing.first(b,"<span[^>]*class=[\"'][^\"']*\\bname\\b[^\"']*[\"'][^>]*>([\\s\\S]*?)</span>")).ifBlank{EngineParsing.htmlDecode(EngineParsing.first(b,"<img[^>]+alt=[\"']([^\"']+)[\"']"))}.ifBlank{id};val dur=EngineParsing.stripTags(EngineParsing.first(b,"<span[^>]*class=[\"'][^\"']*\\btime\\b[^\"']*[\"'][^>]*>([\\s\\S]*?)</span>")).ifBlank {
            EngineParsing.first(b, "<em[^>]*class=[\\\"'][^\\\"']*(?:thumb__sticky|duration)[^\\\"']*[\\\"'][^>]*>([\\s\\S]*?)</em>")
                .let(EngineParsing::stripTags)
        };val views=EngineParsing.stripTags(EngineParsing.first(b,"<span[^>]*class=[\"'][^\"']*(?:views|watched|liked)[^\"']*[\"'][^>]*>([\\s\\S]*?)</span>"));EngineSearchItem(id,path,title,EngineParsing.absolute(thumb,request.baseUrl),dur,views)}.take(request.maxItems.coerceAtLeast(1)).toList()
    }
    private fun decodeOutPs(ps:String):String{if(ps.isBlank())return "";return try{val decoded=EngineParsing.urlDecode(ps);val json=String(Base64.getDecoder().decode(decoded),Charsets.UTF_8);val u=EngineParsing.first(json,"[\"']url[\"']\\s*:\\s*[\"']([^\"']+)[\"']").ifBlank{json}.replace("\\/","/");Regex("(/novideo/[^\"'\\s<>]+\\.html)",RegexOption.IGNORE_CASE).find(u)?.groupValues?.getOrNull(1).orEmpty()}catch(_:Exception){""}}
}

class SosalkinoCatalogEngine:SourceEngine{
    override val id="sosalkino_catalog";override val aliases=setOf("sosalkino")
    override fun buildSearchUrl(request:EngineSearchRequest):String{val b=request.baseUrl.trimEnd('/');if(b.isBlank())return "";return if(request.page<=0)"$b/new/" else "$b/new/?page=${request.page+1}"}
    override fun parseSearch(text:String,request:EngineSearchRequest):List<EngineSearchItem>{
        val q=request.query.trim().lowercase()
        val re=Regex("<a[^>]*href=([\\\"'])([^\\\"']*?/video/[^\\\"']+)\\1[^>]*>\\s*<div class=([\\\"'])video\\3>([\\s\\S]*?)</a>",setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL))
        return re.findAll(text).mapNotNull{m->
            val watch=EngineParsing.absolute(m.groupValues[2],request.baseUrl);if(watch.isBlank())return@mapNotNull null
            val block=m.groupValues[4]
            val id=Regex("/video/([^/?#\\\"]+)",RegexOption.IGNORE_CASE).find(watch)?.groupValues?.getOrNull(1).orEmpty();if(id.isBlank())return@mapNotNull null
            val title=EngineParsing.stripTags(EngineParsing.first(block,"<span[^>]*class=[\\\"'][^\\\"']*videotitle[^\\\"']*[\\\"'][^>]*>([\\s\\S]*?)</span>")).ifBlank{id.replace('_',' ')}
            if(q.isNotBlank()&&!title.lowercase().contains(q)&&!id.lowercase().contains(q))return@mapNotNull null
            val thumb=EngineParsing.first(block,"data-original=[\\\"']([^\\\"']+)[\\\"']").ifBlank{EngineParsing.first(block,"<img[^>]+src=[\\\"']([^\\\"']+)[\\\"']")}
            val duration=EngineParsing.stripTags(EngineParsing.first(block,"<div[^>]*class=[\\\"'][^\\\"']*videoduration[^\\\"']*[\\\"'][^>]*>([\\s\\S]*?)</div>"))
            val views=EngineParsing.stripTags(EngineParsing.first(block,"<span[^>]*class=[\\\"'][^\\\"']*videoviews[^\\\"']*[\\\"'][^>]*>([\\s\\S]*?)</span>"))
            EngineSearchItem(id,watch,title,EngineParsing.absolute(thumb,request.baseUrl),duration,views)
        }.take(request.maxItems.coerceAtLeast(1)).toList()
    }
}
