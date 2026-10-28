package cn.local.imagepdf

import android.content.Context
import android.util.AtomicFile
import cn.local.imagepdf.core.*
import cn.local.imagepdf.ui.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A small, atomic draft/journal. Never serializes pixels or sensitive URIs to logs. */
internal class StateStore(private val context: Context) {
    private val file = AtomicFile(File(context.filesDir, "session.json"))

    fun write(state: AppUiState) {
        val json = JSONObject().put("version", 1)
            .put("active", state.task != null).put("screen", state.screen.name)
            .put("sort", state.sortLabel).put("settings", settingsJson(state.settings))
        json.put("images", JSONArray().apply {
            state.images.forEach { item -> put(JSONObject().apply {
                put("id", item.id); put("uri", item.uri); put("name", item.name)
                put("width", item.width); put("height", item.height)
                put("exif", item.exifOrientation); put("rotation", item.rotation)
                put("order", item.originalOrder); put("taken", item.takenAt ?: JSONObject.NULL)
                put("error", item.error ?: JSONObject.NULL)
                put("originalUri", item.originalUri ?: JSONObject.NULL)
            }) }
        })
        state.result?.let { result -> json.put("result", JSONObject().apply {
            put("path", result.filePath); put("name", result.name); put("pages", result.pageCount)
            put("bytes", result.bytes); put("exported", result.exported)
        }) }
        val stream = file.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Throwable) { file.failWrite(stream); throw error }
    }

    fun read(): AppUiState {
        if (!file.baseFile.exists()) return AppUiState()
        return try {
            val json = JSONObject(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
            val images = json.optJSONArray("images") ?: JSONArray()
            val entries = (0 until minOf(images.length(), MAX_IMAGES)).map { index ->
                val item = images.getJSONObject(index)
                ImageEntry(item.getString("id"), item.getString("uri"), item.getString("name"),
                    item.getInt("width"), item.getInt("height"), item.optInt("exif", 1),
                    item.optInt("rotation"), item.optLong("order", index.toLong()),
                    if (item.isNull("taken")) null else item.getLong("taken"),
                    if (item.isNull("error")) null else item.getString("error"),
                    if (item.isNull("originalUri")) null else item.getString("originalUri"))
            }
            val result = json.optJSONObject("result")?.let { r ->
                val candidate = File(r.getString("path"))
                val root = File(context.filesDir, "results").canonicalFile
                if (candidate.isFile && candidate.canonicalPath.startsWith(root.path + File.separator))
                    ResultUi(candidate.path, r.getString("name"), r.getInt("pages"),
                        candidate.length(), r.optBoolean("exported")) else null
            }
            val interrupted = json.optBoolean("active")
            AppUiState(
                screen = when { result != null -> AppScreen.RESULT; entries.isNotEmpty() -> AppScreen.ORGANIZE; else -> AppScreen.HOME },
                images = entries, settings = readSettings(json.optJSONObject("settings")),
                sortLabel = json.optString("sort", "导入顺序"), result = result, interrupted = interrupted,
                notice = if (interrupted) "上次任务被系统中断。已保留图片顺序，请重新生成；已完成的文件仍可另存。" else null
            )
        } catch (_: Exception) {
            AppUiState(notice = "上次草稿无法读取，请重新选择图片。已完成的应用内文件没有被删除。")
        }
    }

    fun cleanIncompleteFiles() {
        // Results are retained. Only uncommitted/intermediate files from a dead process are removed.
        File(context.filesDir, "results").listFiles().orEmpty().filter {
            it.isDirectory && !it.name.startsWith("merge-")
        }.flatMap { it.walkTopDown().toList() }.filter {
            it.isFile && (it.extension == "partial" || it.name == "page.jpeg" || it.name.startsWith("page-") && it.extension == "jpg")
        }.forEach { it.delete() }
        File(context.cacheDir, "conversion").takeIf { it.exists() }?.deleteRecursively()
    }

    private fun settingsJson(s: OutputSettings) = JSONObject().apply {
        put("name", s.fileName); put("size", s.pageSize.name); put("orientation", s.orientation.name)
        put("layout", s.layout.name); put("margin", s.marginMm); put("quality", s.quality.name)
        put("numbers", s.pageNumbers)
    }
    private fun readSettings(j: JSONObject?): OutputSettings {
        if (j == null) return OutputSettings()
        return runCatching { OutputSettings(j.getString("name"), PageSize.valueOf(j.getString("size")),
            Orientation.valueOf(j.getString("orientation")), LayoutMode.valueOf(j.getString("layout")),
            j.getInt("margin").takeIf { it in listOf(0, 5, 10, 15) } ?: 10,
            Quality.valueOf(j.getString("quality")), j.optBoolean("numbers")) }.getOrDefault(OutputSettings())
    }
}
