package cn.local.imagepdf.merge

import android.content.Context
import android.util.AtomicFile
import cn.local.imagepdf.ui.ResultUi
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/** Merge drafts never share the image tool's session file or its source directory. */
internal class MergeStateStore(private val context: Context) {
    private val file = AtomicFile(File(context.filesDir, "merge-session.json"))
    private val sourcesRoot get() = File(context.filesDir, "merge-sources").canonicalFile
    private val resultsRoot get() = File(context.filesDir, "results").canonicalFile

    fun write(state: MergeState) {
        val data = JSONObject().put("version", 1).put("active", state.task != null || state.importing)
            .put("interrupted", state.interrupted).put("outputName", state.outputName)
        data.put("entries", JSONArray().apply {
            state.entries.forEach { entry -> put(JSONObject().apply {
                put("id", entry.id); put("originalUri", entry.originalUri); put("sourcePath", entry.sourcePath)
                put("name", entry.name); put("pages", entry.pages); put("bytes", entry.bytes)
                put("issue", entry.issue ?: JSONObject.NULL)
            }) }
        })
        state.result?.let { result -> data.put("result", JSONObject().apply {
            put("path", result.filePath); put("name", result.name); put("pages", result.pageCount)
            put("exported", result.exported)
        }) }
        val output = file.startWrite()
        try {
            output.write(data.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (failure: Throwable) { file.failWrite(output); throw failure }
    }

    fun read(): MergeState {
        if (!file.baseFile.exists()) return MergeState()
        return try {
            if (file.baseFile.length() > 4L * 1024 * 1024) throw IOException("合并草稿过大。")
            val data = JSONObject(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
            val rows = data.optJSONArray("entries") ?: JSONArray()
            if (rows.length() > PdfMergeLimits.MAX_FILES) throw IOException("合并草稿条目异常。")
            val entries = (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                val candidate = File(row.optString("sourcePath")).canonicalFile
                val owned = candidate.path.startsWith(sourcesRoot.path + File.separator)
                val available = owned && candidate.isFile
                val pages = row.optInt("pages").takeIf { it in 0..PdfMergeLimits.MAX_PAGES } ?: 0
                val savedIssue = if (row.isNull("issue")) null else row.optString("issue").ifBlank { null }
                MergeEntry(row.getString("id"), row.optString("originalUri"),
                    if (owned) candidate.path else "", row.optString("name", "PDF 文档"), pages,
                    if (available) candidate.length() else row.optLong("bytes").coerceAtLeast(0),
                    if (!available) "PDF 缓存已失效，请移除此项后重新选择。" else savedIssue)
            }
            val result = data.optJSONObject("result")?.let { row ->
                val candidate = File(row.optString("path")).canonicalFile
                val parent = candidate.parentFile
                val owned = parent != null && parent.parentFile?.canonicalFile == resultsRoot && parent.name.startsWith("merge-")
                val pages = row.optInt("pages")
                if (owned && candidate.isFile && candidate.extension.equals("pdf", true) &&
                    pages in 1..PdfMergeLimits.MAX_PAGES) {
                    ResultUi(candidate.path, row.optString("name", candidate.name), pages,
                        candidate.length(), row.optBoolean("exported"))
                } else null
            }
            val interrupted = data.optBoolean("active") || data.optBoolean("interrupted")
            MergeState(entries = entries, outputName = data.optString("outputName", "合并文档"),
                result = result, interrupted = interrupted,
                notice = when {
                    interrupted -> "上次 PDF 合并或保存被中断，已保留文件顺序，请重新开始；完整结果仍可另存。"
                    data.has("result") && result == null -> "上次合并结果已无法读取，请检查源文件后重新生成。"
                    else -> null
                })
        } catch (_: Exception) {
            MergeState(notice = "上次 PDF 合并草稿无法读取，请重新选择文件。已有完整结果没有被删除。")
        }
    }

    fun cleanIncompleteFiles() {
        // Scope cleanup to this tool even when the image runtime initializes in parallel.
        File(context.filesDir, "merge-sources").takeIf { it.exists() }?.walkTopDown()
            ?.filter { it.isFile && it.extension in setOf("partial", "copying") }?.forEach { it.delete() }
        // PDFBox temporary names need not end in .partial. These caches are merge-only and
        // startup cleanup completes before this runtime accepts any new operation.
        listOf("merge-work", "pdf-merge-import").forEach { name ->
            File(context.cacheDir, name).takeIf { it.exists() }?.deleteRecursively()
        }
        File(context.filesDir, "results").listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("merge-") }
            .forEach { directory ->
                directory.listFiles().orEmpty().filter { it.isFile && it.extension == "partial" }.forEach { it.delete() }
                File(directory, "scratch").takeIf { it.exists() }?.deleteRecursively()
            }
    }
}
