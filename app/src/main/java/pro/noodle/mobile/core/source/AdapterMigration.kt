package pro.noodle.mobile.core.source

import pro.noodle.mobile.core.adapter.AdapterSchemaParser
import pro.noodle.mobile.core.adapter.AdapterValidator
import pro.noodle.mobile.core.engine.EngineDependencies
import pro.noodle.mobile.core.json.*
import java.io.File


data class AdapterMigrationResult(
    val success: Boolean,
    val changed: Boolean = false,
    val adapterId: String = "",
    val oldSchemaVersion: Int = 0,
    val newSchemaVersion: Int = 3,
    val snapshotPath: String = "",
    val errorCode: String = "",
    val message: String = "",
)

class AdapterMigration(private val paths: AdapterStorePaths, private val recovery: AdapterRecoveryManager) {
    fun migrateCustomToV3(adapterDir: File, createSnapshot: Boolean = true): AdapterMigrationResult {
        if (!paths.isCustom(adapterDir)) return AdapterMigrationResult(false, errorCode = "ADAPTER_MIGRATION_CUSTOM_ONLY", message = "Штатные SNP мигрируют вместе с приложением.")
        val adapterFile = File(adapterDir, "adapter.json")
        if (!adapterFile.isFile) return AdapterMigrationResult(false, errorCode = "ADAPTER_FILE_MISSING", message = "adapter.json отсутствует.")
        val originalText = runCatching { adapterFile.readText(Charsets.UTF_8) }.getOrElse { return AdapterMigrationResult(false, errorCode = "ADAPTER_MIGRATION_READ_FAILED", message = it.message.orEmpty()) }
        val load = AdapterSchemaParser.parse(originalText)
        if (!load.success || load.definition == null) return AdapterMigrationResult(false, errorCode = "ADAPTER_MIGRATION_BUILD_FAILED", message = "${load.errorCode}: ${load.errorMessage}")
        val d = load.definition
        if (d.schemaVersion >= 3) return AdapterMigrationResult(true, changed = false, adapterId = d.id, oldSchemaVersion = d.schemaVersion, message = "SNP уже использует Schema v3.")

        val root = try { MiniJson.parse(originalText) as? JsonValue.Obj ?: return AdapterMigrationResult(false, errorCode = "ADAPTER_MIGRATION_JSON_INVALID", message = "adapter.json не является JSON-объектом.") }
        catch (e: Exception) { return AdapterMigrationResult(false, errorCode = "ADAPTER_MIGRATION_JSON_INVALID", message = e.message.orEmpty()) }
        val map = LinkedHashMap(root.values)
        map["version"] = JsonValue.Num(3.0)
        map["engine_dependencies"] = JsonValue.Arr(EngineDependencies.recommendedFor(d.engine).map { JsonValue.Str(it) })
        map["repair"] = JsonValue.Obj(linkedMapOf(
            "enabled" to JsonValue.Bool(d.healthAutoRepair),
            "mode" to JsonValue.Str("conservative"),
            "failure_threshold" to JsonValue.Num(3.0),
            "cooldown_minutes" to JsonValue.Num(60.0),
            "auto_quarantine" to JsonValue.Bool(false),
            "strategies" to JsonValue.Arr(buildList {
                if (d.engine.equals("declarative_catalog", true)) add(JsonValue.Str("selector_relearn"))
                add(JsonValue.Str("resolver_refresh"))
            }),
        ))
        val health = (map["health"] as? JsonValue.Obj)?.let { LinkedHashMap(it.values) } ?: linkedMapOf()
        if ("auto_repair" !in health) health["auto_repair"] = JsonValue.Bool(false)
        map["health"] = JsonValue.Obj(health)
        val candidateText = JsonValue.Obj(map).toJson(pretty = true)
        val validation = AdapterValidator.validate(candidateText)
        if (!validation.isValid) return AdapterMigrationResult(false, adapterId = d.id, oldSchemaVersion = d.schemaVersion, errorCode = validation.errorCode, message = validation.errorMessage)

        var snapshot = ""
        val hadManifest = File(adapterDir, "snp-package.json").isFile
        val candidateFile = File(paths.updates, d.id.lowercase() + ".migration-candidate.json")
        candidateFile.delete()
        return try {
            if (createSnapshot) {
                snapshot = recovery.createSnapshot(d.id, adapterDir, d.adapterVersion)?.path.orEmpty()
                if (snapshot.isBlank()) return AdapterMigrationResult(false, adapterId=d.id, oldSchemaVersion=d.schemaVersion, errorCode="ADAPTER_MIGRATION_SNAPSHOT_FAILED", message="Не удалось создать snapshot перед миграцией.")
            }
            candidateFile.parentFile?.mkdirs()
            candidateFile.writeText(candidateText, Charsets.UTF_8)
            // Candidate lives outside runtime so it is never included in package discovery or snapshots.
            adapterFile.writeText(candidateFile.readText(Charsets.UTF_8), Charsets.UTF_8)
            if (hadManifest) AdapterPackageManager.createManifest(adapterDir, d.id, d.adapterVersion, d.publisher)
            val post = if (hadManifest) AdapterPackageManager.verifyDirectory(adapterDir) else AdapterPackageInfo(true)
            if (!post.valid) throw IllegalStateException("Post-migration integrity failed: ${post.errorCode}")
            AdapterMigrationResult(true, changed = true, adapterId = d.id, oldSchemaVersion = d.schemaVersion, snapshotPath = snapshot, message = "Schema v${d.schemaVersion} -> v3.")
        } catch (e: Exception) {
            runCatching {
                adapterFile.writeText(originalText, Charsets.UTF_8)
                if (hadManifest) AdapterPackageManager.createManifest(adapterDir, d.id, d.adapterVersion, d.publisher)
            }
            AdapterMigrationResult(false, adapterId = d.id, oldSchemaVersion = d.schemaVersion, snapshotPath = snapshot, errorCode = "ADAPTER_MIGRATION_EXCEPTION", message = "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            candidateFile.delete()
        }
    }
}
