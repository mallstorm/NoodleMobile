package pro.noodle.mobile.core.engine.impl
import pro.noodle.mobile.core.engine.runtime.*
import pro.noodle.mobile.core.json.*
class OEmbedEngine:SourceEngine{
    override val id="oembed_provider"; override val aliases=setOf("oembed")
    override fun resolvePlayback(text:String,pageUrl:String):EngineResolveResult{
        val t=text.trim(); if(t.isBlank())return EngineResolveResult(handled=true,errorCode="OEMBED_EMPTY",errorMessage="Пустой ответ.")
        if(t.startsWith("{")){
            return try{
                val o=MiniJson.parse(t) as? JsonValue.Obj ?: return EngineResolveResult(handled=true,errorCode="OEMBED_JSON_INVALID")
                val html=o.string("html").orEmpty(); val iframe=EngineParsing.first(html,"<iframe[^>]+src=[\"']([^\"']+)[\"']")
                val player=EngineParsing.absolute(iframe,pageUrl)
                val url=o.string("url").orEmpty(); val source=EngineParsing.absolute(url,pageUrl)
                EngineResolveResult(handled=true,success=player.isNotBlank()||source.isNotBlank(),title=o.string("title").orEmpty(),thumbnailUrl=o.string("thumbnail_url").orEmpty(),playerPageUrl=player,sources=if(source.isNotBlank()) listOf(pro.noodle.mobile.core.model.MediaSource(label="oEmbed",url=source,protocol=EngineParsing.protocol(source),isDefault=true)) else emptyList(),followUpPages=listOfNotNull(player.takeIf{it.isNotBlank()}),errorCode=if(player.isBlank()&&source.isBlank())"OEMBED_MEDIA_NOT_FOUND" else "")
            }catch(e:Exception){EngineResolveResult(handled=true,errorCode="OEMBED_JSON_INVALID",errorMessage=e.message.orEmpty())}
        }
        val endpoint=Regex("<link\\b[^>]*>",setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL)).findAll(t).mapNotNull{m-> val tag=m.value; val rel=EngineParsing.attr(tag,"rel");val type=EngineParsing.attr(tag,"type");if(rel.equals("alternate",true)&&type.contains("oembed",true))EngineParsing.absolute(EngineParsing.attr(tag,"href"),pageUrl) else null}.firstOrNull().orEmpty()
        return if(endpoint.isNotBlank())EngineResolveResult(handled=true,success=true,followUpPages=listOf(endpoint)) else EngineResolveResult(handled=true,errorCode="OEMBED_ENDPOINT_NOT_FOUND",errorMessage="oEmbed endpoint не найден.")
    }
}
