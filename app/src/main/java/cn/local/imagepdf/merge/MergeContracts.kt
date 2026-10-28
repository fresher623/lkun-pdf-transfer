package cn.local.imagepdf.merge

import cn.local.imagepdf.ui.ResultUi
import cn.local.imagepdf.ui.TaskUi

data class MergeEntry(
    val id: String,
    val originalUri: String,
    val sourcePath: String,
    val name: String,
    val pages: Int,
    val bytes: Long,
    val issue: String? = null,
)

data class MergeState(
    val entries: List<MergeEntry> = emptyList(),
    val outputName: String = "合并文档",
    val importing: Boolean = false,
    val importDone: Int = 0,
    val importTotal: Int = 0,
    val task: TaskUi? = null,
    val result: ResultUi? = null,
    val error: String? = null,
    val notice: String? = null,
    val interrupted: Boolean = false,
    val duplicateCount: Int = 0,
    val otherTaskActive: Boolean = false,
) {
    val totalPages: Int get() = entries.sumOf { it.pages }
    val totalBytes: Long get() = entries.sumOf { it.bytes }
}

interface MergeActions {
    fun pickPdfs()
    fun move(fromId: String, toId: String)
    fun remove(id: String)
    fun clear()
    fun setOutputName(name: String)
    fun generate()
    fun cancel()
    fun save()
    fun share()
    fun openExternal()
    fun newTask()
    fun dismissError()
    fun dismissNotice()
    fun confirmDuplicates(allow: Boolean)
    fun returnToImages()
}
