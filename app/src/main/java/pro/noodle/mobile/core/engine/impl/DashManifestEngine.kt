package pro.noodle.mobile.core.engine.impl

import org.w3c.dom.Element
import pro.noodle.mobile.core.engine.runtime.*
import pro.noodle.mobile.core.model.*
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

class DashManifestEngine:SourceEngine{
    override val id="dash_manifest"; override val aliases=setOf("dash","mpd")
    override fun resolvePlayback(text:String,pageUrl:String):EngineResolveResult{
        if(text.isBlank()||!text.contains("<MPD",true))return EngineResolveResult(handled=true,errorCode="DASH_MANIFEST_INVALID",errorMessage="Ответ не является DASH MPD.")
        val doc=try{ val f=DocumentBuilderFactory.newInstance();f.isNamespaceAware=true;runCatching{f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)};runCatching{f.setFeature("http://xml.org/sax/features/external-general-entities",false)};runCatching{f.setFeature("http://xml.org/sax/features/external-parameter-entities",false)};f.newDocumentBuilder().parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))) }catch(e:Exception){return EngineResolveResult(handled=true,errorCode="DASH_XML_INVALID",errorMessage=e.message.orEmpty())}
        val protections=doc.getElementsByTagNameNS("*","ContentProtection")
        for(i in 0 until protections.length){val e=protections.item(i) as? Element?:continue; val scheme=e.getAttribute("schemeIdUri").lowercase(); if("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed" in scheme||"9a04f079-9840-4286-ab92-e65be0885f95" in scheme||"widevine" in scheme||"playready" in scheme)return EngineResolveResult(handled=true,errorCode="DASH_DRM_UNSUPPORTED",errorMessage="MPD содержит DRM ContentProtection.")}
        val variants=mutableListOf<MediaVariant>();val audio=mutableListOf<AudioTrack>();val tracks=mutableListOf<TextTrack>()
        val sets=doc.getElementsByTagNameNS("*","AdaptationSet")
        for(i in 0 until sets.length){val set=sets.item(i) as? Element?:continue;val content=set.getAttribute("contentType").lowercase();val mime=set.getAttribute("mimeType").lowercase();val lang=set.getAttribute("lang");val label=set.getAttribute("label");val kind=when{content.isNotBlank()->content;mime.startsWith("video/")->"video";mime.startsWith("audio/")->"audio";mime.startsWith("text/")||mime.contains("ttml")||mime.contains("vtt")->"text";else->""}
            val reps=set.getElementsByTagNameNS("*","Representation")
            when(kind){
                "video"-> for(j in 0 until reps.length){val r=reps.item(j) as? Element?:continue;val id=r.getAttribute("id").ifBlank{"v${variants.size+1}"};val width=(r.getAttribute("width").ifBlank{set.getAttribute("width")}).toIntOrNull()?:0;val height=(r.getAttribute("height").ifBlank{set.getAttribute("height")}).toIntOrNull()?:0;val bw=r.getAttribute("bandwidth").toLongOrNull()?:0;val codecs=r.getAttribute("codecs").ifBlank{set.getAttribute("codecs")};val rmime=r.getAttribute("mimeType").ifBlank{set.getAttribute("mimeType")};variants += MediaVariant(id=id,label=if(height>0)"${height}p" else id,mimeType=rmime,codecs=codecs,width=width,height=height,bandwidth=bw)}
                "audio"->{ if(reps.length==0)audio += AudioTrack(id=set.getAttribute("id").ifBlank{"a${audio.size+1}"},label=label.ifBlank{lang.ifBlank{"Audio"}},language=lang.lowercase(),mimeType=set.getAttribute("mimeType"),codecs=set.getAttribute("codecs"),isDefault=audio.isEmpty()) else for(j in 0 until reps.length){val r=reps.item(j) as? Element?:continue;audio += AudioTrack(id=r.getAttribute("id").ifBlank{"a${audio.size+1}"},label=label.ifBlank{lang.ifBlank{"Audio"}},language=lang.lowercase(),mimeType=r.getAttribute("mimeType").ifBlank{set.getAttribute("mimeType")},codecs=r.getAttribute("codecs").ifBlank{set.getAttribute("codecs")},isDefault=audio.isEmpty())} }
                "text","subtitle","subtitles"->{ val base=findBaseUrl(set);if(base.isNotBlank()){val u=EngineParsing.absolute(base,pageUrl);if(u.isNotBlank())tracks += TextTrack(label=label.ifBlank{lang.ifBlank{"Subtitles"}},language=lang.lowercase(),kind="subtitles",url=u,isDefault=tracks.isEmpty())} }
            }
        }
        if(variants.isNotEmpty()){val idx=variants.indices.maxByOrNull{variants[it].bandwidth}?:0;variants[idx]=variants[idx].copy(isDefault=true)}
        val source=MediaSource(label="DASH auto",url=pageUrl,mimeType="application/dash+xml",protocol="dash",isDefault=true)
        return EngineResolveResult(handled=true,success=true,sources=listOf(source),variants=variants,audioTracks=audio,tracks=tracks)
    }
    private fun findBaseUrl(e:Element):String{val nodes=e.getElementsByTagNameNS("*","BaseURL");return if(nodes.length>0)nodes.item(0).textContent.trim() else ""}
}
