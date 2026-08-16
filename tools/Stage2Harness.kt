import pro.noodle.mobile.core.engine.impl.*
import pro.noodle.mobile.core.engine.runtime.*
import pro.noodle.mobile.core.resolver.*

private data class T(val name:String,val ok:Boolean,val detail:String="")
private val tests=mutableListOf<T>()
private fun check(name:String, condition:Boolean, detail:String=""){tests+=T(name,condition,detail);println("${if(condition)"PASS" else "FAIL"} | $name${if(detail.isBlank())"" else " | $detail"}")}
fun main(){
    check("Runtime registry 15 engines", RuntimeEngineRegistry.implementedIds.size==15, RuntimeEngineRegistry.implementedIds.sorted().joinToString())
    run{
        val html="<ul id=\"list_videos_test_items\"><li><a href=\"/view/123/\"><img data-original=\"/thumb/123.jpg\" alt=\"Test video\"><span class=\"title\">Test video</span><span class=\"duration\">12:34</span><span class=\"views\">1000</span></a></li></ul>"
        val e=RuntimeEngineRegistry.find("kvs_view")!!;val r=e.parseSearch(html,EngineSearchRequest("https://example.com","test",maxItems=20));check("KVS view parser",r.size==1&&r[0].remoteId=="123"&&r[0].thumbnailUrl=="https://example.com/thumb/123.jpg",r.toString())
    }
    run{
        val html="<a href=\"/videos/latest/\" class=\"item\"><span class=\"name\">Latest</span></a><a href=\"/videos/sample-video/\" class=\"item\"><img src=\"/img/sample.jpg\" alt=\"Sample\"><span class=\"name\">Sample title</span><span class=\"time\">05:00</span></a>"
        val r=RuntimeEngineRegistry.find("kvs_videos")!!.parseSearch(html,EngineSearchRequest("https://example.com","sample",maxItems=20));check("KVS videos skips catalog",r.size==1&&r[0].remoteId=="sample-video",r.toString())
    }
    run{
        val html="<html><head><title>Sample video</title></head><body><video poster=\"/poster.jpg\"><source src=\"/media/video-720.mp4\" type=\"video/mp4\" label=\"720p\"><source src=\"https://cdn.example.com/master.m3u8\" type=\"application/vnd.apple.mpegurl\"><track src=\"/subs/ru.vtt\" kind=\"subtitles\" srclang=\"ru\" label=\"Русский\" default></video></body></html>"
        val r=GenericHtml5Engine().resolvePlayback(html,"https://example.com/watch/1");check("Generic HTML5 source+track",r.success&&r.sources.size==2&&r.sources[1].label=="HLS"&&r.tracks.size==1&&r.tracks[0].isDefault,r.toString())
    }
    run{
        val html="<html><head><meta content=\"Structured title\" property=\"og:title\"><meta content=\"https://cdn.example.com/poster.jpg\" property=\"og:image\"><meta content=\"https://cdn.example.com/video.mp4\" property=\"og:video\"><script type=\"application/ld+json\">{\"@type\":\"VideoObject\",\"contentUrl\":\"https:\\/\\/cdn.example.com\\/video.mp4\"}</script></head></html>"
        val r=GenericHtml5Engine().resolvePlayback(html,"https://example.com/watch/2");check("Generic OpenGraph dedupe",r.success&&r.title=="Structured title"&&r.sources.size==1,r.toString())
    }
    run{
        val m="#EXTM3U\n#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"Русский\",LANGUAGE=\"ru\",DEFAULT=YES,URI=\"subs/ru.m3u8\"\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"Русский\",LANGUAGE=\"ru\",DEFAULT=YES,URI=\"audio/ru.m3u8\"\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"English\",LANGUAGE=\"en\",DEFAULT=NO,URI=\"audio/en.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=1280x720,SUBTITLES=\"subs\"\nvideo/720.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=4500000,RESOLUTION=1920x1080,SUBTITLES=\"subs\"\nvideo/1080.m3u8\n"
        val r=HlsManifestEngine().resolvePlayback(m,"https://cdn.example.com/master/index.m3u8?token=abc");check("HLS master variants/tracks",r.success&&r.sources.size==3&&r.variants.size==2&&r.variants[1].isDefault&&r.audioTracks.size==2&&r.tracks.size==1,r.toString())
    }
    run{val m="#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://license\"\n#EXTINF:6.0,\nsegment.ts";val r=HlsManifestEngine().resolvePlayback(m,"https://example.com/p.m3u8");check("HLS DRM refused",!r.success&&r.errorCode=="HLS_DRM_UNSUPPORTED")}
    run{
        val x="<MPD><Period><AdaptationSet contentType=\"video\" mimeType=\"video/mp4\"><Representation id=\"v1\" width=\"1280\" height=\"720\" bandwidth=\"2400000\" codecs=\"avc1.64001f\"/><Representation id=\"v2\" width=\"1920\" height=\"1080\" bandwidth=\"5000000\"/></AdaptationSet><AdaptationSet contentType=\"audio\" lang=\"ru\" label=\"Русский\" mimeType=\"audio/mp4\"><Representation id=\"a1\" bandwidth=\"128000\"/></AdaptationSet><AdaptationSet contentType=\"text\" lang=\"en\"><BaseURL>subs/en.vtt</BaseURL></AdaptationSet></Period></MPD>"
        val r=DashManifestEngine().resolvePlayback(x,"https://cdn.example/video/master.mpd");check("DASH variants/audio/text",r.success&&r.variants.size==2&&r.audioTracks.size==1&&r.tracks.firstOrNull()?.url=="https://cdn.example/video/subs/en.vtt",r.toString())
    }
    run{val x="<MPD><Period><AdaptationSet><ContentProtection schemeIdUri=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"/></AdaptationSet></Period></MPD>";val r=DashManifestEngine().resolvePlayback(x,"https://cdn.example/protected.mpd");check("DASH Widevine refused",!r.success&&r.errorCode=="DASH_DRM_UNSUPPORTED")}
    run{
        val h="<script>jwplayer(\"p\").setup({title:\"Demo\",image:\"/poster.jpg\",sources:[{file:\"/master.m3u8\",label:\"Auto\",default:true},{file:\"/720.mp4\",label:\"720p\"}],tracks:[{file:\"/ru.vtt\",kind:\"captions\",label:\"RU\"}]});</script>"
        val r=JwPlayerEngine().resolvePlayback(h,"https://example.com/watch/1");check("JW Player sources/tracks",r.success&&r.sources.size==2&&r.tracks.size==1,r.toString())
    }
    run{val h="<script>jwplayer(\"p\").setup({\"file\":\"https://cdn.example/master.mpd\",\"type\":\"dash\",\"playlist\":\"/api/list.json\"});</script>";val r=JwPlayerEngine().resolvePlayback(h,"https://site.example/watch/1");check("JW quoted config",r.success&&r.sources.firstOrNull()?.protocol=="dash"&&r.followUpPages.firstOrNull()=="https://site.example/api/list.json",r.toString())}
    run{val h="<video class=\"video-js\" data-setup='{\"sources\":[{\"src\":\"https://cdn.example/master.mpd\",\"type\":\"application/dash+xml\"}],\"tracks\":[{\"src\":\"/ru.vtt\",\"kind\":\"subtitles\",\"srclang\":\"ru\",\"label\":\"Русский\"}]}'></video>";val r=VideoJsEngine().resolvePlayback(h,"https://site.example/v/1");check("Video.js data-setup",r.success&&r.sources.size==1&&r.sources[0].protocol=="dash"&&r.tracks.firstOrNull()?.language=="ru",r.toString())}
    run{val r=IFrameChainEngine().resolvePlayback("<iframe src=\"/embed/1\"></iframe><iframe src=\"/embed/1\"></iframe>","https://example.com/watch");check("Iframe dedupe",r.success&&r.followUpPages==listOf("https://example.com/embed/1"),r.toString())}
    run{val r=OEmbedEngine().resolvePlayback("<html><head><link rel=\"alternate\" type=\"application/json+oembed\" href=\"https://example.com/oembed?url=42\"></head></html>","https://example.com/watch/42");check("oEmbed discovery",r.success&&r.followUpPages.size==1,r.toString())}
    run{val r=OEmbedEngine().resolvePlayback("{\"type\":\"video\",\"title\":\"Official embed\",\"thumbnail_url\":\"https://example.com/t.jpg\",\"html\":\"<iframe src=\\\"https://player.example.com/embed/42\\\"></iframe>\"}","https://example.com/oembed");check("oEmbed JSON iframe",r.success&&r.title=="Official embed"&&r.playerPageUrl=="https://player.example.com/embed/42",r.toString())}
    run{
        val html="<html><head><title>Pipeline sample</title></head><body><video><source src=\"https://cdn.example.com/master.m3u8\" type=\"application/vnd.apple.mpegurl\"></video></body></html>";val master="#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720\n720.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080\n1080.m3u8\n";val r=ResolverPipeline.resolve(html,"https://example.com/watch/1","generic_html5",listOf("generic_html5","hls_manifest"),ResolverFetcher{if(it=="https://cdn.example.com/master.m3u8")master else null});check("Resolver expands HLS",r.success&&r.sources.size==3&&r.title=="Pipeline sample",r.toString())
    }
    run{
        val html="<video><source src=\"https://cdn.example.com/master.m3u8\" type=\"application/vnd.apple.mpegurl\"></video>";val r=ResolverPipeline.resolve(html,"https://example.com/watch/2","generic_html5",listOf("generic_html5","hls_manifest"),ResolverFetcher{null});check("Resolver keeps HLS fallback",r.success&&r.sources.size==1,r.toString())
    }
    run{
        val html="<article class=\"video card\"><a href=\"/watch/42\"><span class=\"title\">Demo &amp; Test</span><img data-src=\"/img/42.jpg\"><span class=\"duration\">03:14</span></a></article>";val req=EngineSearchRequest(baseUrl="https://example.com",query="demo",itemSelector="article.video",titleSelector="span.title",urlSelector="a",thumbnailSelector="img",thumbnailAttribute="data-src",durationSelector="span.duration");val r=DeclarativeHtmlEngine().parseSearch(html,req);check("Declarative CSS-lite",r.size==1&&r[0].title=="Demo & Test"&&r[0].watchPath=="https://example.com/watch/42",r.toString())
    }
    run{val j="{\"data\":{\"items\":[{\"id\":\"42\",\"title\":\"API item\",\"url\":\"/watch/42\",\"image\":\"/img/42.jpg\",\"meta\":{\"duration\":\"02:00\"}}]}}";val req=EngineSearchRequest(baseUrl="https://api.example.com",query="x",itemsPath="$.data.items",idPath="$.id",titlePath="$.title",urlPath="$.url",thumbnailPath="$.image",durationPath="$.meta.duration");val r=JsonApiEngine().parseSearch(j,req);check("JSON API paths",r.size==1&&r[0].remoteId=="42"&&r[0].durationText=="02:00",r.toString())}
    run{val e=SosalkinoCatalogEngine();val h="<a href=\"/video/demo_movie\"><div class=\"video\"><img data-original=\"/poster.jpg\"><span class=\"videotitle\">Demo Movie</span><div class=\"videoduration\">10:00</div><span class=\"videoviews\">123</span></div></a>";val r=e.parseSearch(h,EngineSearchRequest("https://example.com","demo",0,20));check("Sosalkino catalog page",e.buildSearchUrl(EngineSearchRequest("https://example.com","demo",1,20))=="https://example.com/new/?page=2"&&r.firstOrNull()?.remoteId=="demo_movie",r.toString())}
    run{val e=WordPressRestEngine();val u=e.buildSearchUrl(EngineSearchRequest("https://example.com","demo",0,25));val r=e.parseSearch("[{\"id\":9,\"title\":\"WordPress item\",\"url\":\"https://example.com/video/item\"}]",EngineSearchRequest("https://example.com","x"));check("WordPress REST",u=="https://example.com/wp-json/wp/v2/search?search=demo&page=1&per_page=25"&&r.firstOrNull()?.remoteId=="9",u+" "+r)}
    val failed=tests.filterNot{it.ok};println("SUMMARY ${tests.size-failed.size}/${tests.size} passed");if(failed.isNotEmpty())error("Failed: "+failed.joinToString{it.name})
}
