package pro.noodle.mobile.core.catalog

import pro.noodle.mobile.core.json.*
import java.math.BigInteger

object TrustedKeyCodec {
    private val keyIdRegex = Regex("^[A-Za-z0-9._-]{3,128}$")
    private val hexRegex = Regex("^[0-9a-fA-F]+$")

    fun validate(key: TrustedCatalogKey): TrustedKeyValidationResult {
        if (!keyIdRegex.matches(key.keyId)) return bad("CATALOG_KEY_ID_INVALID", "key_id должен содержать 3–128 символов A-Z, a-z, 0-9, ._-.")
        if (!key.algorithm.equals("rsa-sha256", true)) return bad("CATALOG_KEY_ALGORITHM_UNSUPPORTED", "Поддерживается только rsa-sha256.")
        val hex = key.modulusHex.trim()
        if (hex.length % 2 != 0 || !hexRegex.matches(hex)) return bad("CATALOG_KEY_MODULUS_INVALID", "RSA modulus должен быть корректной hex-строкой.")
        val bits = runCatching { BigInteger(hex, 16).bitLength() }.getOrDefault(0)
        if (bits < 2048) return bad("CATALOG_KEY_RSA_TOO_SMALL", "RSA modulus должен иметь минимум 2048 реальных бит (получено $bits).")
        if (key.exponent !in setOf(3L, 65537L)) return bad("CATALOG_KEY_EXPONENT_INVALID", "Поддерживается exponent 3 или 65537.")
        return TrustedKeyValidationResult(true)
    }

    fun parseRegistry(text: String, builtIn: Boolean = false): Result<List<TrustedCatalogKey>> = runCatching {
        val root = MiniJson.parse(text) as? JsonValue.Obj ?: error("trusted_keys root must be object")
        require(root.int("format_version") == 1) { "trusted_keys format_version must be 1" }
        val arr = root.arr("keys") ?: error("trusted_keys.keys missing")
        arr.values.map { value ->
            val o = value as? JsonValue.Obj ?: error("trusted key must be object")
            val key = TrustedCatalogKey(
                keyId = o.string("key_id").orEmpty(),
                algorithm = o.string("algorithm") ?: "rsa-sha256",
                exponent = (o.values["exponent"] as? JsonValue.Num)?.value?.toLong() ?: 65537L,
                modulusHex = o.string("modulus_hex").orEmpty(),
                builtIn = builtIn,
            )
            val vr = validate(key); require(vr.valid) { "${vr.errorCode}: ${vr.message}" }
            key
        }.distinctBy { it.keyId.lowercase() }
    }

    fun renderRegistry(keys: List<TrustedCatalogKey>): String = JsonValue.Obj(linkedMapOf(
        "format_version" to JsonValue.Num(1.0),
        "keys" to JsonValue.Arr(keys.map { key -> JsonValue.Obj(linkedMapOf(
            "key_id" to JsonValue.Str(key.keyId),
            "algorithm" to JsonValue.Str("rsa-sha256"),
            "exponent" to JsonValue.Num(key.exponent.toDouble()),
            "modulus_hex" to JsonValue.Str(key.modulusHex.lowercase()),
        )) }),
    )).toJson(pretty = true)

    private fun bad(code: String, msg: String) = TrustedKeyValidationResult(false, code, msg)
}
