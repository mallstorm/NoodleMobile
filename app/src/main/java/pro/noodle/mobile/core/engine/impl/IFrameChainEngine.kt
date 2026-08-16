package pro.noodle.mobile.core.engine.impl
import pro.noodle.mobile.core.engine.runtime.*
class IFrameChainEngine:SourceEngine{
    override val id="iframe_chain"; override val aliases=setOf("iframe","iframes")
    override fun resolvePlayback(text:String,pageUrl:String):EngineResolveResult{
        if(text.isBlank()) return EngineResolveResult(handled=true,errorCode="IFRAME_EMPTY",errorMessage="Страница пуста.")
        val seen=linkedSetOf<String>()
        Regex("<iframe\\b[^>]*>",setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL)).findAll(text).forEach{m-> val u=EngineParsing.absolute(EngineParsing.attr(m.value,"src"),pageUrl); if(u.startsWith("http://",true)||u.startsWith("https://",true))seen+=u }
        return if(seen.isNotEmpty()) EngineResolveResult(handled=true,success=true,playerPageUrl=seen.first(),followUpPages=seen.toList()) else EngineResolveResult(handled=true,errorCode="IFRAME_NOT_FOUND",errorMessage="iframe не найден.")
    }
}
