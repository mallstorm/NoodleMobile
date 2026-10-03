package pro.noodle.mobile.core.engine.impl

import pro.noodle.mobile.core.engine.runtime.*
import pro.noodle.mobile.core.model.MediaSource
import pro.noodle.mobile.core.model.TextTrack

class JwPlayerEngine:SourceEngine{
    override val id="jwplayer_config"; override val aliases=setOf("jwplayer","jw_player","jw_setup","jw")
    override fun resolvePlayback(text:String,pageUrl:String):EngineResolveResult{
        if(!Regex("jwplayer\\s*\\(|\\.setup\\s*\\(|\\bplaylist\\s*:\\s*\\[|window\\.playlist\\s*=|\\bplaylist\\s*=\\s*\\{",RegexOption.IGNORE_CASE).containsMatchIn(text))return EngineResolveResult()
        var config=EngineParsing.first(text,"jwplayer\\s*\\([^)]*\\)\\s*\\.setup\\s*\\(\\s*(\\{[\\s\\S]*?\\})\\s*\\)").ifBlank{text}
        config=Regex("[\"'](file|src|label|res|quality|type|default|title|image|poster|kind|srclang|language|playlist)[\"']\\s*:",RegexOption.IGNORE_CASE).replace(config,"$1:")
        val title=EngineParsing.htmlDecode(EngineParsing.first(config,"\\btitle\\s*:\\s*[\"']([^\"']+)[\"']"))
        val thumb=EngineParsing.absolute(EngineParsing.first(config,"\\b(?:image|poster)\\s*:\\s*[\"']([^\"']+)[\"']"),pageUrl)
        val seen=linkedSetOf<String>();val seenT=linkedSetOf<String>();val sources=mutableListOf<MediaSource>();val tracks=mutableListOf<TextTrack>()
        fun addSource(raw:String,declared:String,type:String,default:Boolean){val u=EngineParsing.absolute(raw.replace("\\u0026","&"),pageUrl);if(u.isBlank()||!seen.add(u.lowercase()))return;val mime=EngineParsing.htmlDecode(type).ifBlank{when(EngineParsing.protocol(u)){"hls"->"application/vnd.apple.mpegurl";"dash"->"application/dash+xml";else->"video/mp4"}};sources += MediaSource(label=EngineParsing.inferLabel(u,mime,declared),url=u,mimeType=mime,protocol=EngineParsing.protocol(u,mime),isDefault=default)}
        fun addTrack(block:String){val raw=EngineParsing.first(block,"\\b(?:file|src)\\s*:\\s*[\"']([^\"']+)[\"']");val u=EngineParsing.absolute(raw,pageUrl);if(u.isBlank()||!seenT.add(u.lowercase()))return;tracks += TextTrack(label=EngineParsing.htmlDecode(EngineParsing.first(block,"\\blabel\\s*:\\s*[\"']([^\"']+)[\"']")),language=EngineParsing.htmlDecode(EngineParsing.first(block,"\\b(?:srclang|language)\\s*:\\s*[\"']([^\"']+)[\"']")).lowercase(),kind=EngineParsing.htmlDecode(EngineParsing.first(block,"\\bkind\\s*:\\s*[\"']([^\"']+)[\"']")).ifBlank{"subtitles"}.lowercase(),url=u,isDefault=Regex("\\bdefault\\s*:\\s*(?:true|1)",RegexOption.IGNORE_CASE).containsMatchIn(block))}
        Regex("\\{([^{}]*(?:\\bfile|\\bsrc)\\s*:\\s*[\"'][^\"']+[\"'][^{}]*)\\}",setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL)).findAll(config).forEach{m->val b=m.groupValues[1];val raw=EngineParsing.first(b,"\\b(?:file|src)\\s*:\\s*[\"']([^\"']+)[\"']");val label=EngineParsing.first(b,"\\b(?:label|res|quality)\\s*:\\s*[\"']?([^,\"'}\\s]+)");val type=EngineParsing.first(b,"\\btype\\s*:\\s*[\"']([^\"']+)[\"']");if(Regex("\\bkind\\s*:\\s*[\"'](?:captions|subtitles)[\"']",RegexOption.IGNORE_CASE).containsMatchIn(b))addTrack(b) else addSource(raw,label,type,Regex("\\bdefault\\s*:\\s*(?:true|1)",RegexOption.IGNORE_CASE).containsMatchIn(b))}
        if(sources.isEmpty())Regex("\\b(?:file|src)\\s*:\\s*[\"']([^\"']+)[\"']",RegexOption.IGNORE_CASE).findAll(config).forEach{m->addSource(m.groupValues[1],"","",false)}
        // Dedicated second track pass catches tracks even if the object regex above was skipped by nested config.
        Regex("\\{([^{}]*\\bkind\\s*:\\s*[\"'](?:captions|subtitles)[\"'][^{}]*)\\}",setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL)).findAll(config).forEach{addTrack(it.groupValues[1])}
        val playlist=EngineParsing.absolute(EngineParsing.first(config,"\\bplaylist\\s*:\\s*[\"']([^\"']+)[\"']"),pageUrl)
        val playbackSources = if(sources.isNotEmpty() && sources.none{it.isDefault}) listOf(sources.first().copy(isDefault=true)) + sources.drop(1) else sources.toList()
        return if(playbackSources.isNotEmpty())EngineResolveResult(handled=true,success=true,title=title,thumbnailUrl=thumb,playerPageUrl=pageUrl,sources=playbackSources,tracks=tracks,followUpPages=listOfNotNull(playlist.takeIf{it.isNotBlank()})) else EngineResolveResult(handled=true,title=title,thumbnailUrl=thumb,playerPageUrl=pageUrl,tracks=tracks,followUpPages=listOfNotNull(playlist.takeIf{it.isNotBlank()}),errorCode="JWPLAYER_MEDIA_NOT_FOUND",errorMessage="Конфигурация JW Player найдена, но источники отсутствуют.")
    }
}
