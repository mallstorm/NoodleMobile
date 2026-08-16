package pro.noodle.mobile.core.engine.impl

import pro.noodle.mobile.core.engine.runtime.*

class DeclarativeHtmlEngine:SourceEngine{
    override val id="declarative_catalog";override val aliases=setOf("declarative","declarative_html","css_catalog")
    override fun buildSearchUrl(request:EngineSearchRequest):String = templateUrl(request)
    override fun parseSearch(text:String,request:EngineSearchRequest):List<EngineSearchItem>{
        if(text.isBlank()||request.itemSelector.isBlank())return emptyList()
        return selectBlocks(text,request.itemSelector).mapNotNull{block->
            val urlNode=selectFirstTag(block,request.urlSelector.ifBlank{"a"})
            val watchRaw=EngineParsing.attr(urlNode,"href").ifBlank{EngineParsing.stripTags(urlNode)}
            val watch=EngineParsing.absolute(watchRaw,request.baseUrl)
            if(watch.isBlank())return@mapNotNull null
            val title=extractText(block,request.titleSelector).ifBlank{EngineParsing.attr(urlNode,"title")}.ifBlank{watch.substringBefore('?').trimEnd('/').substringAfterLast('/').ifBlank{"item"}}
            val imgNode=selectFirstTag(block,request.thumbnailSelector.ifBlank{"img"})
            val attr=request.thumbnailAttribute.ifBlank{"src"}
            val thumb=EngineParsing.absolute(EngineParsing.attr(imgNode,attr).ifBlank{EngineParsing.attr(imgNode,"src")},request.baseUrl)
            val remote=watch.substringBefore('?').trimEnd('/').substringAfterLast('/').ifBlank{watch}
            EngineSearchItem(remote,watch,title,thumb,extractText(block,request.durationSelector),extractText(block,request.viewsSelector))
        }.take(request.maxItems.coerceAtLeast(1))
    }

    private fun templateUrl(r:EngineSearchRequest):String{if(r.searchUrlTemplate.isBlank())return "";val page=(r.page+1).coerceAtLeast(1).toString();val u=r.searchUrlTemplate.replace("{base_url}",r.baseUrl,true).replace("{query}",EngineParsing.encodedQuery(r.query),true).replace("{page}",page,true);return EngineParsing.absolute(u,r.baseUrl)}
    private data class Selector(val tag:String,val clazz:String)
    private fun parseSelector(s:String):Selector?{val t=s.trim();if(t.isBlank())return null;val parts=t.split('.',limit=2);val tag=parts[0];if(!tag.matches(Regex("[A-Za-z][A-Za-z0-9_-]*")))return null;return Selector(tag,parts.getOrElse(1){""})}
    private fun classMatches(attrs:String, clazz:String):Boolean{if(clazz.isBlank())return true;val c=EngineParsing.attr("<$attrs>","class");return c.split(Regex("\\s+")).any{it.equals(clazz,true)}}
    private fun selectBlocks(html:String,selector:String):List<String>{val s=parseSelector(selector)?:return emptyList();val paired=Regex("<${Regex.escape(s.tag)}\\b([^>]*)>([\\s\\S]*?)</${Regex.escape(s.tag)}>",setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL));return paired.findAll(html).filter{classMatches(it.groupValues[1],s.clazz)}.map{it.value}.toList()}
    private fun selectFirstTag(html:String,selector:String):String{val s=parseSelector(selector)?:return "";val re=Regex("<${Regex.escape(s.tag)}\\b([^>]*)>(?:[\\s\\S]*?</${Regex.escape(s.tag)}>)?|<${Regex.escape(s.tag)}\\b([^>]*)/?>",setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL));return re.findAll(html).firstOrNull{classMatches(it.groupValues.getOrElse(1){""}.ifBlank{it.groupValues.getOrElse(2){""}},s.clazz)}?.value.orEmpty()}
    private fun extractText(html:String,selector:String):String{if(selector.isBlank())return "";val s=parseSelector(selector)?:return "";val re=Regex("<${Regex.escape(s.tag)}\\b([^>]*)>([\\s\\S]*?)</${Regex.escape(s.tag)}>",setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL));val m=re.findAll(html).firstOrNull{classMatches(it.groupValues[1],s.clazz)}?:return "";return EngineParsing.stripTags(m.groupValues[2])}
}
