package cn.local.imagepdf.merge

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import cn.local.imagepdf.ConversionService
import cn.local.imagepdf.TaskGate
import cn.local.imagepdf.core.sanitizeFileName
import cn.local.imagepdf.platform.ExportRepository
import cn.local.imagepdf.ui.ResultUi
import cn.local.imagepdf.ui.TaskUi
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex

/** Application-scoped coordinator; every input is a private cached PDF, never an image URI grant. */
class MergeRuntime(private val app: Application, private val gate: TaskGate) {
    val repository = PdfMergeRepository(app)
    private val exports = ExportRepository(app)
    private val store = MergeStateStore(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(MergeState(importing = true))
    val state: StateFlow<MergeState> = mutable.asStateFlow()
    private val writes = Channel<Unit>(Channel.CONFLATED)
    private val storeMutex = Mutex()
    private var ready = false
    private var pendingDuplicates = emptyList<Uri>()
    private var importJob: Job? = null
    private var activeJob: Job? = null
    private var pending: Pending? = null
    private var operationActive = false
    private var serviceExecuting = false
    private var cancelRequested = false
    private data class ExportSelection(val result: ResultUi, val lease: TaskGate.Lease)
    private var exportSelection: ExportSelection? = null
    private var exportWaiting = false

    private sealed interface Operation {
        data class Generate(val entries: List<MergeEntry>, val outputName: String, val id: String) : Operation
        data class Export(val result: ResultUi, val target: Uri) : Operation
    }
    private data class Pending(val operation: Operation, val lease: TaskGate.Lease)

    init {
        scope.launch {
            val restored = withContext(Dispatchers.IO) { store.read().also { runCatching { store.cleanIncompleteFiles() } } }
            mutable.value = restored
            ready = true
            safelyPersist()
            cleanSources()
        }
        scope.launch {
            for (ignored in writes) {
                delay(150)
                if (ready) safelyPersist()
            }
        }
    }

    private fun change(transform: (MergeState) -> MergeState) { mutable.update(transform); writes.trySend(Unit) }
    private suspend fun persistNow() {
        storeMutex.lock()
        try { val snapshot = state.value; withContext(Dispatchers.IO) { store.write(snapshot) } }
        finally { storeMutex.unlock() }
    }
    private suspend fun safelyPersist(): Boolean = try { persistNow(); true }
        catch (_: Exception) { mutable.update { it.copy(notice = "合并草稿暂时无法保存，请检查设备剩余空间。") }; false }
    private suspend fun persistCompletion(transform: (MergeState) -> MergeState, release: () -> Unit) {
        storeMutex.lock()
        try {
            val completed = transform(state.value)
            try { withContext(Dispatchers.IO) { store.write(completed) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(notice = "合并草稿暂时无法保存，请检查设备剩余空间。") } }
            release()
            // Prevent a queued journal write from restoring active=true after completion.
            change(transform)
        } finally { storeMutex.unlock() }
    }
    private fun editable(ignoreDuplicates: Boolean = false) = ready && !exportWaiting && !operationActive && !state.value.importing &&
        state.value.task == null && (ignoreDuplicates || state.value.duplicateCount == 0) && gate.owner == null
    fun canPick() = editable()

    fun importSelection(uris: List<Uri>) {
        if (uris.isEmpty() || !editable()) return
        if (state.value.entries.size + uris.size > PdfMergeLimits.MAX_FILES) {
            error("单次合并最多 ${PdfMergeLimits.MAX_FILES} 个 PDF，请减少所选文件后重试。")
            return
        }
        val seen = state.value.entries.map { it.originalUri }.toMutableSet()
        val duplicates = uris.count { !seen.add(it.toString()) }
        if (duplicates > 0) {
            pendingDuplicates = uris.toList()
            change { it.copy(duplicateCount = duplicates) }
        } else beginImport(uris)
    }
    fun confirmDuplicates(allow: Boolean) {
        if (!allow) {
            pendingDuplicates = emptyList(); change { it.copy(duplicateCount = 0) }; return
        }
        if (!editable(ignoreDuplicates = true)) { notice("另一个任务正在处理，请完成后再确认导入。") ; return }
        val selected = pendingDuplicates
        pendingDuplicates = emptyList()
        change { it.copy(duplicateCount = 0) }
        if (selected.isNotEmpty()) beginImport(selected)
    }
    private fun beginImport(uris: List<Uri>) {
        val lease = gate.tryAcquire("merge") ?: run { notice("另一个工具正在处理任务，请稍后再导入。") ; return }
        val existing = state.value.entries.toList()
        change { it.copy(importing = true, importDone = 0, importTotal = uris.size) }
        importJob = scope.launch {
            try {
                persistNow()
                val added = repository.importUris(uris, existing) { done, total ->
                    mutable.update { it.copy(importDone = done, importTotal = total) }
                }.map { entry -> entry.copy(issue = entry.issue?.let { friendly(it, "PDF 无法读取，请移除此项后重新选择。") }) }
                change { it.copy(entries = existing + added,
                    notice = if (added.any { entry -> entry.issue != null }) "部分 PDF 无法读取，请查看异常提示并移除或重新选择。" else null) }
                // Completion must imply that immediate reorder/remove can acquire the gate.
                persistCompletion({ it.copy(importing = false) }) { lease.close(); importJob = null }
            } catch (_: CancellationException) {
                lease.close()
                importJob = null
                change { it.copy(importing = false, notice = "导入已取消，原有 PDF 列表已保留。") }
            } catch (failure: Exception) {
                lease.close()
                importJob = null
                change { it.copy(importing = false, error = friendly(failure.message, "PDF 导入失败，请检查文件、读取权限和设备空间后重试。")) }
            } finally {
                lease.close()
            }
        }
    }

    fun move(fromId: String, toId: String) {
        if (!editable() || fromId == toId) return
        change { old ->
            val entries = old.entries.toMutableList()
            val from = entries.indexOfFirst { it.id == fromId }; val to = entries.indexOfFirst { it.id == toId }
            if (from < 0 || to < 0) old else { entries.add(to, entries.removeAt(from)); old.copy(entries = entries) }
        }
    }
    fun remove(id: String) { if (editable()) { change { it.copy(entries = it.entries.filterNot { entry -> entry.id == id }) }; cleanSources() } }
    fun clear() { if (editable()) { change { it.copy(entries = emptyList()) }; cleanSources() } }
    fun setOutputName(name: String) { if (editable()) change { it.copy(outputName = name) } }

    fun generate() {
        if (!editable()) return
        val snapshot = state.value
        if (snapshot.entries.size !in 2..PdfMergeLimits.MAX_FILES) { error("请选择 2～${PdfMergeLimits.MAX_FILES} 份 PDF 再合并。") ; return }
        if (snapshot.entries.any { it.issue != null }) { error("请先移除或重新选择异常 PDF，再开始合并。") ; return }
        if (snapshot.totalPages !in 1..PdfMergeLimits.MAX_PAGES) { error("合并总页数必须在 1～${PdfMergeLimits.MAX_PAGES} 页之间。") ; return }
        if (snapshot.entries.any { it.bytes !in 1..PdfMergeLimits.MAX_FILE_BYTES } ||
            snapshot.totalBytes > PdfMergeLimits.MAX_TOTAL_BYTES) { error("PDF 输入超出限制：单份最多 256 MiB，合计最多 512 MiB。") ; return }
        if (snapshot.outputName.isBlank()) { error("请输入合并后的 PDF 文件名。") ; return }
        val lease = gate.tryAcquire("merge") ?: run { notice("另一个工具正在处理任务，请稍后再合并。") ; return }
        val name = sanitizeFileName(snapshot.outputName)
        start(Operation.Generate(snapshot.entries.toList(), name, "merge-${UUID.randomUUID()}"), lease,
            TaskUi(stage = "准备合并", total = snapshot.totalPages))
        change { it.copy(outputName = name, interrupted = false) }
    }
    /** Freeze the result and reserve exclusivity before opening the external save picker. */
    fun prepareExportSelection(): ResultUi? {
        if (!editable()) { notice("另一个工具正在处理任务，请稍后再保存。"); return null }
        val result = state.value.result ?: return null
        val lease = gate.tryAcquire("merge") ?: return null
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
            notice("已取消另存，完整合并结果仍可预览、保存或分享。"); return
        }
        if (selection != null) { beginExport(selection.result, target, selection.lease); return }
        // Direct callers and callbacks restored after process death wait instead of silently
        // abandoning the fresh destination. Only app-owned result files are read, never deleted.
        if (exportWaiting) { notice("已有保存请求正在等待处理，请稍候。"); return }
        val frozenResult = state.value.result
        exportWaiting = true
        scope.launch {
            var acquired: TaskGate.Lease? = null
            try {
                while (!ready) delay(25)
                val result = frozenResult ?: state.value.result
                if (result == null) { error("完整合并结果已无法读取，请重新合并后再保存。"); return@launch }
                if (gate.owner != null || operationActive || state.value.importing) notice("正在等待当前任务结束，随后保存已选中的 PDF。")
                while (acquired == null) {
                    if (!operationActive && state.value.task == null && !state.value.importing) acquired = gate.tryAcquire("merge")
                    if (acquired == null) delay(25)
                }
                exportWaiting = false
                beginExport(result, target, acquired!!)
                acquired = null
            } finally { acquired?.close(); exportWaiting = false }
        }
    }
    private fun beginExport(result: ResultUi, target: Uri, lease: TaskGate.Lease) {
        start(Operation.Export(result, target), lease,
            TaskUi(stage = "正在保存", total = result.pageCount, exporting = true, totalBytes = result.bytes))
    }
    private fun start(operation: Operation, lease: TaskGate.Lease, task: TaskUi) {
        val run = Pending(operation, lease)
        pending = run; operationActive = true; cancelRequested = false
        change { it.copy(task = task, error = null) }
        activeJob = scope.launch {
            try {
                persistNow(); ensureActive()
                ContextCompat.startForegroundService(app, Intent(app, ConversionService::class.java).putExtra("tool", "merge"))
            } catch (_: CancellationException) {
                if (pending === run) { pending = null; operationActive = false; activeJob = null; run.lease.close() }
                finishCancelled()
            } catch (failure: Exception) {
                if (pending === run) { pending = null; operationActive = false; activeJob = null; run.lease.close() }
                change { it.copy(task = null, error = friendly(failure.message, "系统无法启动后台合并，请保持应用在前台后重试。")) }
            }
        }
    }

    fun executePending(service: ConversionService) {
        if (serviceExecuting) return
        val run = pending
        if (run == null || cancelRequested) {
            pending = null; service.finishWork(); run?.lease?.close(); operationActive = false; finishCancelled(); return
        }
        serviceExecuting = true
        activeJob = scope.launch {
            try {
                when (val operation = run.operation) {
                    is Operation.Generate -> {
                        val result = repository.merge(operation.id, operation.entries, operation.outputName) { done, total, stage ->
                            mutable.update { it.copy(task = it.task?.copy(stage = stage, completed = done, total = total)) }
                        }
                        ensureActive()
                        change { it.copy(result = result) }
                    }
                    is Operation.Export -> {
                        exports.export(File(operation.result.filePath), operation.target) { copied, total ->
                            mutable.update { it.copy(task = it.task?.copy(copiedBytes = copied, totalBytes = total)) }
                        }
                        change { it.copy(result = operation.result.copy(exported = true), notice = "保存成功。") }
                    }
                }
            } catch (_: CancellationException) {
                val committed = (run.operation as? Operation.Generate)?.let { operation ->
                    withContext(NonCancellable + Dispatchers.IO) {
                        val candidate = File(app.filesDir, "results/${operation.id}/${operation.outputName}")
                        if (candidate.isFile && candidate.length() > 0) ResultUi(candidate.path, candidate.name,
                            operation.entries.sumOf { it.pages }, candidate.length()) else null
                    }
                }
                if (committed != null) change { it.copy(result = committed,
                    notice = "取消请求到达时 PDF 已完整生成，结果已保留，可继续预览或另存。") }
                else finishCancelled(keepTask = true)
            }
            catch (failure: PdfMergeException) {
                val message = friendly(failure.message, "PDF 无法合并，请重新选择异常文件。")
                change { old -> old.copy(error = message,
                    entries = old.entries.map { if (it.id == failure.entryId) it.copy(issue = message) else it }) }
            } catch (_: OutOfMemoryError) {
                change { it.copy(error = "PDF 处理超出可用内存，请减少文件数量或页数后重试。") }
            } catch (failure: Exception) {
                change { it.copy(error = friendly(failure.message,
                    if (run.operation is Operation.Export) "保存失败，目标可能不完整。已保留完整合并结果，请换个位置重试。"
                    else "PDF 合并失败，请检查源文件和设备空间后重试。")) }
            } finally {
                if (pending === run) pending = null
                // Completion is published after the shared service and gate are available.
                withContext(NonCancellable) {
                    persistCompletion({ it.copy(task = null) }) {
                        service.finishWork()
                        run.lease.close()
                        operationActive = false; serviceExecuting = false; activeJob = null
                    }
                }
            }
        }
    }
    fun cancel() {
        if (state.value.importing) { importJob?.cancel(); return }
        if (state.value.task == null) return
        cancelRequested = true
        change { it.copy(task = it.task?.copy(cancelling = true, stage = "正在取消，等待当前步骤结束")) }
        activeJob?.cancel()
    }
    private fun finishCancelled(keepTask: Boolean = false) { change { it.copy(task = if (keepTask) it.task else null, notice = "任务已取消，原有 PDF 列表和完整结果已保留。") } }
    fun newTask() { if (editable()) { pendingDuplicates = emptyList(); change { MergeState() }; cleanSources() } }
    private fun cleanSources() {
        scope.launch {
            if (!safelyPersist() || !editable()) return@launch
            val lease = gate.tryAcquire("merge") ?: return@launch
            try { repository.releaseUnusedSources(state.value.entries.toList()) }
            catch (_: Exception) { /* Deferred cleanup must not destroy a usable draft. */ }
            finally { lease.close() }
        }
    }
    fun error(message: String) { change { it.copy(error = message) } }
    fun notice(message: String) { change { it.copy(notice = message) } }
    fun dismissError() { change { it.copy(error = null) } }
    fun dismissNotice() { change { it.copy(notice = null) } }
    fun shareIntent(): Intent? = runCatching { state.value.result?.let { exports.shareIntent(File(it.filePath)) } }
        .getOrElse { error("合并结果无法读取，请重新合并后分享。"); null }
    fun openIntent(): Intent? = runCatching { state.value.result?.let { exports.openIntent(File(it.filePath)) } }
        .getOrElse { error("合并结果无法读取，请重新合并后打开。"); null }
    private fun friendly(message: String?, fallback: String) = message?.takeIf { text ->
        text.length <= 400 && text.any { it.code in 0x4e00..0x9fff } && !text.contains("\n\tat ")
    } ?: fallback
}
