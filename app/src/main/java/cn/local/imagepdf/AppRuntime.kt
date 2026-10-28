package cn.local.imagepdf

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import cn.local.imagepdf.core.*
import cn.local.imagepdf.platform.*
import cn.local.imagepdf.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.UUID

/** Application-scoped state survives Activity recreation; the foreground service owns long work. */
class AppRuntime(private val context: Context, private val taskGate: TaskGate = TaskGate()) {
    val repository = ImageRepository(context)
    private val folders = FolderRepository(context)
    private var folderJob: Job? = null
    private val converter = ImageConverter(context)
    private val exporter = ExportRepository(context)
    private val store = StateStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(AppUiState(importing = true))
    val state: StateFlow<AppUiState> = mutable.asStateFlow()
    private val writes = Channel<Unit>(Channel.CONFLATED)
    private val storeMutex = kotlinx.coroutines.sync.Mutex()
    private var ready = false
    private var pendingUris = emptyList<Uri>()
    private var pending: Operation? = null
    private var activeJob: Job? = null
    private var cancelRequested = false
    private var operationActive = false
    private var operationLease: TaskGate.Lease? = null
    private data class ExportSelection(val result: ResultUi, val lease: TaskGate.Lease)
    private var exportSelection: ExportSelection? = null
    private var exportWaiting = false

    private sealed interface Operation {
        data class Generate(val images: List<ImageEntry>, val settings: OutputSettings, val id: String) : Operation
        data class Export(val result: ResultUi, val target: Uri) : Operation
    }

    init {
        scope.launch {
            val restored = withContext(Dispatchers.IO) {
                store.read().also { runCatching { store.cleanIncompleteFiles() } }
            }
            mutable.value = restored
            ready = true
            safelyPersist()
            runCatching { repository.releaseUnusedSources(restored.images) }
        }
        scope.launch {
            for (ignored in writes) {
                delay(150)
                if (ready) runCatching { persistNow() }.onFailure {
                    mutable.update { s -> s.copy(notice = "草稿暂时无法保存，请检查设备剩余空间。") }
                }
            }
        }
    }

    private fun change(transform: (AppUiState) -> AppUiState) {
        mutable.update(transform)
        writes.trySend(Unit)
    }
    private suspend fun persistNow() {
        storeMutex.lock()
        try { val snapshot = state.value; withContext(Dispatchers.IO) { store.write(snapshot) } }
        finally { storeMutex.unlock() }
    }
    private suspend fun safelyPersist(): Boolean = try { persistNow(); true }
        catch (_: Exception) { mutable.update { it.copy(notice = "草稿暂时无法保存，请检查设备剩余空间。") }; false }
    private suspend fun persistCompletion(transform: (AppUiState) -> AppUiState, release: () -> Unit) {
        storeMutex.lock()
        try {
            val completed = transform(state.value)
            try { withContext(Dispatchers.IO) { store.write(completed) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(notice = "草稿暂时无法保存，请检查设备剩余空间。") } }
            release()
            // Keep the journal lock through publication so an older queued write cannot replace
            // the completed snapshot with an active=true snapshot after this final write.
            change(transform)
        } finally { storeMutex.unlock() }
    }
    private fun canEdit() = ready && !exportWaiting && taskGate.owner == null && !operationActive && state.value.task == null &&
        !state.value.importing && !state.value.readingFolder && state.value.folderSelection == null
    fun canPick(): Boolean {
        if (taskGate.owner == "merge") { notice("PDF 合并任务正在处理，请稍后再开始图片操作。"); return false }
        return canEdit()
    }

    fun openFolder(uri: Uri?) {
        if (uri == null || !canEdit()) return
        val remaining = MAX_IMAGES - state.value.images.size
        if (remaining <= 0) { error("已达到 500 张上限，请先移除部分图片。"); return }
        change { it.copy(readingFolder = true) }
        folderJob = scope.launch {
            try {
                val selection = folders.listImages(uri, remaining)
                change { it.copy(readingFolder = false, folderSelection = selection) }
            } catch (_: CancellationException) { change { it.copy(readingFolder = false) } }
            catch (problem: Exception) {
                change { it.copy(readingFolder = false, error = problem.message
                    ?.takeIf { message -> message.any { c -> c.code in 0x4e00..0x9fff } }
                    ?: "无法读取这个文件夹，请选择图片所在的具体文件夹并允许读取。") }
            }
        }
    }
    fun dismissFolder() {
        folderJob?.cancel()
        change { it.copy(readingFolder = false, folderSelection = null) }
    }
    fun importFolder(uris: List<String>) {
        if (taskGate.owner != null) { notice("另一个 PDF 任务正在处理，已保留当前图片选择，请稍后再导入。"); return }
        val selection = state.value.folderSelection ?: return
        val selected = uris.distinct()
        val allowed = selection.images.map { it.uri }.toHashSet()
        change { it.copy(folderSelection = null) }
        if (selected.isEmpty() || selected.size > selection.remaining || selected.any { it !in allowed }) {
            error("请选择 1～${selection.remaining} 张图片后再导入。")
            return
        }
        importSelection(selected.map(Uri::parse))
    }

    fun importSelection(uris: List<Uri>) {
        if (uris.isEmpty() || !canEdit()) return
        val remaining = MAX_IMAGES - state.value.images.size
        if (remaining <= 0) { error("已达到 500 张上限，请先移除部分图片。"); return }
        if (uris.size > remaining) {
            pendingUris = uris
            change { it.copy(pendingImportCount = uris.size) }
        } else beginImport(uris)
    }
    fun confirmImport(allow: Boolean) {
        val selected = pendingUris
        pendingUris = emptyList()
        change { it.copy(pendingImportCount = 0) }
        if (allow && canEdit()) beginImport(selected.take(MAX_IMAGES - state.value.images.size))
    }
    private fun beginImport(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val importLease = taskGate.tryAcquire("image") ?: run { notice("另一个 PDF 任务正在处理，请稍后再导入图片。"); return }
        val existing = state.value.images
        val duplicateCount = uris.size - uris.distinct().size + uris.distinct().count { uri ->
            existing.any { (it.originalUri ?: it.uri) == uri.toString() }
        }
        change { it.copy(importing = true, importDone = 0, importTotal = uris.size, screen = AppScreen.ORGANIZE) }
        scope.launch {
            try {
                val nextOrder = (existing.maxOfOrNull { it.originalOrder } ?: -1) + 1
                val imported = repository.importImages(uris, nextOrder) { done, total ->
                    mutable.update { it.copy(importDone = done, importTotal = total) }
                }
                change { it.copy(images = existing + imported, sortLabel = "自定义顺序",
                    notice = listOfNotNull(
                        if (duplicateCount > 0) "包含 $duplicateCount 张重复图片，已按选择保留。" else null,
                        if (imported.any { item -> item.error != null }) "部分图片无法读取，请查看异常标记并重新选择或删除。" else null
                    ).joinToString("\n").ifBlank { null }) }
                // Publish completion only after editing can actually acquire the shared gate.
                persistCompletion({ it.copy(importing = false) }) { importLease.close() }
            } catch (_: CancellationException) {
                importLease.close()
                change { it.copy(importing = false) }
            } catch (_: Exception) {
                importLease.close()
                change { it.copy(importing = false, error = "导入失败，请检查图片是否已下载、读取权限及设备空间后重试。") }
            } finally { importLease.close() }
        }
    }

    fun move(fromId: String, toId: String) {
        if (!canEdit() || fromId == toId) return
        change { s ->
            val list = s.images.toMutableList()
            val from = list.indexOfFirst { it.id == fromId }; val to = list.indexOfFirst { it.id == toId }
            if (from < 0 || to < 0) s else { list.add(to, list.removeAt(from)); s.copy(images = list, sortLabel = "自定义顺序") }
        }
    }
    fun rotate(id: String) { if (canEdit()) change { it.copy(images = it.images.map { item -> if (item.id == id) item.copy(rotation = (item.rotation + 90) % 360) else item }) } }
    fun delete(id: String) { if (canEdit()) { change { it.copy(images = it.images.filterNot { item -> item.id == id }) }; cleanSources() } }
    fun clear() { if (canEdit()) { change { it.copy(images = emptyList(), screen = AppScreen.HOME, sortLabel = "导入顺序") }; cleanSources() } }
    private fun cleanSources() {
        scope.launch {
            if (safelyPersist() && canEdit()) runCatching {
                repository.releaseUnusedSources(state.value.images)
            }
        }
    }
    fun sort(order: SortOrder) {
        if (!canEdit()) return
        change { s -> s.copy(images = when (order) {
            SortOrder.IMPORT -> s.images.sortedBy { it.originalOrder }
            SortOrder.NAME -> s.images.sortedWith { a, b -> naturalCompare(a.name, b.name).takeIf { it != 0 } ?: a.originalOrder.compareTo(b.originalOrder) }
            SortOrder.TAKEN -> s.images.sortedWith(compareBy<ImageEntry> { it.takenAt == null }.thenBy { it.takenAt }.thenBy { it.originalOrder })
        }, sortLabel = when (order) { SortOrder.IMPORT -> "导入顺序"; SortOrder.NAME -> "文件名升序（1、2、10）"; SortOrder.TAKEN -> "拍摄时间升序（缺失在末尾）" }) }
    }
    fun updateSettings(settings: OutputSettings) { if (canEdit()) change { it.copy(settings = settings) } }
    fun navigate(screen: AppScreen) {
        if (screen == AppScreen.SETTINGS && (state.value.images.isEmpty() || !canEdit())) return
        if (screen == AppScreen.RESULT && state.value.result == null) return
        change { it.copy(screen = screen) }
    }
    fun generate() {
        if (!canEdit()) return
        val s = state.value
        if (s.images.size !in 1..MAX_IMAGES) { error("请选择 1～500 张图片。"); return }
        if (s.images.any { it.error != null }) { error("请先删除或重新选择带异常标记的图片，再生成 PDF。"); return }
        val settings = s.settings.copy(fileName = sanitizeFileName(s.settings.fileName))
        operationLease = taskGate.tryAcquire("image") ?: run { notice("另一个 PDF 任务正在处理，请稍后再开始。"); return }
        pending = Operation.Generate(s.images.toList(), settings, UUID.randomUUID().toString())
        operationActive = true
        change { it.copy(settings = settings, task = TaskUi(total = s.images.size), screen = AppScreen.PROGRESS, interrupted = false) }
        startService()
    }
    /** Hold the selected result and gate across the external CreateDocument activity. */
    fun prepareExportSelection(): ResultUi? {
        if (!canEdit()) { notice("另一个 PDF 任务正在处理，请稍后再保存。"); return null }
        val result = state.value.result ?: return null
        val lease = taskGate.tryAcquire("image") ?: return null
        exportSelection = ExportSelection(result, lease)
        return result
    }
    fun cancelExportSelection() {
        exportSelection?.lease?.close()
        exportSelection = null
    }
    fun export(target: Uri?) {
        val selection = exportSelection
        exportSelection = null
        if (target == null) {
            selection?.lease?.close()
            notice("已取消另存，生成的 PDF 仍可预览、重新保存或分享。"); return
        }
        if (selection != null) { beginExport(selection.result, target, selection.lease); return }
        // Compatibility for direct callers and a picker result restored after process death.
        // Never discard a newly selected destination merely because startup/another tool is busy.
        if (exportWaiting) { notice("已有保存请求正在等待处理，请稍候。"); return }
        val frozenResult = state.value.result
        exportWaiting = true
        scope.launch {
            var acquired: TaskGate.Lease? = null
            try {
                while (!ready) delay(25)
                val result = frozenResult ?: state.value.result
                if (result == null) { error("完整 PDF 已无法读取，请重新生成后再保存。"); return@launch }
                if (taskGate.owner != null || operationActive || state.value.importing) notice("正在等待当前任务结束，随后保存已选中的 PDF。")
                while (acquired == null) {
                    if (!operationActive && state.value.task == null && !state.value.importing &&
                        !state.value.readingFolder && state.value.folderSelection == null) acquired = taskGate.tryAcquire("image")
                    if (acquired == null) delay(25)
                }
                exportWaiting = false
                beginExport(result, target, acquired!!)
                acquired = null // Operation cleanup now owns this lease.
            } finally { acquired?.close(); exportWaiting = false }
        }
    }
    private fun beginExport(result: ResultUi, target: Uri, lease: TaskGate.Lease) {
        operationLease = lease
        pending = Operation.Export(result, target)
        operationActive = true
        change { it.copy(task = TaskUi(stage = "正在保存", total = result.pageCount, exporting = true, totalBytes = result.bytes), screen = AppScreen.PROGRESS) }
        startService()
    }
    private fun startService() {
        cancelRequested = false
        activeJob = scope.launch {
            try {
                persistNow()
                ensureActive()
                ContextCompat.startForegroundService(context, Intent(context, ConversionService::class.java))
            } catch (_: CancellationException) { operationActive = false; releaseOperation(); finishCancelled() }
            catch (_: Exception) {
                pending = null
                operationActive = false
                releaseOperation()
                change { it.copy(task = null, screen = if (it.result != null) AppScreen.RESULT else AppScreen.SETTINGS,
                    error = "系统暂时无法启动后台任务，请保持应用在前台后重试。") }
            }
        }
    }
    internal fun executePending(service: ConversionService) {
        val operation = pending
        if (operation == null || cancelRequested) {
            service.finishWork(); operationActive = false; releaseOperation(); finishCancelled(); return
        }
        repository.clearThumbnailCache()
        activeJob = scope.launch {
            try {
                when (operation) {
                    is Operation.Generate -> {
                        val output = withContext(Dispatchers.IO) {
                            converter.convert(operation.images, operation.settings, operation.id) { completed, total, stage ->
                                mutable.update { s -> s.copy(task = s.task?.copy(stage = stage, completed = completed, total = total)) }
                            }
                        }
                        ensureActive()
                        val result = ResultUi(output.path, operation.settings.fileName, operation.images.size, output.length())
                        change { it.copy(result = result, screen = AppScreen.RESULT) }
                    }
                    is Operation.Export -> {
                        withContext(Dispatchers.IO) { exporter.export(File(operation.result.filePath), operation.target) { copied, total ->
                            mutable.update { s -> s.copy(task = s.task?.copy(copiedBytes = copied, totalBytes = total)) }
                        } }
                        // A fully closed successful copy wins over a late cancel tap.
                        change { it.copy(result = operation.result.copy(exported = true), screen = AppScreen.RESULT, notice = "保存成功。") }
                    }
                }
            } catch (_: CancellationException) { finishCancelledAfterOperation(operation, keepTask = true) }
            catch (problem: ImageProcessingException) {
                change { s -> s.copy(screen = AppScreen.ORGANIZE,
                    images = s.images.map { if (it.id == problem.itemId) it.copy(error = problem.message) else it },
                    error = problem.message ?: "图片处理失败，请删除异常图片后重试。") }
            } catch (problem: Exception) {
                change { it.copy(screen = if (operation is Operation.Export) AppScreen.RESULT else AppScreen.SETTINGS,
                    error = problem.message?.takeIf { msg -> msg.any { char -> char.code in 0x4e00..0x9fff } }
                        ?: if (operation is Operation.Export) "保存失败，目标可能存在不完整文件。已保留完整 PDF，请换个位置重试。"
                        else "生成失败，请检查图片读取权限和设备空间后重试。") }
            } finally {
                pending = null
                // Until persistence and service cleanup finish, keep the task visibly busy.
                // An observer of task == null can now immediately start/edit without a lost tap.
                withContext(NonCancellable) {
                    persistCompletion({ it.copy(task = null) }) {
                        service.finishWork()
                        operationActive = false
                        activeJob = null
                        releaseOperation()
                    }
                }
            }
        }
    }
    private fun releaseOperation() { operationLease?.close(); operationLease = null }
    private suspend fun finishCancelledAfterOperation(operation: Operation, keepTask: Boolean = false) {
        val committed = if (operation is Operation.Generate) withContext(NonCancellable + Dispatchers.IO) {
            File(context.filesDir, "results/${operation.id}/${sanitizeFileName(operation.settings.fileName)}")
                .takeIf { it.isFile && it.length() > 0 }
        } else null
        if (committed != null && operation is Operation.Generate) {
            // The converter only renames to this task-specific path after complete validation.
            change { it.copy(task = if (keepTask) it.task else null, result = ResultUi(committed.path, committed.name, operation.images.size, committed.length()),
                screen = AppScreen.RESULT, notice = "取消到达时 PDF 已生成完成，完整结果已保留，请预览或另存。") }
        } else finishCancelled(keepTask)
    }
    fun cancel() {
        if (state.value.task == null) return
        cancelRequested = true
        change { it.copy(task = it.task?.copy(cancelling = true, stage = "正在取消，等待当前步骤结束")) }
        activeJob?.cancel()
    }
    private fun finishCancelled(keepTask: Boolean = false) {
        pending = null
        change { it.copy(task = if (keepTask) it.task else null, screen = if (it.result != null) AppScreen.RESULT else AppScreen.ORGANIZE,
            notice = "任务已取消，图片顺序已保留。已生成的完整文件仍可使用。") }
    }
    fun newTask() {
        if (!canEdit()) return
        change { AppUiState(settings = OutputSettings()) }
        cleanSources()
    }
    fun dismissError() { change { it.copy(error = null) } }
    fun dismissNotice() { change { it.copy(notice = null) } }
    fun notice(message: String) { change { it.copy(notice = message) } }
    fun error(message: String) { change { it.copy(error = message) } }
    fun shareIntent() = runCatching { state.value.result?.let { exporter.shareIntent(File(it.filePath)) } }
        .getOrElse { error("应用内 PDF 已无法读取，请重新生成后分享。"); null }
    fun openIntent() = runCatching { state.value.result?.let { exporter.openIntent(File(it.filePath)) } }
        .getOrElse { error("应用内 PDF 已无法读取，请重新生成或重新选择文件。"); null }
}
