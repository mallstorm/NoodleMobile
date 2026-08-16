package pro.noodle.mobile.core.catalog

data class TrustedCatalogKey(
    val keyId: String,
    val algorithm: String = "rsa-sha256",
    val exponent: Long = 65537,
    val modulusHex: String,
    val builtIn: Boolean = false,
)

data class CatalogEntry(
    val adapterId: String,
    val name: String,
    val version: String,
    val publisher: String,
    val description: String = "",
    val packageUrl: String,
    val sha256: String,
    val category: String = "",
    val homepage: String = "",
    val tags: List<String> = emptyList(),
    val minAppVersion: String = "",
    val maxAppVersion: String = "",
    val compatible: Boolean = true,
    val compatibilityStatus: String = "compatible",
)

data class VerifiedCatalog(
    val name: String,
    val publisher: String,
    val keyId: String,
    val entries: List<CatalogEntry>,
    val rejectedEntries: Int = 0,
)

data class CatalogLoadResult(
    val success: Boolean,
    val catalog: VerifiedCatalog? = null,
    val errorCode: String = "",
    val message: String = "",
)

data class TrustedKeyValidationResult(val valid: Boolean, val errorCode: String = "", val message: String = "")
