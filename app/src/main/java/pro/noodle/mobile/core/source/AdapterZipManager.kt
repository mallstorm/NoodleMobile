package pro.noodle.mobile.core.source

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object AdapterZipManager {
    const val MAX_ZIP_BYTES = 16L * 1024 * 1024
    const val MAX_EXPANDED_BYTES = 32L * 1024 * 1024
    const val MAX_ENTRY_BYTES = 16L * 1024 * 1024
    const val MAX_ENTRIES = 256
    const val MAX_COMPRESSION_RATIO = 100.0

    data class ExtractResult(val success: Boolean, val root: File? = null, val errorCode: String = "", val errorMessage: String = "")

    fun extractPackage(zipFile: File, stagingRoot: File): ExtractResult {
        if (!zipFile.isFile) return fail("SNP_IMPORT_PACKAGE_MISSING", "ZIP-пакет не найден.")
        if (zipFile.length() > MAX_ZIP_BYTES) return fail("SNP_IMPORT_PACKAGE_TOO_LARGE", "ZIP превышает 16 MiB.")
        if (stagingRoot.exists()) stagingRoot.deleteRecursively()
        stagingRoot.mkdirs()
        var totalExpanded = 0L
        return try {
            ZipFile(zipFile).use { zip ->
                val entries = zip.entries().toList()
                if (entries.size > MAX_ENTRIES) return fail("SNP_IMPORT_TOO_MANY_ENTRIES", "В ZIP слишком много файлов.")
                val seenEntries = hashSetOf<String>()
                for (entry in entries) {
                    val rawName = entry.name.replace('\\', '/')
                    if (rawName.isBlank() || rawName.startsWith('/') || !AdapterPackageManager.isSafeRelativePath(rawName.trimEnd('/'))) {
                        return fail("SNP_IMPORT_PATH_UNSAFE", "Небезопасный ZIP path: ${entry.name}")
                    }
                    val entryKey = rawName.trimEnd('/').lowercase()
                    if (!seenEntries.add(entryKey)) return fail("SNP_IMPORT_ENTRY_DUPLICATE", "Дублирующийся ZIP path: ${entry.name}")
                    if (entry.size > MAX_ENTRY_BYTES) return fail("SNP_IMPORT_ENTRY_TOO_LARGE", "Слишком большой файл: ${entry.name}")
                    if (entry.size > 0 && entry.compressedSize > 0 && entry.size.toDouble() / entry.compressedSize.toDouble() > MAX_COMPRESSION_RATIO) {
                        return fail("SNP_IMPORT_SUSPICIOUS_COMPRESSION", "Подозрительная степень сжатия: ${entry.name}")
                    }
                    val out = AdapterPackageManager.resolveInside(stagingRoot, rawName) ?: return fail("SNP_IMPORT_PATH_UNSAFE", "ZIP path выходит из staging: ${entry.name}")
                    if (entry.isDirectory) {
                        out.mkdirs()
                        continue
                    }
                    out.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        BufferedOutputStream(FileOutputStream(out)).use { output ->
                            val buf = ByteArray(64 * 1024)
                            var entryBytes = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                entryBytes += n
                                totalExpanded += n
                                if (entryBytes > MAX_ENTRY_BYTES) return fail("SNP_IMPORT_ENTRY_TOO_LARGE", "Распакованный файл превышает лимит: ${entry.name}")
                                if (totalExpanded > MAX_EXPANDED_BYTES) return fail("SNP_IMPORT_EXPANDED_TOO_LARGE", "Распакованный пакет превышает 32 MiB.")
                                output.write(buf, 0, n)
                            }
                        }
                    }
                }
            }
            val root = normalizeSingleRoot(stagingRoot)
            if (!File(root, "adapter.json").isFile) fail("SNP_IMPORT_ADAPTER_MISSING", "В корне SNP отсутствует adapter.json.")
            else ExtractResult(true, root)
        } catch (e: Exception) {
            fail("SNP_IMPORT_ZIP_ERROR", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun exportDirectory(adapterDir: File, outputZip: File): Boolean {
        if (!adapterDir.isDirectory) return false
        outputZip.parentFile?.mkdirs()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(outputZip))).use { zip ->
            adapterDir.walkTopDown().filter { it.isFile }.forEach { file ->
                val rel = file.relativeTo(adapterDir).invariantSeparatorsPath
                if (!AdapterPackageManager.isSafeRelativePath(rel)) return@forEach
                zip.putNextEntry(ZipEntry(rel))
                BufferedInputStream(FileInputStream(file)).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return outputZip.isFile
    }

    private fun normalizeSingleRoot(staging: File): File {
        if (File(staging, "adapter.json").isFile) return staging
        val children = staging.listFiles().orEmpty().filter { it.name != ".DS_Store" }
        if (children.size == 1 && children[0].isDirectory && File(children[0], "adapter.json").isFile) return children[0]
        return staging
    }

    private fun fail(code: String, message: String) = ExtractResult(false, errorCode = code, errorMessage = message)
}
