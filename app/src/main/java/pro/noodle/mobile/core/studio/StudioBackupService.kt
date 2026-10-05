package pro.noodle.mobile.core.studio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pro.noodle.mobile.core.source.RuntimeAdapterRepository
import java.io.File

/** Keeps a small rolling set of complete .snp.zip backups after successful Studio saves. */
class StudioBackupService(context: Context) {
    private val app = context.applicationContext
    private val repo = RuntimeAdapterRepository(app)
    private val root = File(app.filesDir, "snp-backups")

    suspend fun backup(adapterId: String): File? = withContext(Dispatchers.IO) {
        val runtime = repo.find(adapterId) ?: return@withContext null
        if (runtime.bundled) return@withContext null
        val dir = File(root, adapterId.lowercase()).apply { mkdirs() }
        val version = runtime.definition.adapterVersion.ifBlank { "unknown" }.replace(Regex("[^A-Za-z0-9._-]"), "-")
        val target = File(dir, "${System.currentTimeMillis()}-$version.snp.zip")
        if (!repo.exportAdapter(adapterId, target)) return@withContext null
        dir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(".snp.zip", true) }
            .sortedByDescending { it.lastModified() }
            .drop(MAX_BACKUPS_PER_SOURCE)
            .forEach { it.delete() }
        target
    }

    fun list(adapterId: String): List<File> = File(root, adapterId.lowercase())
        .listFiles().orEmpty()
        .filter { it.isFile && it.name.endsWith(".snp.zip", true) }
        .sortedByDescending { it.lastModified() }

    companion object { private const val MAX_BACKUPS_PER_SOURCE = 5 }
}
