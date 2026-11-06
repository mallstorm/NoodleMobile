package pro.noodle.mobile.core.engine.impl

import pro.noodle.mobile.core.engine.runtime.*
import pro.noodle.mobile.core.model.MediaSource
import pro.noodle.mobile.core.model.TextTrack

class GenericHtml5Engine : SourceEngine {
    override val id="generic_html5"
    override val aliases=setOf("html5","generic","generic_video","video_object")

    override fun resolvePlayback(text:String,pageUrl:String):EngineResolveResult{
        if(text.isBlank()) return EngineResolveResult(handled=true,errorCode="GENERIC_HTML_EMPTY",errorMessage="Страница не содержит HTML.")
        val seen=linkedSetOf<String>(); val sources=mutableListOf<MediaSource>(); val tracks=mutableListOf<TextTrack>(); val seenTracks=linkedSetOf<String>()
        fun addSource(raw:String,mime:String="",label:String="",default:Boolean=false){ val u=EngineParsing.absolute(raw,pageUrl); if(u.isBlank()||!(u.startsWith("http://",true)||u.startsWith("https://",true))||EngineParsing.isPreviewClip(u)||!seen.add(u.lowercase()))return; sources += MediaSource(label=EngineParsing.inferLabel(u,mime,label),url=u,mimeType=mime,protocol=EngineParsing.protocol(u,mime),isDefault=default||sources.isEmpty()) }
        fun addTrack(raw:String,kind:String="subtitles",lang:String="",label:String="",default:Boolean=false){ val u=EngineParsing.absolute(raw,pageUrl); if(u.isBlank()||!seenTracks.add(u.lowercase()))return; tracks += TextTrack(label=EngineParsing.htmlDecode(label),language=lang.trim().lowercase(),kind=kind.ifBlank{"subtitles"}.lowercase(),url=u,isDefault=default) }

        Regex("<source\\b[^>]*>",setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL)).findAll(text).forEach{m-> val tag=m.value; addSource(EngineParsing.attr(tag,"src"),EngineParsing.attr(tag,"type"),EngineParsing.attr(tag,"label"),EngineParsing.hasBooleanAttr(tag,"default")) }
        Regex("<video\\b[^>]*>",setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL)).findAll(text).forEach{m-> val tag=m.value; addSource(EngineParsing.attr(tag,"src"),EngineParsing.attr(tag,"type"),EngineParsing.attr(tag,"data-quality"),EngineParsing.hasBooleanAttr(tag,"autoplay")) }
        Regex("<track\\b[^>]*>",setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL)).findAll(text).forEach{m-> val tag=m.value; addTrack(EngineParsing.attr(tag,"src"),EngineParsing.attr(tag,"kind"),EngineParsing.attr(tag,"srclang"),EngineParsing.attr(tag,"label"),EngineParsing.hasBooleanAttr(tag,"default")) }

        val ogVideo = EngineParsing.first(text,"<meta[^>]+(?:property|name)=[\"']og:video(?::url|:secure_url)?[\"'][^>]+content=[\"']([^\"']+)[\"']")
            .ifBlank { EngineParsing.first(text,"<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+(?:property|name)=[\"']og:video(?::url|:secure_url)?[\"']") }
        addSource(ogVideo)
        val twitter=EngineParsing.first(text,"<meta[^>]+(?:property|name)=[\"']twitter:player:stream[\"'][^>]+content=[\"']([^\"']+)[\"']")
        addSource(twitter)
        Regex("<script[^>]+type=[\"']application/ld\\+json[\"'][^>]*>([\\s\\S]*?)</script>",setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL)).findAll(text).forEach { m ->
            val body=EngineParsing.htmlDecode(m.groupValues[1])
            if(body.contains("VideoObject",true)) {
                addSource(EngineParsing.first(body,"[\"'](?:contentUrl|embedUrl)[\"']\\s*:\\s*[\"']([^\"']+)[\"']"))
            }
        }
        val iframe=EngineParsing.first(text,"<iframe[^>]+src=[\"']([^\"']+)[\"']")
        val player=EngineParsing.absolute(iframe,pageUrl)
        val success=sources.isNotEmpty()||tracks.isNotEmpty()||player.isNotBlank()
        return EngineResolveResult(handled=true,success=success,title=EngineParsing.titleFromHtml(text),thumbnailUrl=EngineParsing.imageFromHtml(text,pageUrl),playerPageUrl=player,sources=sources,tracks=tracks,followUpPages=listOfNotNull(player.takeIf{it.isNotBlank()}),errorCode=if(success)"" else "GENERIC_MEDIA_NOT_FOUND",errorMessage=if(success)"" else "HTML5 media не найдено.")
    }
}
