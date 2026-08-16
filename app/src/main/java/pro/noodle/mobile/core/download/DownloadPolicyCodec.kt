package pro.noodle.mobile.core.download

import pro.noodle.mobile.core.json.*
import pro.noodle.mobile.core.network.NetworkPolicy

data class DownloadPolicyEnvelope(val sourceKey:String,val policy:NetworkPolicy)

/**
 * Security boundary persisted in DownloadRequest.data. Decode is intentionally fail-closed:
 * an adaptive request without source identity/allowed hosts must never become an unrestricted client.
 */
object DownloadPolicyCodec {
    fun encode(sourceKey:String,policy:NetworkPolicy):ByteArray = JsonValue.Obj(linkedMapOf(
        "source_key" to JsonValue.Str(sourceKey),
        "allowed_hosts" to JsonValue.Arr(policy.allowedHosts.map { JsonValue.Str(it) }),
        "allow_http" to JsonValue.Bool(policy.allowHttp),
        "allow_cookies" to JsonValue.Bool(policy.allowCookies),
        "max_redirects" to JsonValue.Num(policy.maxRedirects.toDouble()),
    )).toJson().toByteArray(Charsets.UTF_8)

    fun decode(bytes:ByteArray):DownloadPolicyEnvelope? = runCatching {
        val o = MiniJson.parse(bytes.toString(Charsets.UTF_8)) as JsonValue.Obj
        val sourceKey = o.string("source_key").orEmpty().trim()
        val hosts = o.arr("allowed_hosts")?.strings().orEmpty().map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()
        require(sourceKey.isNotBlank()) { "source_key missing" }
        require(hosts.isNotEmpty()) { "allowed_hosts missing" }
        DownloadPolicyEnvelope(
            sourceKey,
            NetworkPolicy(
                allowedHosts = hosts,
                allowHttp = o.bool("allow_http") ?: false,
                allowCookies = o.bool("allow_cookies") ?: false,
                maxResponseBytes = 16 * 1024 * 1024,
                maxRedirects = (o.int("max_redirects") ?: 5).coerceIn(0, 10),
            ),
        )
    }.getOrNull()
}
