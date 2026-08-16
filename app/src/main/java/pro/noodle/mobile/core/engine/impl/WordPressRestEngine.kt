package pro.noodle.mobile.core.engine.impl

import pro.noodle.mobile.core.engine.runtime.*
import pro.noodle.mobile.core.json.*

class WordPressRestEngine:SourceEngine{
    override val id="wordpress_rest";override val aliases=setOf("wordpress","wp_rest")
    override fun buildSearchUrl(request:EngineSearchRequest):String{if(request.baseUrl.isBlank()||request.query.isBlank())return "";val page=(request.page+1).coerceAtLeast(1);val per=request.maxItems.takeIf{it>0}?:25;return "${request.baseUrl.trimEnd('/')}/wp-json/wp/v2/search?search=${EngineParsing.encodedQuery(request.query)}&page=$page&per_page=$per"}
    override fun parseSearch(text:String,request:EngineSearchRequest):List<EngineSearchItem>{val arr=try{MiniJson.parse(text) as? JsonValue.Arr}catch(_:Exception){null}?:return emptyList();return arr.values.mapNotNull{it as? JsonValue.Obj}.mapNotNull{o->val id=when(val v=o.values["id"]){is JsonValue.Str->v.value;is JsonValue.Num->v.value.toLong().toString();else->""};val url=EngineParsing.absolute(o.string("url").orEmpty(),request.baseUrl);if(url.isBlank())return@mapNotNull null;EngineSearchItem(id.ifBlank{url.substringAfterLast('/')},url,o.string("title").orEmpty().ifBlank{id})}.take(request.maxItems.coerceAtLeast(1))}
}
