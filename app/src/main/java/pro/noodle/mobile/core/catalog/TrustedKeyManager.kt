package pro.noodle.mobile.core.catalog

import android.content.Context
import java.io.File

class TrustedKeyManager(context: Context) {
    private val app = context.applicationContext
    private val userFile = File(app.filesDir, "config/catalog_trusted_keys.json")

    fun list(): List<TrustedCatalogKey> {
        val builtIn = runCatching { app.assets.open("catalog/trusted_keys.json").bufferedReader().use { TrustedKeyCodec.parseRegistry(it.readText(), builtIn = true).getOrThrow() } }.getOrDefault(emptyList())
        val user = if (userFile.isFile) TrustedKeyCodec.parseRegistry(userFile.readText(Charsets.UTF_8), builtIn = false).getOrDefault(emptyList()) else emptyList()
        val builtinIds = builtIn.map { it.keyId.lowercase() }.toSet()
        return (builtIn + user.filter { it.keyId.lowercase() !in builtinIds }).sortedBy { it.keyId.lowercase() }
    }

    fun asMap(): Map<String, TrustedCatalogKey> = list().associateBy { it.keyId }

    fun importRegistry(text: String): TrustedKeyValidationResult {
        val incoming = TrustedKeyCodec.parseRegistry(text, builtIn = false).getOrElse { return TrustedKeyValidationResult(false, "CATALOG_KEYS_IMPORT_INVALID", it.message.orEmpty()) }
        val current = list()
        val builtIns = current.filter { it.builtIn }
        val builtInIds = builtIns.map { it.keyId.lowercase() }.toSet()
        if (incoming.any { it.keyId.lowercase() in builtInIds }) return TrustedKeyValidationResult(false, "CATALOG_KEY_BUILTIN_IMMUTABLE", "Встроенный trusted key нельзя заменить импортом.")
        val merged = (current.filterNot { it.builtIn }.associateBy { it.keyId.lowercase() }.toMutableMap()).apply { incoming.forEach { put(it.keyId.lowercase(), it) } }.values.toList()
        persist(merged)
        return TrustedKeyValidationResult(true, message = "Импортировано ключей: ${incoming.size}")
    }

    fun remove(keyId: String): TrustedKeyValidationResult {
        val current = list(); val found = current.firstOrNull { it.keyId.equals(keyId, true) } ?: return TrustedKeyValidationResult(false, "CATALOG_KEY_NOT_FOUND", "Trusted key не найден.")
        if (found.builtIn) return TrustedKeyValidationResult(false, "CATALOG_KEY_BUILTIN_IMMUTABLE", "Встроенный trusted key нельзя удалить.")
        persist(current.filter { !it.builtIn && !it.keyId.equals(keyId, true) })
        return TrustedKeyValidationResult(true, message = "Trusted key удалён.")
    }

    fun exportUserRegistry(): String = TrustedKeyCodec.renderRegistry(list().filterNot { it.builtIn })

    private fun persist(keys: List<TrustedCatalogKey>) {
        userFile.parentFile?.mkdirs()
        val tmp = File(userFile.parentFile, userFile.name + ".tmp")
        tmp.writeText(TrustedKeyCodec.renderRegistry(keys.map { it.copy(builtIn = false) }), Charsets.UTF_8)
        if (userFile.exists()) userFile.delete()
        if (!tmp.renameTo(userFile)) { userFile.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8); tmp.delete() }
    }
}
