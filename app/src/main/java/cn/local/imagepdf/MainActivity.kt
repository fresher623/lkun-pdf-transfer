package cn.local.imagepdf

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.local.imagepdf.core.OutputSettings
import cn.local.imagepdf.merge.MergeActions
import cn.local.imagepdf.merge.PdfMergeScreen
import cn.local.imagepdf.ui.*

class MainActivity : ComponentActivity() {
    private val runtime get() = (application as ImagePdfApplication).runtime
    private val merger get() = (application as ImagePdfApplication).mergeRuntime
    private var showingMerge by mutableStateOf(false)
    private var notificationForMerge = false
    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(
        if (Build.VERSION.SDK_INT >= 33) minOf(500, MediaStore.getPickImagesMaxLimit()) else 500
    )) { uris -> runtime.importSelection(uris) }
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> runtime.importSelection(uris) }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> runtime.openFolder(uri) }
    private val savePicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> runtime.export(uri) }
    private val mergePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> merger.importSelection(uris) }
    private val mergeSavePicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> merger.export(uri) }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            val message = "通知权限未开启。任务仍会继续，可在应用内查看进度；系统可能只在任务管理器显示后台状态。"
            if (notificationForMerge) merger.notice(message) else runtime.notice(message)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        showingMerge = savedInstanceState?.getBoolean("showingMerge")
            ?: (intent.getStringExtra(ConversionService.EXTRA_TOOL) == ConversionService.TOOL_MERGE)
        notificationForMerge = savedInstanceState?.getBoolean("notificationForMerge") ?: false
        val actions = object : AppActions {
            override fun openMerge() {
                val state = runtime.state.value
                if (state.importing || state.readingFolder || state.folderSelection != null) {
                    runtime.notice("请先完成当前图片选择或导入，再打开 PDF 合并。")
                } else showingMerge = true
            }
            override fun pickFolder() {
                if (runtime.canPick()) runCatching { folderPicker.launch(null) }
                    .onFailure { runtime.error("文件夹选择器不可用，请启用系统文件应用，或先使用逐张选择图片。") }
            }
            override fun importFolder(uris: List<String>) = runtime.importFolder(uris)
            override fun dismissFolder() = runtime.dismissFolder()
            override fun pickPhotos() {
                if (runtime.canPick()) runCatching { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    .onFailure { runtime.error("系统相册不可用，请使用“批量选择图片”，一次可导入最多 500 张。") }
            }
            override fun pickFiles() {
                if (runtime.canPick()) runCatching { filePicker.launch(arrayOf("image/*")) }
                    .onFailure { runtime.error("系统文件选择器不可用，请启用系统文件应用后再批量导入。也可暂用系统相册，但相册单次数量可能受系统限制。") }
            }
            override fun confirmImport(allow: Boolean) = runtime.confirmImport(allow)
            override fun move(fromId: String, toId: String) = runtime.move(fromId, toId)
            override fun rotate(id: String) = runtime.rotate(id)
            override fun delete(id: String) = runtime.delete(id)
            override fun clear() = runtime.clear()
            override fun sort(order: SortOrder) = runtime.sort(order)
            override fun updateSettings(settings: OutputSettings) = runtime.updateSettings(settings)
            override fun navigate(screen: AppScreen) = runtime.navigate(screen)
            override fun generate() { if (runtime.canPick()) { requestNotification(false); runtime.generate() } }
            override fun cancel() = runtime.cancel()
            override fun save() {
                val result = runtime.prepareExportSelection() ?: return
                runCatching { savePicker.launch(result.name) }
                    .onFailure { runtime.cancelExportSelection(); runtime.error("系统保存界面不可用，请使用分享导出文件。") }
            }
            override fun share() { runtime.shareIntent()?.let { openIntent(Intent.createChooser(it, "分享 PDF")) } }
            override fun openExternal() { runtime.openIntent()?.let { openIntent(it) } }
            override fun newTask() = runtime.newTask()
            override fun dismissError() = runtime.dismissError()
            override fun dismissNotice() = runtime.dismissNotice()
        }
        val mergeActions = object : MergeActions {
            override fun pickPdfs() {
                if (merger.canPick()) runCatching { mergePicker.launch(arrayOf("application/pdf")) }
                    .onFailure { merger.error("系统文件选择器不可用，请启用系统文件应用后重试。") }
            }
            override fun move(fromId: String, toId: String) = merger.move(fromId, toId)
            override fun remove(id: String) = merger.remove(id)
            override fun clear() = merger.clear()
            override fun setOutputName(name: String) = merger.setOutputName(name)
            override fun generate() { if (merger.canPick()) { requestNotification(true); merger.generate() } }
            override fun cancel() = merger.cancel()
            override fun save() {
                val result = merger.prepareExportSelection() ?: return
                runCatching { mergeSavePicker.launch(result.name) }
                    .onFailure { merger.cancelExportSelection(); merger.error("系统保存界面不可用，请使用分享导出文件。") }
            }
            override fun share() { merger.shareIntent()?.let { openIntent(Intent.createChooser(it, "分享 PDF")) } }
            override fun openExternal() { merger.openIntent()?.let { openIntent(it) } }
            override fun newTask() = merger.newTask()
            override fun dismissError() = merger.dismissError()
            override fun dismissNotice() = merger.dismissNotice()
            override fun confirmDuplicates(allow: Boolean) = merger.confirmDuplicates(allow)
            override fun returnToImages() { showingMerge = false }
        }
        setContent {
            val imageState = runtime.state.collectAsStateWithLifecycle().value
            val mergeState = merger.state.collectAsStateWithLifecycle().value
            if (showingMerge) PdfMergeScreen(
                mergeState.copy(otherTaskActive = imageState.importing || imageState.readingFolder || imageState.task != null), mergeActions,
            ) else ImagePdfApp(
                imageState.copy(otherTaskActive = mergeState.importing || mergeState.task != null), actions, runtime.repository,
            )
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("showingMerge", showingMerge)
        outState.putBoolean("notificationForMerge", notificationForMerge)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        // A picker survives rotation, but closing this Activity abandons its pending selection.
        if (isFinishing) { runtime.cancelExportSelection(); merger.cancelExportSelection() }
        super.onDestroy()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        when (intent.getStringExtra(ConversionService.EXTRA_TOOL)) {
            ConversionService.TOOL_MERGE -> showingMerge = true
            ConversionService.TOOL_IMAGE -> {
                showingMerge = false
                if (runtime.state.value.task != null) runtime.navigate(AppScreen.PROGRESS)
            }
        }
    }
    private fun requestNotification(forMerge: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getPreferences(MODE_PRIVATE).getBoolean("notification_requested", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notification_requested", true).apply()
            notificationForMerge = forMerge
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    private fun openIntent(intent: Intent) {
        try { startActivity(intent) }
        catch (_: ActivityNotFoundException) { showIntentError("没有可处理 PDF 的应用，请使用内置预览或另存为。") }
        catch (_: SecurityException) { showIntentError("无法授予目标应用读取权限，请另存后再打开。") }
    }
    private fun showIntentError(message: String) { if (showingMerge) merger.error(message) else runtime.error(message) }
}
