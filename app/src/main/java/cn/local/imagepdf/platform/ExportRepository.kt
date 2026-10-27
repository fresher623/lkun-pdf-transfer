package cn.local.imagepdf.platform

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class ExportRepository(context: Context) {
    private val app = context.applicationContext

    /** target must be the fresh URI returned by ACTION_CREATE_DOCUMENT, never an existing user file. */
    suspend fun export(file: File, target: Uri, onProgress: (Long, Long) -> Unit = { _, _ -> }): Unit =
        withContext(Dispatchers.IO) {
            requireOwnedResult(file)
            var copied = 0L
            try {
                currentCoroutineContext().ensureActive()
                val size = file.length()
                onProgress(0, size)
                file.inputStream().buffered().use { source ->
                    (app.contentResolver.openOutputStream(target, "w")
                        ?: throw IOException("保存位置无法写入，请选择其他文件夹。" )).buffered().use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = source.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            copied += count
                            onProgress(copied, size)
                        }
                        currentCoroutineContext().ensureActive()
                        output.flush()
                    }
                }
                currentCoroutineContext().ensureActive()
                if (copied != file.length()) throw IOException("保存不完整，请重新选择保存位置。")
                // Only the caller may announce success after this returns and both streams have closed.
            } catch (failure: Throwable) {
                try { DocumentsContract.deleteDocument(app.contentResolver, target) }
                catch (_: Exception) { /* Provider may not support delete; caller explains partial target. */ }
                throw failure
            }
        }

    fun shareIntent(file: File): Intent {
        val uri = contentUri(file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("PDF", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun openIntent(file: File): Intent {
        val uri = contentUri(file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            clipData = ClipData.newRawUri("PDF", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun contentUri(file: File): Uri {
        requireOwnedResult(file)
        return FileProvider.getUriForFile(app, "${app.packageName}.files", file)
    }

    private fun requireOwnedResult(file: File) {
        val results = File(app.filesDir, "results").canonicalFile
        require(file.canonicalFile.path.startsWith(results.path + File.separator) &&
            file.extension.equals("pdf", ignoreCase = true) && file.isFile) { "仅可访问应用内已生成的 PDF 结果。" }
    }
}
