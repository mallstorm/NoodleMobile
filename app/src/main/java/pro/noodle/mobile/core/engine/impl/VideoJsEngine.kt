package pro.noodle.mobile.core.engine.impl

import pro.noodle.mobile.core.engine.runtime.*
import pro.noodle.mobile.core.json.*
import pro.noodle.mobile.core.model.MediaSource
import pro.noodle.mobile.core.model.TextTrack

class VideoJsEngine:SourceEngine{
    override val id="videojs_config"; override val aliases=setOf("videojs","video.js","video_js")
    override fun resolvePlayback(text:String,pageUrl:String):EngineResolveResult{
        if(!Regex("video-js|videojs\\s*\\(|data-setup",RegexOption.IGNORE_CASE).containsMatchIn(text))return EngineResolveResult()
        val sources=mutableListOf<MediaSource>();val tracks=mutableListOf<TextTrack>();val seen=linkedSetOf<String>();val seenT=linkedSetOf<String>()
        fun addSource(raw:String,type:String="",label:String=""){val u=EngineParsing.absolute(raw,pageUrl);if(u.isBlank()||!seen.add(u.lowercase()))return;val mime=type.ifBlank{when(EngineParsing.protocol(u)){"hls"->"application/vnd.apple.mpegurl";"dash"->"application/dash+xml";else->"video/mp4"}};sources += MediaSource(label=EngineParsing.inferLabel(u,mime,label),url=u,mimeType=mime,protocol=EngineParsing.protocol(u,mime),isDefault=sources.isEmpty())}
        fun addTrack(raw:String,kind:String="subtitles",lang:String="",label:String=""){val u=EngineParsing.absolute(raw,pageUrl);if(u.isBlank()||!seenT.add(u.lowercase()))return;tracks += TextTrack(label=label,language=lang.lowercase(),kind=kind.ifBlank{"subtitles"}.lowercase(),url=u,isDefault=tracks.isEmpty())}
        Regex("<source\\b[^>]*>",setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL)).findAll(text).forEach{val tag=it.value;addSource(EngineParsing.attr(tag,"src"),EngineParsing.attr(tag,"type"),EngineParsing.attr(tag,"label"))}
        Regex("<track\\b[^>]*>",setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL)).findAll(text).forEach{val tag=it.value;addTrack(EngineParsing.attr(tag,"src"),EngineParsing.attr(tag,"kind"),EngineParsing.attr(tag,"srclang"),EngineParsing.attr(tag,"label"))}
        val videoTag=Regex("<video\\b[^>]*>",setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL)).findAll(text).map{it.value}.firstOrNull{tag->EngineParsing.attr(tag,"data-setup").isNotBlank()||EngineParsing.attr(tag,"class").contains("video-js",true)}.orEmpty()
        val dataSetup=EngineParsing.attr(videoTag,"data-setup")
        if(dataSetup.isNotBlank()){
            try{val o=MiniJson.parse(EngineParsing.htmlDecode(dataSetup)) as? JsonValue.Obj;o?.arr("sources")?.values?.mapNotNull{it as? JsonValue.Obj}?.forEach{addSource(it.string("src").orEmpty().ifBlank{it.string("file").orEmpty()},it.string("type").orEmpty(),it.string("label").orEmpty())};o?.arr("tracks")?.values?.mapNotNull{it as? JsonValue.Obj}?.forEach{addTrack(it.string("src").orEmpty().ifBlank{it.string("file").orEmpty()},it.string("kind").orEmpty(),it.string("srclang").orEmpty().ifBlank{it.string("language").orEmpty()},it.string("label").orEmpty())}}catch(_:Exception){}
        }
        val poster=EngineParsing.absolute(EngineParsing.attr(videoTag,"poster"),pageUrl)
        return if(sources.isNotEmpty())EngineResolveResult(handled=true,success=true,title=EngineParsing.titleFromHtml(text),thumbnailUrl=poster,sources=sources,tracks=tracks) else EngineResolveResult(handled=true,title=EngineParsing.titleFromHtml(text),thumbnailUrl=poster,errorCode="VIDEOJS_MEDIA_NOT_FOUND",errorMessage="Video.js найден, но источники отсутствуют.")
    }
}
