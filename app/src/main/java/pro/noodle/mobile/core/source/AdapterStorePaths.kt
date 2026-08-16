package pro.noodle.mobile.core.source

import java.io.File

class AdapterStorePaths(val root: File) {
    val bundled = File(root, "bundled")
    val custom = File(root, "custom")
    val history = File(root, "_history")
    val updates = File(root, "_updates")
    val pending = File(updates, "pending")
    val repair = File(updates, "repair")
    val diagnostics = File(root.parentFile ?: root, "diagnostics")

    fun ensure() {
        listOf(root, bundled, custom, history, updates, pending, repair, diagnostics).forEach { it.mkdirs() }
    }

    fun runtimeAdapterDirs(): List<File> = sequenceOf(bundled, custom)
        .filter { it.isDirectory }
        .flatMap { rootDir -> rootDir.listFiles().orEmpty().asSequence().filter { it.isDirectory && File(it, "adapter.json").isFile } }
        .toList()

    fun findAdapterDir(id: String): File? = runtimeAdapterDirs().firstOrNull {
        runCatching { pro.noodle.mobile.core.adapter.AdapterSchemaParser.parse(File(it, "adapter.json").readText()).definition?.id.equals(id, true) }.getOrDefault(false)
    }

    fun isBundled(dir: File): Boolean = AdapterPackageManager.isUnder(dir, bundled)
    fun isCustom(dir: File): Boolean = AdapterPackageManager.isUnder(dir, custom)
}
