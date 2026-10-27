package cn.local.imagepdf.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import cn.local.imagepdf.core.MAX_IMAGES
import cn.local.imagepdf.core.naturalCompare
import cn.local.imagepdf.ui.FolderImage
import cn.local.imagepdf.ui.FolderSelection
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Enumerates metadata from the selected folder's current level; never decodes or copies images. */
class FolderRepository(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun listImages(treeUri: Uri, remaining: Int): FolderSelection = withContext(Dispatchers.IO) {
        require(remaining in 0..MAX_IMAGES) { "可添加数量必须在 0～500 之间。" }
        currentCoroutineContext().ensureActive()
        if (treeUri.scheme != "content" || !DocumentsContract.isTreeUri(treeUri)) {
            throw IOException("请选择一个可访问的图片文件夹。")
        }
        try {
            try {
                resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Some providers grant access only for this session. The importer later copies
                // selected sources when a persistent individual-document grant is unavailable.
            } catch (_: UnsupportedOperationException) {
                // A transient tree grant is still usable for listing and immediate import.
            }
            currentCoroutineContext().ensureActive()
            val directoryId = DocumentsContract.getTreeDocumentId(treeUri)
            val directory = DocumentsContract.buildDocumentUriUsingTree(treeUri, directoryId)
            val folderName = resolver.query(directory, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf(String::isNotBlank) else null
            } ?: "所选文件夹"

            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, directoryId)
            val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
            val images = mutableListOf<FolderImage>()
            val cursor = resolver.query(children, projection, null, null, null)
                ?: throw IOException("无法读取文件夹内容，请重新选择或检查读取权限。")
            cursor.use {
                val idColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                var entries = 0
                while (it.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    if (++entries > MAX_FOLDER_ENTRIES) {
                        throw IOException("当前文件夹超过 10000 个项目，请将图片分到较小的文件夹后重试；本次没有导入。")
                    }
                    val mime = it.getString(mimeColumn).orEmpty()
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val name = it.getString(nameColumn)?.takeIf(String::isNotBlank) ?: "未命名图片"
                    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (!mime.startsWith("image/", ignoreCase = true) && extension !in IMAGE_EXTENSIONS) continue
                    val id = it.getString(idColumn)?.takeIf(String::isNotBlank)
                        ?: throw IOException("文件夹中的图片缺少文档标识，请换用系统文件选择器。")
                    images += FolderImage(DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString(), name)
                }
            }
            currentCoroutineContext().ensureActive()
            // Kotlin's object sort is stable: equal names preserve the provider's original order.
            FolderSelection(folderName, images.sortedWith { a, b -> naturalCompare(a.name, b.name) }, remaining)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            throw failure
        } catch (failure: SecurityException) {
            throw IOException("文件夹读取权限已失效，请重新选择并允许访问。", failure)
        } catch (failure: Exception) {
            throw IOException("无法读取所选文件夹，请确认文件夹仍可用后重试。", failure)
        }
    }

    private companion object {
        const val MAX_FOLDER_ENTRIES = 10_000
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif")
    }
}
