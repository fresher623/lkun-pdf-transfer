package cn.local.imagepdf.ui

import cn.local.imagepdf.core.ImageEntry
import cn.local.imagepdf.core.OutputSettings

enum class AppScreen { HOME, ORGANIZE, SETTINGS, PROGRESS, RESULT }
enum class SortOrder { IMPORT, NAME, TAKEN }

data class FolderImage(val uri: String, val name: String)
data class FolderSelection(val folderName: String, val images: List<FolderImage>, val remaining: Int)

data class TaskUi(
    val stage: String = "准备中",
    val completed: Int = 0,
    val total: Int = 0,
    val cancelling: Boolean = false,
    val exporting: Boolean = false,
    val copiedBytes: Long = 0,
    val totalBytes: Long = 0,
)

data class ResultUi(
    val filePath: String,
    val name: String,
    val pageCount: Int,
    val bytes: Long,
    val exported: Boolean = false,
)

data class AppUiState(
    val screen: AppScreen = AppScreen.HOME,
    val images: List<ImageEntry> = emptyList(),
    val settings: OutputSettings = OutputSettings(),
    val importing: Boolean = false,
    val importDone: Int = 0,
    val importTotal: Int = 0,
    val sortLabel: String = "导入顺序",
    val task: TaskUi? = null,
    val result: ResultUi? = null,
    val error: String? = null,
    val notice: String? = null,
    val pendingImportCount: Int = 0,
    val interrupted: Boolean = false,
    val folderSelection: FolderSelection? = null,
    val readingFolder: Boolean = false,
)

interface AppActions {
    fun pickPhotos()
    fun pickFiles()
    fun pickFolder()
    fun importFolder(uris: List<String>)
    fun dismissFolder()
    fun confirmImport(allow: Boolean)
    fun move(fromId: String, toId: String)
    fun rotate(id: String)
    fun delete(id: String)
    fun clear()
    fun sort(order: SortOrder)
    fun updateSettings(settings: OutputSettings)
    fun navigate(screen: AppScreen)
    fun generate()
    fun cancel()
    fun save()
    fun share()
    fun openExternal()
    fun newTask()
    fun dismissError()
    fun dismissNotice()
}
