package pro.noodle.mobile.core.resolver

import pro.noodle.mobile.core.engine.EngineRegistry
import pro.noodle.mobile.core.engine.runtime.*
import pro.noodle.mobile.core.model.*

fun interface ResolverFetcher { fun fetch(url:String): String? }

data class ResolverLimits(val maxHlsManifests:Int=6,val maxDashManifests:Int=4,val maxFollowUpPages:Int=8,val maxIframeDepth:Int=3)

object ResolverPipeline {
    fun resolve(initialText:String,pageUrl:String,primaryEngine:String,strategies:List<String>,fetcher:ResolverFetcher?=null,limits:ResolverLimits=ResolverLimits()):EngineResolveResult{
        val strategyList=mutableListOf<String>(); val seenStrategies=linkedSetOf<String>(); val seenPages=linkedSetOf(pageUrl.lowercase()); val seenManifests=linkedSetOf<String>(); val pages=ArrayDeque<Pair<String,Int>>()
        fun normalize(v:String):String{val x=v.trim().lowercase();return when(x){"html5","open_graph","video_object","generic"->"generic_html5";"player_config","js_player"->"generic_player_config";"hls","m3u8","hls_master","hls_playlist"->"hls_manifest";"dash","mpd","mpeg_dash"->"dash_manifest";"jwplayer","jw_player"->"jwplayer_config";"videojs","video_js"->"videojs_config";"iframe","iframes","embed_chain"->"iframe_chain";"oembed","official_embed"->"oembed_provider";else->EngineRegistry.normalizeId(x)}}
        fun addStrategy(v:String){val n=normalize(v);if(n.isNotBlank()&&seenStrategies.add(n))strategyList+=n}
        addStrategy(primaryEngine);strategies.forEach(::addStrategy); listOf("jwplayer_config","videojs_config","generic_player_config","generic_html5","oembed_provider","iframe_chain").forEach(::addStrategy)
        if(initialText.trimStart().startsWith("#EXTM3U",true)||pageUrl.lowercase().contains(".m3u8"))addStrategy("hls_manifest")
        if(initialText.contains("<mpd",true)||pageUrl.lowercase().contains(".mpd"))addStrategy("dash_manifest")
        var result=EngineResolveResult()
        fun merge(source:EngineResolveResult){if(!source.handled)return; val s=(result.sources+source.sources).distinctBy{it.url.lowercase()};val v=(result.variants+source.variants).distinctBy{"${it.id}|${it.label}|${it.bandwidth}".lowercase()};val a=(result.audioTracks+source.audioTracks).distinctBy{"${it.id}|${it.language}|${it.label}".lowercase()};val t=(result.tracks+source.tracks).distinctBy{it.url.lowercase()};val f=(result.followUpPages+source.followUpPages).distinctBy{it.lowercase()};result=result.copy(handled=true,success=result.success||source.success,title=result.title.ifBlank{source.title},thumbnailUrl=result.thumbnailUrl.ifBlank{source.thumbnailUrl},playerPageUrl=result.playerPageUrl.ifBlank{source.playerPageUrl},sources=s,variants=v,audioTracks=a,tracks=t,followUpPages=f,errorCode=result.errorCode.ifBlank{source.errorCode},errorMessage=result.errorMessage.ifBlank{source.errorMessage})}
        fun queue(url:String,depth:Int){if(url.isBlank()||depth>limits.maxIframeDepth)return; if(seenPages.add(url.lowercase()))pages+=url to depth}
        fun runStrategies(body:String,url:String,depth:Int){strategyList.forEach{s->val e=RuntimeEngineRegistry.find(s)?:return@forEach;val step=e.resolvePlayback(body,url);if(step.handled){merge(step);step.followUpPages.forEach{queue(it,depth+1)}}}}
        fun removeSource(url:String){result=result.copy(sources=result.sources.filterNot{it.url.equals(url,true)},success=result.sources.any{!it.url.equals(url,true)}||result.variants.isNotEmpty())}
        fun expand(engineId:String,max:Int,drmCode:String,isCandidate:(MediaSource)->Boolean){val f=fetcher?:return;val e=RuntimeEngineRegistry.find(engineId)?:return;val candidates=result.sources.filter(isCandidate).map{it.url}.filter{seenManifests.add("$engineId|${it.lowercase()}")}.take(max);for(url in candidates){val body=f.fetch(url)?:continue;val step=e.resolvePlayback(body,url);if(step.handled){if(step.errorCode.equals(drmCode,true))removeSource(url);merge(step)}}}
        fun expandAdaptive(){expand("hls_manifest",limits.maxHlsManifests,"HLS_DRM_UNSUPPORTED"){it.protocol.equals("hls",true)||it.mimeType.contains("mpegurl",true)||it.url.contains(".m3u8",true)};expand("dash_manifest",limits.maxDashManifests,"DASH_DRM_UNSUPPORTED"){it.protocol.equals("dash",true)||it.mimeType.contains("dash+xml",true)||it.url.contains(".mpd",true)}}
        runStrategies(initialText,pageUrl,0);expandAdaptive()
        var followed=0
        while(fetcher!=null&&pages.isNotEmpty()&&followed<limits.maxFollowUpPages){val (url,depth)=pages.removeFirst();followed++;val body=f.fetch(url).orEmpty();if(body.isBlank())continue;runStrategies(body,url,depth);expandAdaptive()}
        result=when{
            result.success&&!result.errorCode.equals("HLS_DRM_UNSUPPORTED",true)&&!result.errorCode.equals("DASH_DRM_UNSUPPORTED",true)->result.copy(errorCode="",errorMessage="")
            result.handled&&result.errorCode.isBlank()->result.copy(errorCode="RESOLVER_PIPELINE_EMPTY",errorMessage="Ни одна стратегия не вернула воспроизводимый источник.")
            else->result
        }
        return result
    }
}
