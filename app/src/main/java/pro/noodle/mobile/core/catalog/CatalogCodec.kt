package pro.noodle.mobile.core.catalog

import pro.noodle.mobile.core.json.*
import pro.noodle.mobile.core.source.AdapterCompatibility
import pro.noodle.mobile.core.version.SemanticVersion
import java.net.URI
import java.util.Base64

object CatalogCodec {
    private val idRegex = Regex("^[a-z0-9][a-z0-9._-]{0,127}$", RegexOption.IGNORE_CASE)
    private val tagRegex = Regex("^[A-Za-z0-9._-]{1,32}$")
    private val shaRegex = Regex("^[0-9a-fA-F]{64}$")

    fun loadEnvelope(text: String, trusted: Map<String, TrustedCatalogKey>): CatalogLoadResult {
        val root = try { MiniJson.parse(text) as? JsonValue.Obj ?: return fail("CATALOG_ENVELOPE_INVALID", "Каталог должен быть JSON-объектом.") }
        catch (e: Exception) { return fail("CATALOG_ENVELOPE_INVALID", e.message.orEmpty()) }
        if (root.int("format_version") != 1) return fail("CATALOG_FORMAT_UNSUPPORTED", "Поддерживается format_version=1.")
        val keyId = root.string("key_id").orEmpty().trim()
        val key = trusted.entries.firstOrNull { it.key.equals(keyId, true) }?.value ?: return fail("CATALOG_KEY_UNTRUSTED", "Ключ $keyId отсутствует в Trusted Keys.")
        val payload = runCatching { Base64.getDecoder().decode(root.string("payload").orEmpty()) }.getOrElse { return fail("CATALOG_PAYLOAD_BASE64_INVALID", "Некорректный payload base64.") }
        val sig = runCatching { Base64.getDecoder().decode(root.string("signature").orEmpty()) }.getOrElse { return fail("CATALOG_SIGNATURE_BASE64_INVALID", "Некорректная signature base64.") }
        if (!CatalogSignatureVerifier.verify(payload, sig, key)) return fail("CATALOG_SIGNATURE_INVALID", "RSA-SHA256 подпись каталога не прошла проверку.")
        val payloadObj = try { MiniJson.parse(payload.toString(Charsets.UTF_8)) as? JsonValue.Obj ?: return fail("CATALOG_PAYLOAD_INVALID", "Payload должен быть JSON-объектом.") }
        catch (e: Exception) { return fail("CATALOG_PAYLOAD_INVALID", e.message.orEmpty()) }
        if (payloadObj.int("format_version") != 1) return fail("CATALOG_PAYLOAD_FORMAT_UNSUPPORTED", "Payload format_version должен быть 1.")
        val entriesRaw = payloadObj.arr("entries") ?: return fail("CATALOG_ENTRIES_MISSING", "Payload entries отсутствует.")
        var rejected = 0
        val entries = entriesRaw.values.mapNotNull { value -> parseEntry(value as? JsonValue.Obj).also { if (it == null) rejected++ } }
        return CatalogLoadResult(true, VerifiedCatalog(
            name = payloadObj.string("name").orEmpty(),
            publisher = payloadObj.string("publisher").orEmpty(),
            keyId = keyId,
            entries = entries,
            rejectedEntries = rejected,
        ))
    }

    private fun parseEntry(o: JsonValue.Obj?): CatalogEntry? {
        o ?: return null
        val id = o.string("adapter_id").orEmpty().trim()
        val name = o.string("name").orEmpty().trim()
        val version = o.string("version").orEmpty().trim()
        val publisher = o.string("publisher").orEmpty().trim()
        val packageUrl = o.string("package_url").orEmpty().trim()
        val sha = o.string("sha256").orEmpty().trim().lowercase()
        if (!idRegex.matches(id) || name.isBlank() || publisher.isBlank() || SemanticVersion.parseOrNull(version) == null || !shaRegex.matches(sha)) return null
        val uri = runCatching { URI(packageUrl) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank()) return null
        val min = o.string("min_app_version").orEmpty().trim(); val max = o.string("max_app_version").orEmpty().trim()
        val compatibility = AdapterCompatibility.evaluate(min, max)
        val tags = o.arr("tags")?.strings().orEmpty().filter { tagRegex.matches(it) }.distinct().take(32)
        return CatalogEntry(id, name, version, publisher, o.string("description").orEmpty(), packageUrl, sha, o.string("category").orEmpty(), o.string("homepage").orEmpty(), tags, min, max, compatibility.compatible, compatibility.status)
    }

    private fun fail(code: String, message: String) = CatalogLoadResult(false, errorCode = code, message = message)
}
