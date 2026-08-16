package pro.noodle.mobile.core.engine.impl

import pro.noodle.mobile.core.engine.runtime.*
import pro.noodle.mobile.core.model.*

class HlsManifestEngine:SourceEngine{
    override val id="hls_manifest"; override val aliases=setOf("hls","m3u8","hls_master","hls_playlist")
    override fun resolvePlayback(text:String,pageUrl:String):EngineResolveResult{
        if(!text.trimStart().startsWith("#EXTM3U",true)) return EngineResolveResult(handled=true,errorCode="HLS_MANIFEST_INVALID",errorMessage="Ответ не является HLS-плейлистом.")
        val lines=text.replace("\r\n","\n").replace('\r','\n').lines().map{it.trim()}
        val sources=mutableListOf<MediaSource>(); val variants=mutableListOf<MediaVariant>(); val audio=mutableListOf<AudioTrack>(); val tracks=mutableListOf<TextTrack>()
        val seenS=linkedSetOf<String>(); val seenA=linkedSetOf<String>(); val seenT=linkedSetOf<String>()
        var highest=-1L; var defaultIndex=-1; var hasStream=false; var hasSegments=false; var drm=false
        fun addSource(raw:String,label:String,mime:String,codecs:String,width:Int,height:Int,bandwidth:Long){
            val u=EngineParsing.absolute(raw.trim().trim('"'),pageUrl); if(u.isBlank()||!seenS.add(u.lowercase()))return
            sources += MediaSource(label=label.ifBlank{"HLS"},url=u,mimeType=mime,protocol="hls",width=width,height=height,bandwidth=bandwidth,codecs=codecs)
            variants += MediaVariant(id=u,label=label.ifBlank{"HLS"},mimeType=mime,codecs=codecs,width=width,height=height,bandwidth=bandwidth)
            if(bandwidth>highest){highest=bandwidth;defaultIndex=sources.lastIndex}
        }
        fun addAudio(attrs:Map<String,String>){ val group=attrs["GROUP-ID"].orEmpty();val name=attrs["NAME"].orEmpty();val lang=attrs["LANGUAGE"].orEmpty();val key="$group|$name|$lang".lowercase();if(!seenA.add(key))return;audio += AudioTrack(id=if(name.isBlank())group else "$group|$name",label=name.ifBlank{if(lang.isBlank())"Audio" else lang.uppercase()},language=lang.lowercase(),mimeType="application/vnd.apple.mpegurl",codecs=attrs["CODECS"].orEmpty(),isDefault=attrs["DEFAULT"].equals("YES",true)) }
        fun addTrack(attrs:Map<String,String>){ val u=EngineParsing.absolute(attrs["URI"].orEmpty(),pageUrl); if(u.isBlank()||!seenT.add(u.lowercase()))return;tracks += TextTrack(label=attrs["NAME"].orEmpty(),language=attrs["LANGUAGE"].orEmpty().lowercase(),kind="subtitles",url=u,isDefault=attrs["DEFAULT"].equals("YES",true)) }
        var i=0
        while(i<lines.size){
            val line=lines[i]
            when{
                line.startsWith("#EXT-X-KEY:",true)||line.startsWith("#EXT-X-SESSION-KEY:",true)->{ val up=line.uppercase(); if("METHOD=SAMPLE-AES" in up || ("KEYFORMAT=" in up && "KEYFORMAT=\"IDENTITY\"" !in up))drm=true }
                line.startsWith("#EXT-X-MEDIA:",true)->{ val a=parseAttrs(line.substringAfter(':')); when(a["TYPE"]?.uppercase()){ "SUBTITLES"->addTrack(a);"AUDIO"->addAudio(a) } }
                line.startsWith("#EXT-X-I-FRAME-STREAM-INF:",true)->{ val a=parseAttrs(line.substringAfter(':')); val (w,h)=resolution(a["RESOLUTION"].orEmpty()); val bw=(a["AVERAGE-BANDWIDTH"]?:a["BANDWIDTH"]).orEmpty().toLongOrNull()?:0; addSource(a["URI"].orEmpty(),buildLabel(a)+" I-frame","application/vnd.apple.mpegurl",a["CODECS"].orEmpty(),w,h,bw) }
                line.startsWith("#EXT-X-STREAM-INF:",true)->{ hasStream=true; val a=parseAttrs(line.substringAfter(':')); val next=lines.drop(i+1).firstOrNull{it.isNotBlank()}?.takeUnless{it.startsWith('#')}.orEmpty(); if(next.isNotBlank()){ val (w,h)=resolution(a["RESOLUTION"].orEmpty()); val bw=(a["AVERAGE-BANDWIDTH"]?:a["BANDWIDTH"]).orEmpty().toLongOrNull()?:0; addSource(next,buildLabel(a),"application/vnd.apple.mpegurl",a["CODECS"].orEmpty(),w,h,bw) } }
                line.startsWith("#EXTINF:",true)->hasSegments=true
            };i++
        }
        if(hasSegments&&!hasStream)addSource(pageUrl,"HLS","application/vnd.apple.mpegurl","",0,0,0)
        if(defaultIndex<0&&variants.isNotEmpty())defaultIndex=0
        if(defaultIndex in variants.indices)variants[defaultIndex]=variants[defaultIndex].copy(isDefault=true)
        if(hasStream)sources.add(0,MediaSource(label="HLS auto",url=pageUrl,mimeType="application/vnd.apple.mpegurl",protocol="hls",isDefault=true)) else if(sources.isNotEmpty())sources[0]=sources[0].copy(isDefault=true)
        if(drm)return EngineResolveResult(handled=true,success=false,sources=sources,variants=variants,audioTracks=audio,tracks=tracks,errorCode="HLS_DRM_UNSUPPORTED",errorMessage="Плейлист содержит DRM/SAMPLE-AES и требует официального проигрывателя.")
        return if(sources.isNotEmpty())EngineResolveResult(handled=true,success=true,sources=sources,variants=variants,audioTracks=audio,tracks=tracks) else EngineResolveResult(handled=true,errorCode="HLS_VARIANTS_NOT_FOUND",errorMessage="В HLS-плейлисте не найдены варианты или медиасегменты.")
    }
    private fun parseAttrs(s:String):Map<String,String>{ val out=linkedMapOf<String,String>(); Regex("([A-Z0-9-]+)=(\"(?:[^\"]|\"\")*\"|[^,]*)",RegexOption.IGNORE_CASE).findAll(s).forEach{m->out[m.groupValues[1].uppercase()]=m.groupValues[2].trim().removeSurrounding("\"")};return out }
    private fun resolution(s:String):Pair<Int,Int>{val m=Regex("^(\\d+)x(\\d+)$",RegexOption.IGNORE_CASE).matchEntire(s.trim())?:return 0 to 0;return (m.groupValues[1].toIntOrNull()?:0) to (m.groupValues[2].toIntOrNull()?:0)}
    private fun buildLabel(a:Map<String,String>):String{a["NAME"]?.takeIf{it.isNotBlank()}?.let{return it}; val (_,h)=resolution(a["RESOLUTION"].orEmpty());var r=if(h>0)"${h}p" else "HLS";val bw=(a["AVERAGE-BANDWIDTH"]?:a["BANDWIDTH"]).orEmpty().toLongOrNull()?:0;if(bw>0)r += " • %.1f Mbps".format(java.util.Locale.US,bw/1_000_000.0);return r}
}
