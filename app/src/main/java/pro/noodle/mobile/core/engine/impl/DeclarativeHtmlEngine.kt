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

    private fun templateUrl(r:EngineSearchRequest):String{if(r.searchUrlTemplate.isBlank())return "";val page=(r.page+1).coerceAtLeast(1).toString();val u=r.searchUrlTemplate.replace("{base_url}",r.baseUrl,true).let{EngineParsing.fillQuery(it,r.query)}.replace("{page}",page,true);return EngineParsing.absolute(u,r.baseUrl)}
    private data class Selector(val tag:String,val clazz:String)
    /** CSS-lite: `tag`, `tag.class`, `.class`, `tag#id` (id ignored). Tag may be omitted. */
    private fun parseSelector(s:String):Selector?{
        val t=s.trim().substringBefore('#');if(t.isBlank())return null
        val parts=t.split('.',limit=2);val tag=parts[0].trim();val clazz=parts.getOrElse(1){""}.trim()
        if(tag.isNotEmpty()&&!tag.matches(Regex("[A-Za-z][A-Za-z0-9_-]*")))return null
        if(tag.isEmpty()&&clazz.isEmpty())return null
        return Selector(tag,clazz)
    }
    private fun classMatches(attrs:String, clazz:String):Boolean{if(clazz.isBlank())return true;val c=EngineParsing.attr("<x $attrs>","class");return c.split(Regex("\\s+")).any{it.equals(clazz,true)}}
    private val voidTags=setOf("img","source","input","br","hr","meta","link","track","wbr","area","base","col","embed","param")
    private fun openTags(html:String,s:Selector):Sequence<MatchResult>{
        val name=if(s.tag.isBlank())"[A-Za-z][A-Za-z0-9-]*" else Regex.escape(s.tag)
        return Regex("<($name)\\b([^>]*)>",setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL)).findAll(html).filter{classMatches(it.groupValues[2],s.clazz)}
    }
    /** Returns the element starting at [open] including its balanced closing tag. */
    private fun element(html:String,open:MatchResult):String{
        val tag=open.groupValues[1].lowercase()
        if(tag in voidTags||open.value.endsWith("/>"))return open.value
        val re=Regex("<(/?)${Regex.escape(tag)}\\b[^>]*>",RegexOption.IGNORE_CASE)
        var depth=1;var pos=open.range.last+1
        while(depth>0){
            val m=re.find(html,pos)?:return html.substring(open.range.first,minOf(html.length,open.range.first+6000))
            depth+=if(m.groupValues[1]=="/")-1 else if(m.value.endsWith("/>"))0 else 1
            pos=m.range.last+1
        }
        return html.substring(open.range.first,pos)
    }
    private fun selectBlocks(html:String,selector:String):List<String>{
        val s=parseSelector(selector)?:return emptyList()
        val out=mutableListOf<String>();var lastEnd=-1
        for(m in openTags(html,s)){ if(m.range.first<lastEnd)continue; val e=element(html,m); out+=e; lastEnd=m.range.first+e.length }
        return out
    }
    private fun selectFirstTag(html:String,selector:String):String{val s=parseSelector(selector)?:return "";val m=openTags(html,s).firstOrNull()?:return "";return element(html,m)}
    private fun extractText(html:String,selector:String):String{if(selector.isBlank())return "";val s=parseSelector(selector)?:return "";val m=openTags(html,s).firstOrNull()?:return "";return EngineParsing.stripTags(element(html,m))}
}
