import pro.noodle.mobile.core.adapter.AdapterSchemaParser
import pro.noodle.mobile.core.source.*
import pro.noodle.mobile.core.json.JsonValue
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private data class T(val name:String,val ok:Boolean,val detail:String="")
private val tests=mutableListOf<T>()
private fun ck(name:String,ok:Boolean,detail:String=""){tests+=T(name,ok,detail);println("${if(ok)"PASS" else "FAIL"} | $name${if(detail.isBlank())"" else " | $detail"}")}
private fun tempDir(name:String):File=Files.createTempDirectory("noodle-$name-").toFile()
private fun adapterJson(id:String,version:String="1.0.0",schema:Int=3):String="""
{
  "version": $schema,
  "id": "$id",
  "adapter_version": "$version",
  "publisher": "harness",
  "name": "$id",
  "base_url": "https://$id",
  "engine": "generic_html5",
  "engine_dependencies": ["hls_manifest","dash_manifest"],
  "enabled": true,
  "permissions": {"allowed_hosts":["$id"],"allow_javascript":false,"allow_cookies":false},
  "view": {"strategies":["primary","generic_html5","hls_manifest","dash_manifest"]},
  "health": {"query":"video","min_results":1,"require_playback":false,"auto_repair":false},
  "repair": {"enabled":false,"mode":"conservative","failure_threshold":3,"cooldown_minutes":60,"auto_quarantine":false,"strategies":["resolver_refresh"]}
}
""".trimIndent()

fun main(args:Array<String>){
    val bundledRoot=File(args.firstOrNull() ?: "app/src/main/assets/adapters/bundled")
    val bundled=bundledRoot.listFiles().orEmpty().filter{it.isDirectory}.sortedBy{it.name}
    ck("5 bundled package directories",bundled.size==5,bundled.joinToString{it.name})
    bundled.forEach{dir->
        val p=AdapterPackageManager.verifyDirectory(dir,bundledRoot)
        ck("bundled package ${dir.name}",p.valid&&p.trustStatus==AdapterTrustStatus.BUNDLED_TRUSTED,"${p.trustStatus.wireName} ${p.errorCode}")
    }

    run{
        val root=tempDir("manifest")
        File(root,"adapter.json").writeText(adapterJson("example.com"))
        AdapterPackageManager.createManifest(root,"example.com","1.0.0","harness")
        val p=AdapterPackageManager.verifyDirectory(root)
        ck("manifest create/verify",p.valid&&p.trustStatus==AdapterTrustStatus.INTEGRITY_VERIFIED_UNSIGNED,p.toString())
        File(root,"adapter.json").appendText(" ")
        val t=AdapterPackageManager.verifyDirectory(root)
        ck("manifest detects tamper",!t.valid&&t.errorCode in setOf("SNP_PACKAGE_SIZE_MISMATCH","SNP_PACKAGE_HASH_MISMATCH"),t.errorCode)
        root.deleteRecursively()
    }

    run{
        val root=tempDir("unlisted")
        File(root,"adapter.json").writeText(adapterJson("unlisted.example"))
        AdapterPackageManager.createManifest(root,"unlisted.example","1.0.0","harness")
        File(root,"extra.txt").writeText("x")
        val p=AdapterPackageManager.verifyDirectory(root)
        ck("manifest rejects unlisted file",!p.valid&&p.errorCode=="SNP_PACKAGE_UNLISTED_FILE",p.errorCode)
        root.deleteRecursively()
    }

    run{
        val base=tempDir("zip")
        val zip=File(base,"bad.zip")
        ZipOutputStream(zip.outputStream()).use{z->
            z.putNextEntry(ZipEntry("../escape.txt"));z.write("x".toByteArray());z.closeEntry()
        }
        val r=AdapterZipManager.extractPackage(zip,File(base,"out"))
        ck("ZIP traversal blocked",!r.success&&r.errorCode=="SNP_IMPORT_PATH_UNSAFE",r.errorCode)
        base.deleteRecursively()
    }

    run{
        val base=tempDir("zip-duplicate")
        val zip=File(base,"duplicate.zip")
        ZipOutputStream(zip.outputStream()).use{z->
            z.putNextEntry(ZipEntry("adapter.json"));z.write("{}".toByteArray());z.closeEntry()
            z.putNextEntry(ZipEntry("ADAPTER.JSON"));z.write("{}".toByteArray());z.closeEntry()
        }
        val r=AdapterZipManager.extractPackage(zip,File(base,"out"))
        ck("ZIP duplicate path blocked",!r.success&&r.errorCode=="SNP_IMPORT_ENTRY_DUPLICATE",r.errorCode)
        base.deleteRecursively()
    }

    run{
        val root=tempDir("snapshot")
        val paths=AdapterStorePaths(File(root,"adapters"));paths.ensure()
        val current=File(paths.custom,"snap.example").apply{mkdirs()}
        File(current,"adapter.json").writeText(adapterJson("snap.example","1.0.0"))
        AdapterPackageManager.createManifest(current,"snap.example","1.0.0","harness")
        val recovery=AdapterRecoveryManager(paths,5)
        repeat(7){i->
            File(current,"marker.txt").writeText("$i")
            AdapterPackageManager.createManifest(current,"snap.example","1.0.0","harness")
            recovery.createSnapshot("snap.example",current,"1.0.$i")
            Thread.sleep(2)
        }
        val history=recovery.enumerate("snap.example")
        ck("snapshot history pruned to 5",history.size==5,history.size.toString())
        File(current,"marker.txt").writeText("broken")
        val restored=recovery.restoreLatest("snap.example",current)
        ck("snapshot restore latest",restored!=null&&File(current,"marker.txt").readText()!="broken",restored.orEmpty())
        root.deleteRecursively()
    }

    run{
        val root=tempDir("snapshot-compat")
        val paths=AdapterStorePaths(File(root,"adapters"));paths.ensure()
        val current=File(paths.custom,"future.example").apply{mkdirs()}
        val future=adapterJson("future.example","1.0.0").replace("\"name\": \"future.example\"", "\"min_app_version\": \"9.0.0\",\n  \"name\": \"future.example\"")
        File(current,"adapter.json").writeText(future)
        AdapterPackageManager.createManifest(current,"future.example","1.0.0","harness")
        val recovery=AdapterRecoveryManager(paths)
        val snap=recovery.createSnapshot("future.example",current,"1.0.0")
        File(current,"adapter.json").writeText(adapterJson("future.example","1.0.0"));AdapterPackageManager.createManifest(current,"future.example","1.0.0","harness")
        val restored=if(snap!=null) recovery.restoreSnapshot("future.example",snap.name,current) else null
        ck("snapshot compatibility gate",restored==null)
        root.deleteRecursively()
    }

    run{
        val root=tempDir("migration")
        val paths=AdapterStorePaths(File(root,"adapters"));paths.ensure()
        val dir=File(paths.custom,"legacy.example").apply{mkdirs()}
        val v2="""{"version":2,"id":"legacy.example","adapter_version":"1.0.0","publisher":"harness","name":"Legacy","base_url":"https://legacy.example","engine":"generic_html5","enabled":true,"permissions":{"allowed_hosts":["legacy.example"]},"health":{"query":"video","min_results":1,"require_playback":false,"auto_repair":true}}"""
        File(dir,"adapter.json").writeText(v2)
        AdapterPackageManager.createManifest(dir,"legacy.example","1.0.0","harness")
        val r=AdapterMigration(paths,AdapterRecoveryManager(paths)).migrateCustomToV3(dir,true)
        val d=AdapterSchemaParser.parse(File(dir,"adapter.json").readText()).definition
        ck("Schema v2 -> v3 migration",r.success&&r.changed&&d?.schemaVersion==3,r.toString())
        ck("migration snapshot created",r.snapshotPath.isNotBlank()&&File(r.snapshotPath).isDirectory,r.snapshotPath)
        ck("migration candidate not leaked",!File(dir,"adapter.v3.candidate.json").exists() && !File(paths.updates,"legacy.example.migration-candidate.json").exists())
        ck("migration manifest rebuilt",AdapterPackageManager.verifyDirectory(dir).valid)
        root.deleteRecursively()
    }

    run{
        val root=tempDir("update")
        val paths=AdapterStorePaths(File(root,"adapters"));paths.ensure()
        val current=File(paths.custom,"update.example").apply{mkdirs()}
        File(current,"adapter.json").writeText(adapterJson("update.example","1.0.0"))
        AdapterPackageManager.createManifest(current,"update.example","1.0.0","harness")
        val packageDir=File(root,"package").apply{mkdirs()}
        File(packageDir,"adapter.json").writeText(adapterJson("update.example","1.1.0"))
        AdapterPackageManager.createManifest(packageDir,"update.example","1.1.0","harness")
        val zip=File(root,"update.zip");AdapterZipManager.exportDirectory(packageDir,zip)
        val recovery=AdapterRecoveryManager(paths)
        val manager=AdapterUpdateManager(paths,recovery)
        val sameDir=File(root,"same-package").apply{mkdirs()}
        File(sameDir,"adapter.json").writeText(adapterJson("update.example","1.0.0"));AdapterPackageManager.createManifest(sameDir,"update.example","1.0.0","harness")
        val sameZip=File(root,"same.zip");AdapterZipManager.exportDirectory(sameDir,sameZip)
        val rejected=manager.installPackageZip("update.example",current,sameZip,"",true)
        ck("rejected update staging cleaned",!rejected.success && rejected.errorCode=="SNP_UPDATE_NOT_NEWER" && !File(paths.updates,"update.example.staging").exists(),rejected.errorCode)
        val install=manager.installPackageZip("update.example",current,zip,AdapterPackageManager.sha256(zip),true)
        ck("staged update installed",install.success&&install.installedVersion=="1.1.0",install.toString())
        ck("pending semantic marker",manager.readPending("update.example")?.newVersion=="1.1.0")
        val action=manager.completePendingValidation("update.example",false,current)
        val rolled=AdapterSchemaParser.parse(File(current,"adapter.json").readText()).definition?.adapterVersion
        ck("failed semantic auto-rolls back",action?.startsWith("auto_rollback:")==true&&rolled=="1.0.0","$action version=$rolled")
        val install2=manager.installPackageZip("update.example",current,zip,"",true)
        val confirm=manager.completePendingValidation("update.example",true,current)
        ck("successful semantic confirms update",install2.success&&confirm=="confirmed:1.1.0"&&!manager.pendingFile("update.example").exists(),confirm.orEmpty())
        root.deleteRecursively()
    }

    run{
        ck("compat current range",AdapterCompatibility.evaluate("1.9.0","2.0.0").compatible)
        ck("compat rejects newer minimum",!AdapterCompatibility.evaluate("9.0.0","").compatible)
    }

    run{
        val root=tempDir("state")
        val s=SourceStatusStore(File(root,"state.json"))
        s.put("a",mapOf("status" to JsonValue.Str("healthy"),"failures" to JsonValue.Num(2.0)))
        ck("source state persists",s.get("a","status")=="healthy"&&s.getInt("a","failures")==2)
        root.deleteRecursively()
    }

    val failed=tests.filterNot{it.ok}
    println("SUMMARY ${tests.size-failed.size}/${tests.size} passed")
    if(failed.isNotEmpty())error("Failed: "+failed.joinToString{it.name})
}
