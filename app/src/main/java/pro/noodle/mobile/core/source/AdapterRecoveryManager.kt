package pro.noodle.mobile.core.source

import pro.noodle.mobile.core.adapter.AdapterSchemaParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


data class AdapterSnapshotInfo(
    val snapshotId: String,
    val path: String,
    val version: String,
    val createdAt: Long,
    val validPackage: Boolean,
)

class AdapterRecoveryManager(private val paths: AdapterStorePaths, private val keepCount: Int = 5) {
    private fun folder(adapterId: String) = File(paths.history, adapterId.trim().lowercase())

    fun createSnapshot(adapterId: String, currentDir: File, version: String): File? {
        if (!currentDir.isDirectory) return null
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val safeVersion = version.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").replace('.', '_').replace('-', '_')
        val dest = File(folder(adapterId), "${stamp}_${safeVersion}")
        currentDir.copyRecursively(dest, overwrite = true)
        prune(adapterId)
        return dest
    }

    fun enumerate(adapterId: String): List<AdapterSnapshotInfo> {
        val root = folder(adapterId)
        return root.listFiles().orEmpty().filter { it.isDirectory }.map { dir ->
            val load = runCatching { AdapterSchemaParser.parse(File(dir, "adapter.json").readText()) }.getOrNull()
            val pkg = AdapterPackageManager.verifyDirectory(dir)
            AdapterSnapshotInfo(dir.name, dir.path, load?.definition?.adapterVersion.orEmpty(), dir.lastModified(), pkg.valid)
        }.sortedByDescending { it.snapshotId }
    }

    fun restoreLatest(adapterId: String, targetDir: File): String? = enumerate(adapterId).firstOrNull()?.let { restoreSnapshot(adapterId, it.snapshotId, targetDir) }

    fun restoreSnapshot(adapterId: String, snapshotId: String, targetDir: File): String? {
        if (!Regex("^[A-Za-z0-9._-]{1,160}$").matches(snapshotId.trim())) return null
        val root = folder(adapterId).canonicalFile
        val snapshot = File(root, snapshotId).canonicalFile
        if (!AdapterPackageManager.isUnder(snapshot, root) || !snapshot.isDirectory) return null
        val pkg = AdapterPackageManager.verifyDirectory(snapshot)
        if (!pkg.valid) return null
        val d = AdapterSchemaParser.parse(File(snapshot, "adapter.json").readText()).definition ?: return null
        if (!d.id.equals(adapterId, true)) return null
        if (!AdapterCompatibility.evaluate(d.minAppVersion, d.maxAppVersion).compatible) return null
        val staging = File(targetDir.parentFile, targetDir.name + ".restore-staging")
        val failed = File(targetDir.parentFile, targetDir.name + ".failed-" + System.currentTimeMillis())
        staging.deleteRecursively()
        snapshot.copyRecursively(staging, overwrite = true)
        if (targetDir.exists() && !targetDir.renameTo(failed)) {
            staging.deleteRecursively(); return null
        }
        return try {
            if (!staging.renameTo(targetDir)) throw IllegalStateException("Не удалось активировать snapshot")
            failed.deleteRecursively()
            snapshot.path
        } catch (_: Exception) {
            staging.deleteRecursively()
            if (!targetDir.exists() && failed.exists()) failed.renameTo(targetDir)
            null
        }
    }

    fun prune(adapterId: String) {
        val dirs = folder(adapterId).listFiles().orEmpty().filter { it.isDirectory }.sortedByDescending { it.name }
        dirs.drop(keepCount.coerceAtLeast(1)).forEach { it.deleteRecursively() }
    }
}
