package pro.noodle.mobile.core.engine.impl

import pro.noodle.mobile.core.engine.runtime.*
import pro.noodle.mobile.core.json.*

class JsonApiEngine:SourceEngine{
    override val id="json_api_catalog";override val aliases=setOf("json_api","api_json")
    override fun buildSearchUrl(request:EngineSearchRequest):String{if(request.searchUrlTemplate.isBlank())return "";val p=(request.page+1).coerceAtLeast(1);return EngineParsing.absolute(request.searchUrlTemplate.replace("{base_url}",request.baseUrl,true).replace("{query}",EngineParsing.encodedQuery(request.query),true).replace("{page}",p.toString(),true),request.baseUrl)}
    override fun parseSearch(text:String,request:EngineSearchRequest):List<EngineSearchItem>{val root=try{MiniJson.parse(text)}catch(_:Exception){return emptyList()};val node=resolve(root,request.itemsPath);val arr=(node as? JsonValue.Arr)?.values?:return emptyList();return arr.mapNotNull{item->val id=value(item,request.idPath);val title=value(item,request.titlePath);val url=EngineParsing.absolute(value(item,request.urlPath),request.baseUrl);if(url.isBlank())return@mapNotNull null;EngineSearchItem(id.ifBlank{url.substringBefore('?').trimEnd('/').substringAfterLast('/')},url,title.ifBlank{id},EngineParsing.absolute(value(item,request.thumbnailPath),request.baseUrl),value(item,request.durationPath),value(item,request.viewsPath))}.take(request.maxItems.coerceAtLeast(1))}
    private fun value(root:JsonValue,path:String):String=when(val v=resolve(root,path)){is JsonValue.Str->v.value;is JsonValue.Num->if(v.value%1.0==0.0)v.value.toLong().toString() else v.value.toString();is JsonValue.Bool->v.value.toString();else->""}
    private fun resolve(root:JsonValue,path:String):JsonValue?{if(path.isBlank()||path=="$")return root;var cur:JsonValue=root;val tokens=path.trim().removePrefix("$").trimStart('.').split('.').filter{it.isNotBlank()};for(token0 in tokens){var token=token0;val idxs=mutableListOf<Int>();val key=token.substringBefore('[');if(key.isNotBlank()){cur=(cur as? JsonValue.Obj)?.values?.get(key)?:return null};Regex("\\[(\\d+)]").findAll(token).forEach{idxs+=it.groupValues[1].toInt()};for(idx in idxs){cur=(cur as? JsonValue.Arr)?.values?.getOrNull(idx)?:return null}};return cur}
}
