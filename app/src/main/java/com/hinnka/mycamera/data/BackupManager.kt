package com.hinnka.mycamera.data

import android.content.Context
import android.net.Uri
import com.hinnka.mycamera.utils.PLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份和恢复管理器
 * 负责将应用的 DataStore 设置文件以及 CustomImportManager 管理的自定义资源文件打包和解包
 */
object BackupManager {
    private const val TAG = "BackupManager"

    // 需备份的目录/文件相对路径列表 (相对于 context.filesDir)
    private val BACKUP_ENTRIES = listOf(
        "datastore", // DataStore 默认存放目录
        "custom_luts.json",
        "custom_frames.json",
        "category_overrides.json",
        "custom_luts",
        "custom_frames",
        "custom_fonts",
        "custom_logos"
    )

    /**
     * 执行备份
     * @param context Context
     * @param outputUri 目标 zip 文件的 URI (通过 SAF 选择)
     * @return 备份是否成功
     */
    suspend fun performBackup(context: Context, outputUri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val outputStream = context.contentResolver.openOutputStream(outputUri)
                ?: throw IllegalStateException("Cannot open output stream for URI: $outputUri")

            ZipOutputStream(outputStream).use { zos ->
                val filesDir = context.filesDir

                for (entryName in BACKUP_ENTRIES) {
                    val fileOrDir = File(filesDir, entryName)
                    if (fileOrDir.exists()) {
                        zipFile(fileOrDir, fileOrDir.name, zos)
                    } else {
                        PLog.d(TAG, "Skip missing backup entry: $entryName")
                    }
                }
            }
            PLog.d(TAG, "Backup successfully completed to $outputUri")
            true
        } catch (e: Exception) {
            PLog.e(TAG, "Backup failed", e)
            false
        }
    }

    private fun zipFile(fileToZip: File, fileName: String, zos: ZipOutputStream) {
        if (fileToZip.isHidden) {
            return
        }
        if (fileToZip.isDirectory) {
            if (fileName.endsWith("/")) {
                zos.putNextEntry(ZipEntry(fileName))
                zos.closeEntry()
            } else {
                zos.putNextEntry(ZipEntry("$fileName/"))
                zos.closeEntry()
            }
            val children = fileToZip.listFiles()
            if (children != null) {
                for (childFile in children) {
                    zipFile(childFile, fileName + "/" + childFile.name, zos)
                }
            }
            return
        }

        FileInputStream(fileToZip).use { fis ->
            val zipEntry = ZipEntry(fileName)
            zos.putNextEntry(zipEntry)
            val bytes = ByteArray(1024)
            var length: Int
            while (fis.read(bytes).also { length = it } >= 0) {
                zos.write(bytes, 0, length)
            }
            zos.closeEntry()
        }
    }

    /**
     * 执行恢复
     * @param context Context
     * @param inputUri 来源 zip 文件的 URI (通过 SAF 选择)
     * @return 恢复是否成功
     */
    suspend fun performRestore(context: Context, inputUri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: throw IllegalStateException("Cannot open input stream for URI: $inputUri")

            val filesDir = context.filesDir

            ZipInputStream(inputStream).use { zis ->
                var zipEntry: ZipEntry? = zis.nextEntry
                while (zipEntry != null) {
                    val newFile = File(filesDir, zipEntry.name)

                    // 防止 Zip Slip 漏洞
                    if (!newFile.canonicalPath.startsWith(filesDir.canonicalPath + File.separator)) {
                        PLog.w(TAG, "Skip entry outside of target dir: ${zipEntry.name}")
                        zipEntry = zis.nextEntry
                        continue
                    }

                    if (zipEntry.isDirectory) {
                        if (!newFile.isDirectory && !newFile.mkdirs()) {
                            PLog.w(TAG, "Failed to create directory: $newFile")
                        }
                    } else {
                        // 确保父目录存在
                        val parent = newFile.parentFile
                        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                            PLog.w(TAG, "Failed to create parent directory for: $newFile")
                        }

                        // 写入文件
                        FileOutputStream(newFile).use { fos ->
                            val bytes = ByteArray(1024)
                            var length: Int
                            while (zis.read(bytes).also { length = it } >= 0) {
                                fos.write(bytes, 0, length)
                            }
                        }
                    }
                    zis.closeEntry()
                    zipEntry = zis.nextEntry
                }
            }
            PLog.d(TAG, "Restore successfully completed from $inputUri")
            true
        } catch (e: Exception) {
            PLog.e(TAG, "Restore failed", e)
            false
        }
    }
}
