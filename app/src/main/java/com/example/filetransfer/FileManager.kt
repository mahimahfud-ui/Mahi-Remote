package com.example.filetransfer

import android.content.Context
import android.os.Environment
import com.example.security.AllowlistValidator
import com.example.security.AuditLogger
import com.example.security.AuditSeverity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class FileEntry(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val formattedSize: String,
    val isDirectory: Boolean,
    val lastModified: String,
    val mimeType: String,
    val sha256: String? = null
)

data class TransferProgress(
    val transferId: String,
    val fileName: String,
    val totalBytes: Long,
    val transferredBytes: Long,
    val percent: Int,
    val speedKbps: Int,
    val isUpload: Boolean,
    val status: String, // "IN_PROGRESS", "COMPLETED", "FAILED", "CANCELLED"
    val checksum: String? = null
)

class FileManager(private val context: Context) {

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    private val _activeTransfers = MutableStateFlow<List<TransferProgress>>(emptyList())
    val activeTransfers: StateFlow<List<TransferProgress>> = _activeTransfers.asStateFlow()

    private val _transferHistory = MutableStateFlow<List<TransferProgress>>(emptyList())
    val transferHistory: StateFlow<List<TransferProgress>> = _transferHistory.asStateFlow()

    fun getPermittedRootFolders(): List<File> {
        val roots = mutableListOf<File>()
        roots.add(context.filesDir)
        context.getExternalFilesDir(null)?.let { roots.add(it) }

        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (downloads != null && downloads.exists()) roots.add(downloads)

        val documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        if (documents != null && documents.exists()) roots.add(documents)

        return roots.filter { it.canRead() }
    }

    fun listFiles(dirPath: String): List<FileEntry> {
        val dir = File(dirPath)
        val roots = getPermittedRootFolders().map { it.absolutePath }
        val validation = AllowlistValidator.validateFilePath(dir.absolutePath, roots)
        if (validation !is AllowlistValidator.ValidationResult.Valid) {
            return emptyList()
        }

        val children = dir.listFiles() ?: return emptyList()
        return children.map { file ->
            val size = if (file.isDirectory) 0L else file.length()
            FileEntry(
                name = file.name,
                path = file.absolutePath,
                sizeBytes = size,
                formattedSize = formatFileSize(size, file.isDirectory),
                isDirectory = file.isDirectory,
                lastModified = timeFormat.format(Date(file.lastModified())),
                mimeType = getMimeType(file)
            )
        }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    fun createFolder(parentPath: String, folderName: String): Boolean {
        val roots = getPermittedRootFolders().map { it.absolutePath }
        val sanitizedName = folderName.replace(Regex("[/\\\\:?*\"<>|]"), "_")
        val target = File(parentPath, sanitizedName)
        if (AllowlistValidator.validateFilePath(target.absolutePath, roots) !is AllowlistValidator.ValidationResult.Valid) {
            return false
        }
        val success = target.mkdirs()
        AuditLogger.log("FILE", "DIR_CREATED", "Created folder: ${target.name} (success=$success)", AuditSeverity.INFO)
        return success
    }

    fun deleteFile(path: String, confirmed: Boolean): Boolean {
        if (!confirmed) {
            AuditLogger.log("FILE", "DELETE_REFUSED", "Deletion unconfirmed by user for: $path", AuditSeverity.WARNING)
            return false
        }
        val file = File(path)
        val roots = getPermittedRootFolders().map { it.absolutePath }
        val validation = AllowlistValidator.validateFilePath(file.absolutePath, roots)
        if (validation !is AllowlistValidator.ValidationResult.Valid) return false

        val success = if (file.isDirectory) file.deleteRecursively() else file.delete()
        if (success) {
            AuditLogger.log("FILE", "DELETED", "Deleted: ${file.name}", AuditSeverity.SECURITY)
        } else {
            AuditLogger.log("FILE", "DELETE_FAILED", "Failed to delete: ${file.name}", AuditSeverity.WARNING)
        }
        return success
    }

    fun renameFile(oldPath: String, newName: String): Boolean {
        val oldFile = File(oldPath)
        val roots = getPermittedRootFolders().map { it.absolutePath }
        if (AllowlistValidator.validateFilePath(oldFile.absolutePath, roots) !is AllowlistValidator.ValidationResult.Valid) {
            return false
        }
        val parent = oldFile.parentFile ?: return false
        val newFile = File(parent, newName)
        val success = oldFile.renameTo(newFile)
        AuditLogger.log("FILE", "RENAMED", "Renamed ${oldFile.name} -> $newName (success=$success)", AuditSeverity.INFO)
        return success
    }

    fun moveFile(sourcePath: String, targetDir: String): Boolean {
        val src = File(sourcePath)
        val dest = File(targetDir, src.name)
        val roots = getPermittedRootFolders().map { it.absolutePath }
        if (AllowlistValidator.validateFilePath(src.absolutePath, roots) !is AllowlistValidator.ValidationResult.Valid ||
            AllowlistValidator.validateFilePath(dest.absolutePath, roots) !is AllowlistValidator.ValidationResult.Valid) {
            return false
        }
        val success = src.renameTo(dest)
        AuditLogger.log("FILE", "MOVED", "Moved ${src.name} to $targetDir (success=$success)", AuditSeverity.INFO)
        return success
    }

    fun readFileChunk(path: String, offset: Long, length: Int): ByteArray? {
        val roots = getPermittedRootFolders().map { it.absolutePath }
        if (AllowlistValidator.validateFilePath(path, roots) !is AllowlistValidator.ValidationResult.Valid) {
            return null
        }
        val file = File(path)
        if (!file.exists() || !file.canRead()) return null

        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val buffer = ByteArray(length)
                val bytesRead = raf.read(buffer)
                if (bytesRead <= 0) return ByteArray(0)
                if (bytesRead < length) buffer.copyOf(bytesRead) else buffer
            }
        } catch (e: Exception) {
            null
        }
    }

    fun writeFileChunk(path: String, data: ByteArray, append: Boolean): Boolean {
        val roots = getPermittedRootFolders().map { it.absolutePath }
        if (AllowlistValidator.validateFilePath(path, roots) !is AllowlistValidator.ValidationResult.Valid) {
            return false
        }
        return try {
            val file = File(path)
            file.parentFile?.mkdirs()
            FileOutputStream(file, append).use { fos ->
                fos.write(data)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun calculateSha256(path: String): String? {
        val file = File(path)
        if (!file.exists() || file.isDirectory) return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    fun trackTransfer(
        id: String = UUID.randomUUID().toString(),
        fileName: String,
        totalBytes: Long,
        transferredBytes: Long,
        isUpload: Boolean,
        status: String
    ): TransferProgress {
        val percent = if (totalBytes > 0) ((transferredBytes.toDouble() / totalBytes) * 100).toInt() else 100
        val progress = TransferProgress(
            transferId = id,
            fileName = fileName,
            totalBytes = totalBytes,
            transferredBytes = transferredBytes,
            percent = percent,
            speedKbps = 1024,
            isUpload = isUpload,
            status = status
        )

        _activeTransfers.value = listOf(progress) + _activeTransfers.value.filter { it.transferId != id }.take(10)
        if (status == "COMPLETED" || status == "FAILED") {
            _transferHistory.value = listOf(progress) + _transferHistory.value.take(20)
        }
        return progress
    }

    private fun formatFileSize(bytes: Long, isDir: Boolean): String {
        if (isDir) return "--"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        val gb = mb / 1024.0
        return "%.2f GB".format(gb)
    }

    private fun getMimeType(file: File): String {
        if (file.isDirectory) return "inode/directory"
        val ext = file.extension.lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "mp4" -> "video/mp4"
            "pdf" -> "application/pdf"
            "txt", "log" -> "text/plain"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
    }
}
