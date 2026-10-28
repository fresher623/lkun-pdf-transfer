package cn.local.imagepdf.merge

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.local.imagepdf.ui.PdfFilePreview
import cn.local.imagepdf.ui.ResultUi
import cn.local.imagepdf.ui.TaskUi
import java.util.Locale
import kotlinx.coroutines.delay

private val MergeLightColors = lightColorScheme(
    primary = Color(0xFF24685E), onPrimary = Color.White,
    primaryContainer = Color(0xFFD7EEE7), onPrimaryContainer = Color(0xFF123F36),
    secondary = Color(0xFF536786), secondaryContainer = Color(0xFFE2EAF7),
    background = Color(0xFFF6F7F2), surface = Color(0xFFFCFDF9),
    surfaceVariant = Color(0xFFE8ECE5), onSurface = Color(0xFF1B2927),
    onSurfaceVariant = Color(0xFF55615C), outline = Color(0xFF788780),
)
private val MergeDarkColors = darkColorScheme(
    primary = Color(0xFF94D4C5), onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF174E43), onPrimaryContainer = Color(0xFFC9F5E8),
    secondary = Color(0xFFB1C8EE), secondaryContainer = Color(0xFF354865),
    background = Color(0xFF101916), surface = Color(0xFF17211D),
    surfaceVariant = Color(0xFF2B3731), onSurface = Color(0xFFE2EAE3),
    onSurfaceVariant = Color(0xFFBBC8BF), outline = Color(0xFF85988A),
)

private data class MergePreview(val path: String, val name: String, val pages: Int)

/** An independent merge flow; returning to the image tool never clears either draft. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfMergeScreen(state: MergeState, actions: MergeActions) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) MergeDarkColors else MergeLightColors, typography = Typography()) {
        var showResult by rememberSaveable(state.result?.filePath) { mutableStateOf(state.result != null) }
        var clearConfirm by remember { mutableStateOf(false) }
        var newConfirm by remember { mutableStateOf(false) }
        var leaveConfirm by remember { mutableStateOf(false) }
        var preview by remember { mutableStateOf<MergePreview?>(null) }
        LaunchedEffect(state.importing) { if (state.importing) showResult = false }
        val returnToImages = {
            if (state.result?.exported == false) leaveConfirm = true else actions.returnToImages()
        }
        BackHandler(enabled = preview == null) { returnToImages() }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Text("PDF 合并", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        TextButton(onClick = { returnToImages() }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("返回图片工具", fontSize = 12.sp, maxLines = 2, modifier = Modifier.semantics { contentDescription = "返回图片工具，保留合并草稿" })
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (state.otherTaskActive) Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text("图片工具正在处理任务，暂时不能添加、合并或另存。当前草稿已保留，可继续查看。", Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.bodySmall, lineHeight = 20.sp)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                val task = state.task
                val result = state.result
                when {
                    task != null -> MergeProgress(task, actions)
                    result != null && showResult && !state.importing -> MergeResult(
                        result, actions, canSave = !state.otherTaskActive,
                        onPreview = { preview = MergePreview(result.filePath, result.name, result.pageCount) },
                        onList = { showResult = false },
                        onNew = { newConfirm = true },
                    )
                    else -> MergeEditor(state, actions,
                        onClear = { clearConfirm = true },
                        onPreview = { preview = MergePreview(it.sourcePath, it.name, it.pages) },
                        onResult = { showResult = true },
                    )
                }
                }
            }
        }

        if (clearConfirm) MergeConfirm(
            title = "清空合并列表？",
            message = "将移除当前选择的 ${state.entries.size} 份 PDF。原文件和已生成的完整 PDF 不会被删除。",
            confirm = "清空列表",
            onConfirm = { clearConfirm = false; actions.clear() }, onDismiss = { clearConfirm = false },
        )
        if (newConfirm) MergeConfirm(
            title = "新建合并任务？",
            message = "当前合并草稿将清空。已生成的文件不会删除，尚未另存的结果建议先保存。",
            confirm = "新建任务",
            onConfirm = { newConfirm = false; actions.newTask(); showResult = false }, onDismiss = { newConfirm = false },
        )
        if (leaveConfirm) MergeConfirm(
            title = "合并结果尚未另存",
            message = "完整 PDF 和合并草稿会继续保留。返回图片工具后，可再次进入 PDF 合并查看、另存或分享。",
            confirm = "返回图片工具",
            onConfirm = { leaveConfirm = false; actions.returnToImages() }, onDismiss = { leaveConfirm = false },
        )
        when {
            state.error != null -> AlertDialog(
                onDismissRequest = actions::dismissError,
                title = { Text("需要处理一下") }, text = { Text(state.error) },
                confirmButton = { TextButton(onClick = actions::dismissError) { Text("知道了") } },
            )
            state.duplicateCount > 0 -> AlertDialog(
                onDismissRequest = { actions.confirmDuplicates(false) },
                title = { Text("包含重复选择的 PDF") },
                text = { Text("本次有 ${state.duplicateCount} 份 PDF 与已选来源重复。重复加入会让这些文件的全部页面再次出现在结果中，是否继续加入？") },
                confirmButton = { TextButton(onClick = { actions.confirmDuplicates(true) }) { Text("仍然加入") } },
                dismissButton = { TextButton(onClick = { actions.confirmDuplicates(false) }) { Text("取消本次添加") } },
            )
            state.notice != null -> AlertDialog(
                onDismissRequest = actions::dismissNotice,
                title = { Text("温馨提示") }, text = { Text(state.notice) },
                confirmButton = { TextButton(onClick = actions::dismissNotice) { Text("知道了") } },
            )
        }
        preview?.let { file -> PdfFilePreview(file.path, file.name, file.pages) { preview = null } }
    }
}

@Composable
private fun MergeEditor(
    state: MergeState,
    actions: MergeActions,
    onClear: () -> Unit,
    onPreview: (MergeEntry) -> Unit,
    onResult: () -> Unit,
) {
    val editable = !state.importing && state.task == null && !state.otherTaskActive
    val listState = rememberLazyListState()
    var draggedId by remember { mutableStateOf<String?>(null) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    val currentActions by rememberUpdatedState(actions)
    val currentEntries by rememberUpdatedState(state.entries)
    val edge = with(LocalDensity.current) { 60.dp.toPx() }

    fun targetAt(y: Float): String? = listState.layoutInfo.visibleItemsInfo
        .firstOrNull { y >= it.offset && y <= it.offset + it.size }
        ?.key?.toString()?.takeIf { it.startsWith("entry:") }?.removePrefix("entry:")

    LaunchedEffect(draggedId, editable) {
        if (!editable) { draggedId = null; return@LaunchedEffect }
        val id = draggedId ?: return@LaunchedEffect
        while (true) {
            val info = listState.layoutInfo
            val scroll = when {
                pointerY < info.viewportStartOffset + edge -> -14f
                pointerY > info.viewportEndOffset - edge -> 14f
                else -> 0f
            }
            if (scroll != 0f) {
                listState.scrollBy(scroll)
                targetAt(pointerY)?.takeIf { it != id }?.let { currentActions.move(id, it) }
            }
            delay(32)
        }
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().pointerInput(editable) {
                if (editable) detectDragGesturesAfterLongPress(
                    onDragStart = { point ->
                        pointerY = point.y
                        draggedId = targetAt(point.y)?.takeIf { id -> currentEntries.any { it.id == id } }
                    },
                    onDragEnd = { draggedId = null }, onDragCancel = { draggedId = null },
                    onDrag = { change, amount ->
                        val id = draggedId
                        if (id != null) {
                            change.consume()
                            pointerY += amount.y
                            targetAt(pointerY)?.takeIf { it != id }?.let { currentActions.move(id, it) }
                        }
                    },
                )
            },
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "intro") {
                Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("多份文档，按顺序合成。", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("按列表顺序加入每份 PDF 的全部页面，保留原页面尺寸、方向与支持范围内的内容。", style = MaterialTheme.typography.bodyMedium, lineHeight = 22.sp)
                        Text("2～50 份 PDF · 全程本地处理", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item(key = "controls") {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = actions::pickPdfs, enabled = editable && state.entries.size < 50, modifier = Modifier.heightIn(min = 52.dp)) { Text("＋ 添加 PDF") }
                    TextButton(onClick = onClear, enabled = editable && state.entries.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text("清空列表") }
                }
            }
            if (state.importing) item(key = "importing") {
                MergeInfo("正在读取 PDF", "${state.importDone}/${state.importTotal} 份 · 正在复制并检查文件，请稍候。") {
                    LinearProgressIndicator(
                        progress = { if (state.importTotal > 0) (state.importDone.toFloat() / state.importTotal).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            if (state.interrupted) item(key = "interrupted") {
                MergeInfo("上次合并已中断", "输入顺序已保留。请检查文件后从头重新合并，应用不会假装从中断页面续写。")
            }
            state.result?.let { result -> item(key = "existing-result") {
                MergeInfo(if (result.exported) "已有合并结果" else "已有结果，尚未另存", "${result.name}\n${result.pageCount} 页 · ${mergeBytes(result.bytes)}") {
                    TextButton(onClick = onResult, modifier = Modifier.heightIn(min = 48.dp)) { Text("查看已有结果 →") }
                }
            } }
            item(key = "summary") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("已选 ${state.entries.size}/50 份 · 共 ${state.totalPages} 页", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("输入文件共 ${mergeBytes(state.totalBytes)}，合并结果大小以实际生成为准。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.entries.isNotEmpty()) Text("长按文档拖动排序，也可使用上移、下移。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.entries.isEmpty()) item(key = "empty") {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) {
                    Text("还没有 PDF\n添加至少两份文档，开始整理。", Modifier.padding(horizontal = 24.dp, vertical = 34.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 26.sp)
                }
            }
            itemsIndexed(state.entries, key = { _, entry -> "entry:${entry.id}" }) { index, entry ->
                MergeInputCard(entry, index, state.entries.size, editable, draggedId == entry.id,
                    onPreview = { onPreview(entry) }, onRemove = { actions.remove(entry.id) },
                    onUp = { if (index > 0) actions.move(entry.id, state.entries[index - 1].id) },
                    onDown = { if (index < state.entries.lastIndex) actions.move(entry.id, state.entries[index + 1].id) },
                )
            }
            item(key = "output") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = state.outputName, onValueChange = actions::setOutputName,
                        label = { Text("输出文件名") }, singleLine = true, enabled = editable,
                        supportingText = { Text("自动补齐 .pdf 扩展名，开始后文件名与顺序锁定。") },
                        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.entries.any { it.issue != null }) Text("有文件无法处理，请移除异常条目或重新选择后再合并。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Text("首版支持基础静态 PDF；部分带文档元数据或阅读设置的文件也可能不支持。密码、签名、表单、附件等内容会明确提示，避免丢失内容。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp)
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = actions::generate,
                    enabled = editable && state.entries.size in 2..50 && state.entries.all { it.issue == null && it.pages > 0 } && state.outputName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) { Text("开始合并 · ${state.totalPages} 页") }
                if (state.entries.size < 2) Text("至少添加两份有效 PDF 后即可合并。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MergeInputCard(entry: MergeEntry, index: Int, count: Int, editable: Boolean, dragging: Boolean, onPreview: () -> Unit, onRemove: () -> Unit, onUp: () -> Unit, onDown: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Surface(
        modifier = Modifier.fillMaxWidth().then(if (dragging) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier),
        shape = shape, color = MaterialTheme.colorScheme.surface, tonalElevation = if (dragging) 4.dp else 1.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Surface(shape = RoundedCornerShape(9.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text("${index + 1}".padStart(2, '0'), Modifier.padding(horizontal = 10.dp, vertical = 8.dp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(entry.name, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${entry.pages} 页 · ${mergeBytes(entry.bytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (dragging) Text("正在移动", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            entry.issue?.let { issue -> Text(issue, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, lineHeight = 20.sp) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = onPreview, enabled = entry.issue == null && entry.pages > 0 && entry.sourcePath.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text("预览") }
                TextButton(onClick = onRemove, enabled = editable, modifier = Modifier.heightIn(min = 48.dp)) { Text("移除", color = if (editable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton(onClick = onUp, enabled = editable && index > 0, modifier = Modifier.heightIn(min = 48.dp)) { Text("↑ 上移") }
                TextButton(onClick = onDown, enabled = editable && index < count - 1, modifier = Modifier.heightIn(min = 48.dp)) { Text("下移 ↓") }
            }
        }
    }
}

@Composable
private fun MergeProgress(task: TaskUi, actions: MergeActions) {
    val progress = if (task.exporting) {
        if (task.totalBytes > 0) task.copiedBytes.toFloat() / task.totalBytes else 0f
    } else if (task.total > 0) task.completed.toFloat() / task.total else 0f
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Spacer(Modifier.height(20.dp))
        Box(Modifier.size(144.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxSize(), strokeWidth = 7.dp, trackColor = MaterialTheme.colorScheme.surfaceVariant)
            Text(if (task.exporting) "保存" else "合并", fontSize = 24.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(if (task.cancelling) "正在取消" else task.stage, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(if (task.exporting) "${mergeBytes(task.copiedBytes)} / ${mergeBytes(task.totalBytes)}" else "已处理 ${task.completed} / ${task.total} 页", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MergeInfo(if (task.cancelling) "正在结束当前步骤" else if (task.exporting) "正在写入保存位置" else "按文件顺序合并", when {
            task.cancelling -> "正在关闭资源并清理本次半成品。输入草稿与此前生成的完整 PDF 会保留，请等待清理完成。"
            task.exporting -> "只有复制完整并关闭目标文件后才会显示保存成功。另存失败时，应用内完整结果仍可重新保存。"
            else -> "本次输入顺序与文件名已锁定。合并完成后会先校验应用内结果，再提供预览、另存与分享。"
        })
        OutlinedButton(onClick = actions::cancel, enabled = !task.cancelling, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text(if (task.cancelling) "正在取消，请稍候…" else "取消任务") }
        Text("可以返回图片工具查看草稿；当前合并或保存任务会继续显示在通知中。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, lineHeight = 20.sp)
    }
}

@Composable
private fun MergeResult(result: ResultUi, actions: MergeActions, canSave: Boolean, onPreview: () -> Unit, onList: () -> Unit, onNew: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.fillMaxWidth().padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("PDF", fontSize = 34.sp, fontWeight = FontWeight.Light, letterSpacing = 4.sp, color = MaterialTheme.colorScheme.primary)
                Text("合并完成", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(result.name, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                Text("${result.pageCount} 页 · ${mergeBytes(result.bytes)}")
                Surface(shape = RoundedCornerShape(9.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)) {
                    Text(if (result.exported) "已另存成功" else "已生成，尚未另存", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Button(onClick = actions::save, enabled = canSave, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(if (result.exported) "再次另存为" else "另存为 PDF", fontSize = 17.sp) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onPreview, modifier = Modifier.weight(1f).heightIn(min = 54.dp)) { Text("预览结果") }
            OutlinedButton(onClick = actions::share, modifier = Modifier.weight(1f).heightIn(min = 54.dp)) { Text("分享") }
        }
        TextButton(onClick = actions::openExternal, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("用其他应用打开") }
        Text("完整结果已保存在应用私有目录。取消另存不会丢失结果，可以再次保存或分享。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp)
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        OutlinedButton(onClick = onList, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("返回合并列表") }
        TextButton(onClick = onNew, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("新建合并任务") }
    }
}

@Composable
private fun MergeInfo(title: String, text: String, content: @Composable () -> Unit = {}) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 22.sp)
            content()
        }
    }
}

@Composable
private fun MergeConfirm(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun mergeBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.CHINA, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.CHINA, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
}
