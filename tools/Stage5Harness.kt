import pro.noodle.mobile.core.catalog.*
import pro.noodle.mobile.core.studio.AdapterStudioCore
import pro.noodle.mobile.core.diagnostics.DiagnosticsSanitizer
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

private data class T(val name:String,val ok:Boolean,val detail:String="")
private val tests=mutableListOf<T>()
private fun ck(name:String,ok:Boolean,detail:String=""){tests+=T(name,ok,detail);println("${if(ok)"PASS" else "FAIL"} | $name${if(detail.isBlank())"" else " | $detail"}")}
private fun hex(bytes:ByteArray)=bytes.joinToString(""){"%02x".format(it)}
private fun modulusHex(k:RSAPublicKey):String{k.modulus.toByteArray().let { raw -> return hex(if(raw.size>1&&raw[0].toInt()==0) raw.copyOfRange(1,raw.size) else raw) }}

fun main(){
    val pair=KeyPairGenerator.getInstance("RSA").apply{initialize(2048)}.generateKeyPair()
    val pub=pair.public as RSAPublicKey
    val key=TrustedCatalogKey("harness-key", exponent=pub.publicExponent.toLong(), modulusHex=modulusHex(pub))
    val kv=TrustedKeyCodec.validate(key)
    ck("trusted RSA-2048 key accepted",kv.valid,kv.errorCode)
    ck("short RSA key rejected",!TrustedKeyCodec.validate(key.copy(modulusHex="aa".repeat(128))).valid)
    ck("leading-zero fake RSA-2048 rejected",!TrustedKeyCodec.validate(key.copy(modulusHex="00".repeat(256)+"01")).valid)
    ck("bad key id rejected",!TrustedKeyCodec.validate(key.copy(keyId="!x")).valid)
    ck("bad exponent rejected",!TrustedKeyCodec.validate(key.copy(exponent=17)).valid)
    val registry=TrustedKeyCodec.renderRegistry(listOf(key))
    val parsed=TrustedKeyCodec.parseRegistry(registry).getOrThrow()
    ck("trusted key registry round-trip",parsed.size==1&&parsed[0].keyId==key.keyId)

    val payload="""{
      "format_version":1,
      "name":"Harness Catalog",
      "publisher":"Harness",
      "entries":[
        {"adapter_id":"demo.example","name":"Demo","version":"1.2.0","publisher":"Harness","description":"ok","package_url":"https://cdn.example/demo.zip","sha256":"${"a".repeat(64)}","tags":["video","stable"],"min_app_version":"1.9.0"},
        {"adapter_id":"bad id","name":"Bad","version":"x","publisher":"Harness","package_url":"http://bad.example/x.zip","sha256":"x"}
      ]
    }""".trimIndent().toByteArray()
    val signature=Signature.getInstance("SHA256withRSA").run{initSign(pair.private);update(payload);sign()}
    val envelope="""{"format_version":1,"key_id":"harness-key","payload":"${Base64.getEncoder().encodeToString(payload)}","signature":"${Base64.getEncoder().encodeToString(signature)}"}"""
    val loaded=CatalogCodec.loadEnvelope(envelope,mapOf(key.keyId to key))
    ck("signed catalog verifies",loaded.success,loaded.errorCode)
    ck("invalid catalog entry filtered",loaded.catalog?.entries?.size==1&&loaded.catalog?.rejectedEntries==1,loaded.catalog.toString())
    ck("catalog compatibility evaluated",loaded.catalog?.entries?.firstOrNull()?.compatible==true)
    ck("untrusted key rejected",CatalogCodec.loadEnvelope(envelope,emptyMap()).errorCode=="CATALOG_KEY_UNTRUSTED")
    val tamperedPayload=payload.copyOf().also{it[it.lastIndex]=(it[it.lastIndex].toInt() xor 1).toByte()}
    val tampered="""{"format_version":1,"key_id":"harness-key","payload":"${Base64.getEncoder().encodeToString(tamperedPayload)}","signature":"${Base64.getEncoder().encodeToString(signature)}"}"""
    ck("tampered payload rejected",CatalogCodec.loadEnvelope(tampered,mapOf(key.keyId to key)).errorCode=="CATALOG_SIGNATURE_INVALID")
    val badSig=signature.copyOf().also{it[0]=(it[0].toInt() xor 1).toByte()}
    val badEnvelope="""{"format_version":1,"key_id":"harness-key","payload":"${Base64.getEncoder().encodeToString(payload)}","signature":"${Base64.getEncoder().encodeToString(badSig)}"}"""
    ck("tampered signature rejected",CatalogCodec.loadEnvelope(badEnvelope,mapOf(key.keyId to key)).errorCode=="CATALOG_SIGNATURE_INVALID")

    val wpHtml="""<html><link href='/wp-content/theme.css'><script>window.api='/wp-json/'</script></html>"""
    val wp=AdapterStudioCore.analyze("https://studio.example",wpHtml).getOrThrow()
    ck("Adapter Studio detects WordPress",wp.engine=="wordpress_rest",wp.engine)
    ck("Studio WordPress draft validates",AdapterStudioCore.validate(wp).isValid,AdapterStudioCore.validate(wp).errorCode)
    val declHtml="""<article class='video-card'><a href='/v/1'>One</a><img data-src='/1.jpg'></article><article class='video-card'><a href='/v/2'>Two</a><img data-src='/2.jpg'></article>"""
    val decl=AdapterStudioCore.analyze("https://catalog.example/path",declHtml).getOrThrow()
    ck("Adapter Studio declarative fallback",decl.engine=="declarative_catalog",decl.engine)
    ck("Studio repeated selector learned",decl.itemSelector==".video-card",decl.itemSelector)
    ck("Studio data-src learned",decl.thumbnailAttribute=="data-src",decl.thumbnailAttribute)
    ck("Studio declarative draft validates",AdapterStudioCore.validate(decl).isValid,AdapterStudioCore.validate(decl).errorCode)
    ck("Studio rejects HTTP",AdapterStudioCore.analyze("http://catalog.example",declHtml).isFailure)
    ck("Studio defaults JS off",AdapterStudioCore.toAdapterJson(decl).contains("\"allow_javascript\": false"))
    ck("Studio repair conservative",AdapterStudioCore.toAdapterJson(decl).contains("\"mode\": \"conservative\""))

    val secret="""Authorization: Bearer abc123
Cookie: SID=deadbeef
token=hello access_token=world password:qwerty https://x.test/?api_key=secret&x=1"""
    val clean=DiagnosticsSanitizer.sanitize(secret)
    ck("diagnostics Authorization redacted",!clean.contains("abc123")&&clean.contains("Authorization: <redacted>"),clean)
    ck("diagnostics Cookie redacted",!clean.contains("deadbeef"),clean)
    ck("diagnostics token redacted",!clean.contains("hello")&&!clean.contains("world"),clean)
    ck("diagnostics password redacted",!clean.contains("qwerty"),clean)
    ck("diagnostics query secret redacted",!clean.contains("api_key=secret")&&clean.contains("api_key=<redacted>"),clean)

    val failed=tests.filterNot{it.ok}
    println("SUMMARY ${tests.size-failed.size}/${tests.size} passed")
    if(failed.isNotEmpty()) error("Stage5 harness failed: ${failed.joinToString{it.name}}")
}
