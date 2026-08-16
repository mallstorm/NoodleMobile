package pro.noodle.mobile.core.source

import pro.noodle.mobile.core.adapter.AdapterSchemaParser
import pro.noodle.mobile.core.adapter.AdapterValidator
import pro.noodle.mobile.core.json.*
import pro.noodle.mobile.core.version.SemanticVersion
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale

private const val PACKAGE_FILE_NAME = "snp-package.json"

enum class AdapterTrustStatus(val wireName: String) {
    INVALID("invalid"),
    BUNDLED_TRUSTED("bundled_trusted"),
    INTEGRITY_VERIFIED_UNSIGNED("integrity_verified_unsigned"),
    UNSIGNED("unsigned"),
    SIGNATURE_PRESENT_UNVERIFIED("signature_present_unverified"),
}

data class AdapterPackageInfo(
    val valid: Boolean,
    val adapterId: String = "",
    val version: String = "",
    val publisher: String = "",
    val trustStatus: AdapterTrustStatus = AdapterTrustStatus.INVALID,
    val errorCode: String = "",
    val errorMessage: String = "",
    val manifestPath: String = "",
)

data class PackageManifestEntry(val path: String, val sha256: String, val size: Long)

object AdapterPackageManager {
    fun createManifest(adapterDir: File, adapterId: String, version: String, publisher: String): File {
        require(adapterDir.isDirectory) { "Каталог адаптера не найден." }
        require(SemanticVersion.parseOrNull(version) != null) { "Некорректная версия SNP: $version" }
        val files = adapterDir.walkTopDown()
            .filter { it.isFile && it.name != PACKAGE_FILE_NAME }
            .map { file ->
                val rel = file.relativeTo(adapterDir).invariantSeparatorsPath
                require(isSafeRelativePath(rel)) { "Небезопасный путь в SNP: $rel" }
                PackageManifestEntry(rel, sha256(file), file.length())
            }
            .sortedBy { it.path.lowercase(Locale.ROOT) }
            .toList()
        val root = linkedMapOf<String, JsonValue>(
            "format_version" to JsonValue.Num(1.0),
            "adapter_id" to JsonValue.Str(adapterId.trim().lowercase()),
            "version" to JsonValue.Str(version.trim()),
            "publisher" to JsonValue.Str(publisher.trim()),
            "integrity_algorithm" to JsonValue.Str("sha256"),
            "files" to JsonValue.Arr(files.map { entry ->
                JsonValue.Obj(linkedMapOf(
                    "path" to JsonValue.Str(entry.path),
                    "sha256" to JsonValue.Str(entry.sha256),
                    "size" to JsonValue.Num(entry.size.toDouble()),
                ))
            }),
            "signature" to JsonValue.Obj(linkedMapOf(
                "algorithm" to JsonValue.Str("none"),
                "key_id" to JsonValue.Str(""),
                "value" to JsonValue.Str(""),
            )),
        )
        return File(adapterDir, PACKAGE_FILE_NAME).also { it.writeText(JsonValue.Obj(root).toJson(pretty = true), Charsets.UTF_8) }
    }

    fun verifyDirectory(adapterDir: File, bundledRoot: File? = null): AdapterPackageInfo {
        if (!adapterDir.isDirectory) return fail("SNP_PACKAGE_DIR_MISSING", "Каталог пакета отсутствует.")
        val adapterFile = File(adapterDir, "adapter.json")
        if (!adapterFile.isFile) return fail("ADAPTER_FILE_MISSING", "adapter.json отсутствует.")
        val text = runCatching { adapterFile.readText(Charsets.UTF_8) }.getOrElse { return fail("ADAPTER_JSON_READ_FAILED", it.message ?: "Ошибка чтения adapter.json") }
        val validation = AdapterValidator.validate(text)
        if (!validation.isValid) return fail(validation.errorCode, validation.errorMessage)
        val d = validation.definition!!
        val manifest = File(adapterDir, PACKAGE_FILE_NAME)
        if (!manifest.isFile) return AdapterPackageInfo(true, d.id, d.adapterVersion, d.publisher, AdapterTrustStatus.UNSIGNED, manifestPath = manifest.path)

        val root = try { MiniJson.parse(manifest.readText(Charsets.UTF_8)) as? JsonValue.Obj ?: return fail("SNP_PACKAGE_MANIFEST_INVALID", "snp-package.json должен быть JSON-объектом.") }
        catch (e: Exception) { return fail("SNP_PACKAGE_MANIFEST_EXCEPTION", e.message ?: "Ошибка manifest") }

        if (root.int("format_version") != 1) return fail("SNP_PACKAGE_FORMAT_UNSUPPORTED", "Неподдерживаемая версия snp-package.json.")
        if (!root.string("integrity_algorithm").orEmpty().equals("sha256", true)) return fail("SNP_PACKAGE_INTEGRITY_UNSUPPORTED", "Поддерживается только integrity_algorithm=sha256.")
        if (!root.string("adapter_id").orEmpty().equals(d.id, true)) return fail("SNP_PACKAGE_ID_MISMATCH", "adapter_id manifest не совпадает с adapter.json.")
        val version = root.string("version").orEmpty()
        if (!version.equals(d.adapterVersion, true)) return fail("SNP_PACKAGE_VERSION_MISMATCH", "Версия manifest не совпадает с adapter_version.")
        if (SemanticVersion.parseOrNull(version) == null) return fail("SNP_PACKAGE_VERSION_INVALID", "Некорректная версия пакета.")
        val publisher = root.string("publisher") ?: d.publisher
        if (publisher.isNotBlank() && d.publisher.isNotBlank() && !publisher.equals(d.publisher, true)) return fail("SNP_PACKAGE_PUBLISHER_MISMATCH", "Publisher manifest не совпадает с adapter.json.")
        val arr = root.arr("files") ?: return fail("SNP_PACKAGE_FILES_MISSING", "В manifest отсутствует files.")
        if (arr.values.isEmpty()) return fail("SNP_PACKAGE_FILES_EMPTY", "Список files пуст.")

        val seen = hashSetOf<String>()
        var adapterListed = false
        for (value in arr.values) {
            val obj = value as? JsonValue.Obj ?: return fail("SNP_PACKAGE_FILE_INVALID", "Элемент files должен быть объектом.")
            val rel = obj.string("path").orEmpty().replace('\\', '/')
            val expected = obj.string("sha256").orEmpty().lowercase()
            val size = (obj.values["size"] as? JsonValue.Num)?.value?.toLong() ?: -1L
            if (!isSafeRelativePath(rel)) return fail("SNP_PACKAGE_PATH_UNSAFE", "Небезопасный путь в manifest: $rel")
            if (!Regex("^[0-9a-f]{64}$").matches(expected)) return fail("SNP_PACKAGE_HASH_INVALID", "Некорректный SHA-256 для $rel")
            val key = rel.lowercase(Locale.ROOT)
            if (!seen.add(key)) return fail("SNP_PACKAGE_FILE_DUPLICATE", "Дублирующийся путь: $rel")
            val file = resolveInside(adapterDir, rel) ?: return fail("SNP_PACKAGE_PATH_UNSAFE", "Путь выходит за пределы пакета: $rel")
            if (!file.isFile) return fail("SNP_PACKAGE_FILE_MISSING", "Файл отсутствует: $rel")
            if (file.length() != size) return fail("SNP_PACKAGE_SIZE_MISMATCH", "Размер не совпадает: $rel")
            if (!sha256(file).equals(expected, true)) return fail("SNP_PACKAGE_HASH_MISMATCH", "SHA-256 не совпадает: $rel")
            if (rel.equals("adapter.json", true)) adapterListed = true
        }
        if (!adapterListed) return fail("SNP_PACKAGE_ADAPTER_NOT_LISTED", "adapter.json должен быть перечислен в files.")

        val actual = adapterDir.walkTopDown().filter { it.isFile && it.name != PACKAGE_FILE_NAME }.map { it.relativeTo(adapterDir).invariantSeparatorsPath.lowercase(Locale.ROOT) }.toSet()
        val unlisted = actual.firstOrNull { it !in seen }
        if (unlisted != null) return fail("SNP_PACKAGE_UNLISTED_FILE", "Файл отсутствует в manifest: $unlisted")

        val signature = root.obj("signature")
        val signatureValue = signature?.string("value").orEmpty().trim()
        val bundled = bundledRoot?.let { isUnder(adapterDir, it) } == true
        val trust = when {
            bundled -> AdapterTrustStatus.BUNDLED_TRUSTED
            signatureValue.isNotEmpty() -> AdapterTrustStatus.SIGNATURE_PRESENT_UNVERIFIED
            else -> AdapterTrustStatus.INTEGRITY_VERIFIED_UNSIGNED
        }
        return AdapterPackageInfo(true, d.id, version, publisher, trust, manifestPath = manifest.path)
    }

    fun isSafeRelativePath(value: String): Boolean {
        val normalized = value.trim().replace('\\', '/')
        if (normalized.isEmpty() || normalized.startsWith('/') || normalized.contains(':')) return false
        if (normalized.split('/').any { it == ".." || it.isEmpty() }) return false
        return !normalized.startsWith("../")
    }

    fun resolveInside(root: File, relative: String): File? = runCatching {
        val rootCanonical = root.canonicalFile
        val out = File(rootCanonical, relative).canonicalFile
        if (out.path == rootCanonical.path || out.path.startsWith(rootCanonical.path + File.separator)) out else null
    }.getOrNull()

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun isUnder(file: File, root: File): Boolean = runCatching {
        val child = file.canonicalFile.path
        val parent = root.canonicalFile.path
        child == parent || child.startsWith(parent + File.separator)
    }.getOrDefault(false)

    private fun fail(code: String, message: String) = AdapterPackageInfo(false, errorCode = code, errorMessage = message)
}
