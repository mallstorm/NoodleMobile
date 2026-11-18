package pro.noodle.mobile.core.source

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pro.noodle.mobile.core.adapter.AdapterDefinition
import pro.noodle.mobile.core.adapter.AdapterSchemaParser
import pro.noodle.mobile.core.adapter.AdapterValidator
import java.io.File


data class RuntimeAdapter(
    val definition: AdapterDefinition,
    val directory: File,
    val bundled: Boolean,
    val packageInfo: AdapterPackageInfo,
    val compatibility: AdapterCompatibilityResult,
)

class RuntimeAdapterRepository(private val context: Context) {
    val paths = AdapterStorePaths(File(context.filesDir, "adapters"))
    private val mirrors = MirrorStore(context)

    init {
        paths.ensure()
        seedBundledAssets()
    }

    fun listAll(): List<RuntimeAdapter> = paths.runtimeAdapterDirs().mapNotNull { dir ->
        val file = File(dir, "adapter.json")
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return@mapNotNull null
        val validation = AdapterValidator.validate(text)
        val d = validation.definition?.takeIf { validation.isValid }?.let { mirrors.apply(it) } ?: return@mapNotNull null
        RuntimeAdapter(
            definition = d,
            directory = dir,
            bundled = paths.isBundled(dir),
            packageInfo = AdapterPackageManager.verifyDirectory(dir, paths.bundled),
            compatibility = AdapterCompatibility.evaluate(d.minAppVersion, d.maxAppVersion),
        )
    }.sortedWith(compareByDescending<RuntimeAdapter> { it.bundled }.thenBy { it.definition.name.lowercase() })

    fun find(id: String): RuntimeAdapter? = listAll().firstOrNull { it.definition.id.equals(id, true) }
    fun findForBaseUrl(baseUrl: String): RuntimeAdapter? = listAll().firstOrNull { it.definition.baseUrl.equals(baseUrl, true) }

    suspend fun importCustomZip(zipFile: File): AdapterInstallResult = withContext(Dispatchers.IO) {
        val staging = File(paths.updates, "custom-import-${System.currentTimeMillis()}")
        val ext = AdapterZipManager.extractPackage(zipFile, staging)
        if (!ext.success || ext.root == null) return@withContext AdapterInstallResult(false, errorCode = ext.errorCode, message = ext.errorMessage)
        val candidate = ext.root
        try {
            val adapterFile = File(candidate, "adapter.json")
            val validation = AdapterValidator.validate(adapterFile.readText(Charsets.UTF_8))
            if (!validation.isValid || validation.definition == null) return@withContext AdapterInstallResult(false, errorCode = validation.errorCode, message = validation.errorMessage)
            val d = validation.definition
            if (File(paths.bundled, d.id.lowercase()).exists() || listAll().any { it.bundled && it.definition.id.equals(d.id, true) }) {
                return@withContext AdapterInstallResult(false, errorCode = "SNP_IMPORT_BUNDLED_COLLISION", message = "Пользовательский SNP не может заменить штатный источник.")
            }
            val manifest = File(candidate, "snp-package.json")
            if (!manifest.isFile) AdapterPackageManager.createManifest(candidate, d.id, d.adapterVersion, d.publisher)
            val pkg = AdapterPackageManager.verifyDirectory(candidate)
            if (!pkg.valid) return@withContext AdapterInstallResult(false, errorCode = pkg.errorCode, message = pkg.errorMessage)
            val compatibility = AdapterCompatibility.evaluate(d.minAppVersion, d.maxAppVersion)
            if (!compatibility.compatible) return@withContext AdapterInstallResult(false, errorCode = compatibility.errorCode, message = compatibility.message)

            val target = File(paths.custom, d.id.lowercase())
            var snapshot = ""
            if (target.isDirectory) {
                val current = AdapterSchemaParser.parse(File(target, "adapter.json").readText()).definition
                if (current != null) snapshot = AdapterRecoveryManager(paths).createSnapshot(d.id, target, current.adapterVersion)?.path.orEmpty()
            }
            val ready = File(paths.updates, d.id.lowercase() + ".import-ready")
            ready.deleteRecursively(); candidate.copyRecursively(ready, overwrite = true)
            val old = File(paths.updates, d.id.lowercase() + ".import-old")
            old.deleteRecursively()
            if (target.exists() && !target.renameTo(old)) return@withContext AdapterInstallResult(false, errorCode = "SNP_IMPORT_SWAP_FAILED", message = "Не удалось подготовить замену текущего custom SNP.")
            if (!ready.renameTo(target)) {
                if (!target.exists() && old.exists()) old.renameTo(target)
                return@withContext AdapterInstallResult(false, errorCode = "SNP_IMPORT_SWAP_FAILED", message = "Не удалось активировать custom SNP.")
            }
            old.deleteRecursively()
            AdapterInstallResult(true, adapterId = d.id, installedVersion = d.adapterVersion, snapshotPath = snapshot, message = "Custom SNP установлен. Перед включением рекомендуется semantic validation.")
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun exportAdapter(id: String, output: File): Boolean = withContext(Dispatchers.IO) {
        val item = find(id) ?: return@withContext false
        AdapterZipManager.exportDirectory(item.directory, output)
    }

    suspend fun removeCustom(id: String): Boolean = withContext(Dispatchers.IO) {
        val item = find(id) ?: return@withContext false
        if (item.bundled || !paths.isCustom(item.directory)) return@withContext false
        item.directory.deleteRecursively()
    }

    private fun seedBundledAssets() {
        val bundledIds = context.assets.list("adapters/bundled").orEmpty().sorted()
        for (id in bundledIds) {
            val target = File(paths.bundled, id)
            val staging = File(paths.updates, "bundled-$id.seed")
            staging.deleteRecursively(); staging.mkdirs()
            copyAssetTree("adapters/bundled/$id", staging)
            val pkg = AdapterPackageManager.verifyDirectory(staging)
            if (!pkg.valid) {
                staging.deleteRecursively()
                continue
            }
            val old = File(paths.updates, "bundled-$id.old")
            old.deleteRecursively()
            if (target.exists()) target.renameTo(old)
            if (staging.renameTo(target)) old.deleteRecursively() else if (!target.exists() && old.exists()) old.renameTo(target)
        }
    }

    private fun copyAssetTree(assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input -> target.outputStream().use { input.copyTo(it) } }
            return
        }
        target.mkdirs()
        for (child in children) copyAssetTree("$assetPath/$child", File(target, child))
    }
}
