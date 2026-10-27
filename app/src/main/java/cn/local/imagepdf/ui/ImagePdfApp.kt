package cn.local.imagepdf.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.local.imagepdf.core.ImageEntry
import cn.local.imagepdf.core.LayoutMode
import cn.local.imagepdf.core.Orientation
import cn.local.imagepdf.core.OutputSettings
import cn.local.imagepdf.core.PageSize
import cn.local.imagepdf.core.Quality
import cn.local.imagepdf.platform.ImageRepository
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF24685E), onPrimary = Color.White,
    primaryContainer = Color(0xFFD7EEE7), onPrimaryContainer = Color(0xFF123F36),
    secondary = Color(0xFF536786), secondaryContainer = Color(0xFFE2EAF7),
    background = Color(0xFFF6F7F2), surface = Color(0xFFFCFDF9),
    surfaceVariant = Color(0xFFE8ECE5), onSurface = Color(0xFF1B2927),
    onSurfaceVariant = Color(0xFF55615C), outline = Color(0xFF788780),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF94D4C5), onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF174E43), onPrimaryContainer = Color(0xFFC9F5E8),
    secondary = Color(0xFFB1C8EE), secondaryContainer = Color(0xFF354865),
    background = Color(0xFF101916), surface = Color(0xFF17211D),
    surfaceVariant = Color(0xFF2B3731), onSurface = Color(0xFFE2EAE3),
    onSurfaceVariant = Color(0xFFBBC8BF), outline = Color(0xFF85988A),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagePdfApp(state: AppUiState, actions: AppActions, repository: ImageRepository) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, typography = Typography()) {
        var clearConfirm by remember { mutableStateOf(false) }
        var newConfirm by remember { mutableStateOf(false) }
        var leaveConfirm by remember { mutableStateOf(false) }
        var exitConfirm by remember { mutableStateOf(false) }
        var imagePreview by remember { mutableStateOf<ImageEntry?>(null) }
        var pdfPreview by remember { mutableStateOf(false) }
        val busy = state.importing || state.readingFolder || state.folderSelection != null || state.task != null
        val activity = LocalActivity.current
        val requestNew = {
            if (state.images.isNotEmpty() || (state.result != null && !state.result.exported)) newConfirm = true
            else actions.newTask()
        }
        val goBack = {
            when (state.screen) {
                AppScreen.SETTINGS -> actions.navigate(AppScreen.ORGANIZE)
                AppScreen.RESULT -> if (state.result?.exported == false) leaveConfirm = true else actions.navigate(AppScreen.HOME)
                else -> actions.navigate(AppScreen.HOME)
            }
        }
        BackHandler(enabled = state.screen != AppScreen.HOME && imagePreview == null && !pdfPreview) { goBack() }
        BackHandler(enabled = state.screen == AppScreen.HOME && state.task == null && state.result?.exported == false) { exitConfirm = true }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Row(verticalAlignment = Alignment.CenterVertically) {
                        PaperSymbol(Modifier.size(28.dp))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(if (state.screen == AppScreen.HOME) "l君のpdf转换" else screenTitle(state.screen), fontWeight = FontWeight.Bold)
                            if (state.screen == AppScreen.HOME) Text("图片转 PDF", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } },
                    navigationIcon = {
                        if (state.screen != AppScreen.HOME) TextButton(onClick = { goBack() }) { Text("返回") }
                    },
                    actions = { if (state.screen == AppScreen.HOME) Text("本地处理", modifier = Modifier.padding(end = 18.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (state.importing) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                            Text("正在读取图片 ${state.importDone}/${state.importTotal} · 请稍候", style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(progress = { if (state.importTotal > 0) state.importDone.toFloat() / state.importTotal else 0f }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                if (state.readingFolder) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("正在读取文件夹中的图片，请稍候…", style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }
                when (state.screen) {
                    AppScreen.HOME -> HomeScreen(state, actions, requestNew)
                    AppScreen.ORGANIZE -> OrganizeScreen(state, actions, repository, !busy, { clearConfirm = true }, { imagePreview = it })
                    AppScreen.SETTINGS -> SettingsScreen(state, actions, !busy)
                    AppScreen.PROGRESS -> ProgressScreen(state, actions)
                    AppScreen.RESULT -> ResultScreen(state, actions, { pdfPreview = true }, requestNew)
                }
            }
        }
        if (clearConfirm) ConfirmDialog("清空已选图片？", "将移除当前任务的 ${state.images.size} 张图片。源图片不会被删除。", "清空图片", { clearConfirm = false; actions.clear() }, { clearConfirm = false })
        if (newConfirm) ConfirmDialog("开始新任务？", "当前图片和设置将被清空。尚未另存的 PDF 建议先保存；已生成的应用内文件会保留。", "开始新任务", { newConfirm = false; actions.newTask() }, { newConfirm = false })
        if (leaveConfirm) ConfirmDialog("PDF 尚未另存", "应用内的完整 PDF 会保留，可从首页继续查看。也可以先返回完成页另存或分享。", "返回首页", { leaveConfirm = false; actions.navigate(AppScreen.HOME) }, { leaveConfirm = false })
        if (exitConfirm) ConfirmDialog("退出前另存 PDF？", "当前 PDF 尚未另存，退出后仍会保留在应用内。建议先另存到自己选择的位置。", "退出应用", { exitConfirm = false; activity?.finish() }, { exitConfirm = false })
        if (state.pendingImportCount > 0) {
            val remaining = (500 - state.images.size).coerceAtLeast(0)
            AlertDialog(
                onDismissRequest = { actions.confirmImport(false) }, title = { Text("超过图片数量上限") },
                text = { Text("此次选择 ${state.pendingImportCount} 张，当前还可添加 $remaining 张。${if (remaining > 0) "是否只导入本次选择的前 $remaining 张？" else "请先删除部分图片，再重新添加。"}") },
                confirmButton = { if (remaining > 0) TextButton(onClick = { actions.confirmImport(true) }) { Text("导入前 $remaining 张") } else TextButton(onClick = { actions.confirmImport(false) }) { Text("知道了") } },
                dismissButton = { if (remaining > 0) TextButton(onClick = { actions.confirmImport(false) }) { Text("取消导入") } },
            )
        }
        state.error?.let { message ->
            AlertDialog(onDismissRequest = actions::dismissError, title = { Text("需要处理一下") }, text = { Text(message) }, confirmButton = { TextButton(onClick = actions::dismissError) { Text("知道了") } })
        }
        state.notice?.let { message ->
            AlertDialog(onDismissRequest = actions::dismissNotice, title = { Text("温馨提示") }, text = { Text(message) }, confirmButton = { TextButton(onClick = actions::dismissNotice) { Text("知道了") } })
        }
        imagePreview?.let { item -> ImagePreview(item, repository) { imagePreview = null } }
        if (pdfPreview && state.result != null) PdfPreview(state.result) { pdfPreview = false }
        state.folderSelection?.let { selection ->
            FolderPicker(selection, actions::importFolder, actions::dismissFolder)
        }
    }
}

private fun screenTitle(screen: AppScreen): String = when (screen) {
    AppScreen.HOME -> "l君のpdf转换"
    AppScreen.ORGANIZE -> "整理图片"
    AppScreen.SETTINGS -> "输出设置"
    AppScreen.PROGRESS -> "转换任务"
    AppScreen.RESULT -> "已生成 PDF"
}

@Composable
private fun HomeScreen(state: AppUiState, actions: AppActions, onNew: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.fillMaxWidth().padding(28.dp)) {
                Text("把零散的图片，\n收成一份 PDF。", fontSize = 30.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.height(12.dp))
                Text("相册、笔记、资料，按你的顺序装订。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.height(22.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Badge("一次最多500张")
                    Badge("全程离线")
                    Badge("无需账号")
                }
                Spacer(Modifier.height(26.dp))
                PaperStack(Modifier.fillMaxWidth().height(156.dp))
            }
        }
        if (state.task != null) {
            InfoCard("转换任务正在进行", "${state.task.stage} · ${state.task.completed}/${state.task.total} 页") {
                Button(onClick = { actions.navigate(AppScreen.PROGRESS) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("查看当前任务") }
            }
        } else {
            Button(onClick = actions::pickFolder, enabled = !state.importing && !state.readingFolder, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(16.dp)) {
                Text("选择文件夹 · 批量导入", fontSize = 17.sp, lineHeight = 24.sp, textAlign = TextAlign.Center)
            }
            Helper("选择图片所在文件夹，应用内点击全选，再导入；一次最多500张。")
            OutlinedButton(onClick = actions::pickFiles, enabled = !state.importing && !state.readingFolder, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) {
                Text("逐张选择图片", textAlign = TextAlign.Center)
            }
            OutlinedButton(onClick = actions::pickPhotos, enabled = !state.importing && !state.readingFolder, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) {
                Text("从相册选择（数量由系统限制）", textAlign = TextAlign.Center)
            }
            if (state.images.isNotEmpty()) InfoCard("继续当前整理", "已选 ${state.images.size}/500 张 · ${state.sortLabel}") {
                TextButton(onClick = { actions.navigate(AppScreen.ORGANIZE) }) { Text("打开图片列表 →") }
            }
        }
        state.result?.let { result ->
            InfoCard(if (result.exported) "最近生成" else "已生成，尚未另存", "${result.name}\n${result.pageCount} 页 · ${formatBytes(result.bytes)}") {
                TextButton(onClick = { actions.navigate(AppScreen.RESULT) }) { Text("查看 PDF →") }
            }
        }
        if (state.interrupted) InfoCard("上次任务已中断", "系统结束了处理进程。原任务图片仍在，请检查后从头生成。") {
            TextButton(onClick = { actions.navigate(AppScreen.ORGANIZE) }) { Text("检查并重新生成") }
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("三步，整理成册", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Step(1, "选择图片", Modifier.weight(1f))
                Step(2, "整理顺序", Modifier.weight(1f))
                Step(3, "保存分享", Modifier.weight(1f))
            }
            Text("仅访问你选择的图片，不上传内容。每张图片对应一页，可设置纸张、边距与质量。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, lineHeight = 20.sp)
        }
        if (state.images.isNotEmpty() && state.task == null) TextButton(onClick = onNew, enabled = !state.importing, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("新建空白任务") }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun OrganizeScreen(state: AppUiState, actions: AppActions, repository: ImageRepository, editable: Boolean, onClear: () -> Unit, onPreview: (ImageEntry) -> Unit) {
    var sortMenu by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    var draggedId by remember { mutableStateOf<String?>(null) }
    var dragPointer by remember { mutableStateOf(Offset.Zero) }
    val currentActions by rememberUpdatedState(actions)
    val edgeZone = with(LocalDensity.current) { 64.dp.toPx() }
    LaunchedEffect(draggedId) {
        val id = draggedId ?: return@LaunchedEffect
        while (true) {
            val layout = gridState.layoutInfo
            val scroll = when {
                dragPointer.y < layout.viewportStartOffset + edgeZone -> -14f
                dragPointer.y > layout.viewportEndOffset - edgeZone -> 14f
                else -> 0f
            }
            if (scroll != 0f) {
                gridState.scrollBy(scroll)
                gridState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                    dragPointer.x >= info.offset.x && dragPointer.x <= info.offset.x + info.size.width &&
                        dragPointer.y >= info.offset.y && dragPointer.y <= info.offset.y + info.size.height
                }?.let { target -> if (target.key != id) currentActions.move(id, target.key as String) }
            }
            delay(32)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("已选 ${state.images.size}/500", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                TextButton(onClick = onClear, enabled = editable && state.images.isNotEmpty()) { Text("清空") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions::pickFolder, enabled = editable, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text("＋ 从文件夹批量添加", textAlign = TextAlign.Center)
                }
                TextButton(onClick = actions::pickFiles, enabled = editable) { Text("逐张选择") }
                TextButton(onClick = actions::pickPhotos, enabled = editable) { Text("系统相册") }
                Box {
                    TextButton(onClick = { sortMenu = true }, enabled = editable) { Text("排序 ▾") }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        listOf(SortOrder.IMPORT to "导入顺序", SortOrder.NAME to "文件名：自然升序", SortOrder.TAKEN to "拍摄时间：从早到晚").forEach { (order, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { sortMenu = false; actions.sort(order) })
                        }
                    }
                }
            }
            Text("选择图片所在文件夹，应用内点击全选，再导入；一次最多500张。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${state.sortLabel} · 长按图片拖动，点击可预览", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.images.any { it.error != null }) Text("有图片无法读取，请移除异常项或重新选择后再生成。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (!editable && state.task != null) Text("转换中，当前任务的图片和配置已锁定。", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }
        if (state.images.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("还没有图片\n从相册或文件添加，开始整理。", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(156.dp), state = gridState, modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(state.images, key = { _, item -> item.id }) { index, item ->
                    var pointer by remember(item.id) { mutableStateOf(Offset.Zero) }
                    ImageCard(item, index, repository, editable, draggedId == item.id,
                        Modifier.pointerInput(item.id, editable) {
                            if (editable) detectDragGesturesAfterLongPress(
                                onDragStart = { start ->
                                    val info = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == item.id }
                                    pointer = Offset((info?.offset?.x ?: 0).toFloat(), (info?.offset?.y ?: 0).toFloat()) + start
                                    dragPointer = pointer
                                    draggedId = item.id
                                },
                                onDragEnd = { draggedId = null }, onDragCancel = { draggedId = null },
                                onDrag = { change, delta ->
                                    change.consume()
                                    pointer += delta
                                    dragPointer = pointer
                                    val target = gridState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                                        pointer.x >= info.offset.x && pointer.x <= info.offset.x + info.size.width && pointer.y >= info.offset.y && pointer.y <= info.offset.y + info.size.height
                                    }
                                    if (target != null && target.key != item.id) currentActions.move(item.id, target.key as String)
                                },
                            )
                        },
                        onPreview = { onPreview(item) }, onRotate = { actions.rotate(item.id) }, onDelete = { actions.delete(item.id) },
                        onPrevious = if (index > 0) ({ actions.move(item.id, state.images[index - 1].id) }) else null,
                        onNext = if (index < state.images.lastIndex) ({ actions.move(item.id, state.images[index + 1].id) }) else null,
                    )
                }
            }
        }
        BottomAction {
            Button(onClick = { actions.navigate(AppScreen.SETTINGS) }, enabled = editable && state.images.isNotEmpty() && state.images.none { it.error != null }, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text("下一步 · 输出设置") }
            if (state.task != null) TextButton(onClick = { actions.navigate(AppScreen.PROGRESS) }, modifier = Modifier.fillMaxWidth()) { Text("查看转换任务") }
        }
    }
}

@Composable
private fun ImageCard(item: ImageEntry, index: Int, repository: ImageRepository, editable: Boolean, dragged: Boolean, modifier: Modifier, onPreview: () -> Unit, onRotate: () -> Unit, onDelete: () -> Unit, onPrevious: (() -> Unit)?, onNext: (() -> Unit)?) {
    var thumbnailLoaded by remember(item.id, item.rotation, item.uri) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, item.id, item.rotation, item.uri) {
        value = withContext(Dispatchers.IO) { repository.loadThumbnail(item) }
        thumbnailLoaded = true
    }
    Card(modifier = modifier.then(if (dragged) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp)) else Modifier), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.95f).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onPreview)) {
            if (bitmap != null) Image(bitmap!!.asImageBitmap(), contentDescription = "第 ${index + 1} 张，${item.name}，点击预览", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(8.dp))
            else Text(when { item.error != null -> "无法读取"; thumbnailLoaded -> "预览不可用\n点击查看"; else -> "正在加载…" }, Modifier.align(Alignment.Center), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            Surface(modifier = Modifier.align(Alignment.TopStart).padding(8.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), shape = RoundedCornerShape(8.dp)) { Text("${index + 1}".padStart(2, '0'), Modifier.padding(horizontal = 9.dp, vertical = 5.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium) }
        }
        Text(item.name, Modifier.padding(horizontal = 10.dp, vertical = 7.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
        if (item.error != null) Text(item.error ?: "", Modifier.padding(horizontal = 10.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onRotate, enabled = editable, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(0.dp)) { Text("旋转 90°", fontSize = 12.sp) }
            TextButton(onClick = onDelete, enabled = editable, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(0.dp)) { Text("删除", fontSize = 12.sp, color = if (editable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = { onPrevious?.invoke() }, enabled = editable && onPrevious != null, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(0.dp)) { Text("← 前移", fontSize = 12.sp) }
            TextButton(onClick = { onNext?.invoke() }, enabled = editable && onNext != null, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(0.dp)) { Text("后移 →", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun SettingsScreen(state: AppUiState, actions: AppActions, editable: Boolean) {
    val settings = state.settings
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    PaperSymbol(Modifier.size(44.dp))
                    Column { Text("${state.images.size} 张图片 → ${state.images.size} 页 PDF", fontWeight = FontWeight.Bold); Text("按整理页顺序生成，图片不会上传。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                }
            }
            OutlinedTextField(value = settings.fileName, onValueChange = { actions.updateSettings(settings.copy(fileName = it)) }, label = { Text("PDF 文件名") }, supportingText = { Text("自动处理非法字符并补齐 .pdf 扩展名") }, singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
            SettingGroup("页面尺寸", "每张图片各占一页") {
                ChoiceRow(listOf(PageSize.A4 to "A4", PageSize.A5 to "A5", PageSize.LETTER to "Letter", PageSize.IMAGE_RATIO to "图片比例"), settings.pageSize, editable) { actions.updateSettings(settings.copy(pageSize = it)) }
                if (settings.pageSize == PageSize.IMAGE_RATIO) Helper("按最终图片比例设置页面，内容长边统一为 297 mm，再加页边距。极端长图完整显示，不拆页。")
            }
            SettingGroup("页面方向") {
                ChoiceRow(listOf(Orientation.AUTO to "自动", Orientation.PORTRAIT to "纵向", Orientation.LANDSCAPE to "横向"), settings.orientation, editable) { actions.updateSettings(settings.copy(orientation = it)) }
                Helper("自动模式按每张图片旋转后的比例选择横向或纵向。")
            }
            SettingGroup("图片排版") {
                ChoiceRow(listOf(LayoutMode.FIT to "完整显示", LayoutMode.FILL to "铺满裁切"), settings.layout, editable) { actions.updateSettings(settings.copy(layout = it)) }
                if (settings.layout == LayoutMode.FILL && settings.pageSize != PageSize.IMAGE_RATIO) Text("铺满将裁去超出内容区域的部分，请确认图片边缘没有重要内容。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                else Helper("保留完整画面，等比居中。透明区域合成到白色背景。")
            }
            SettingGroup("页边距") {
                ChoiceRow(listOf(0 to "0 mm", 5 to "5 mm", 10 to "10 mm", 15 to "15 mm"), settings.marginMm, editable) { actions.updateSettings(settings.copy(marginMm = it)) }
            }
            SettingGroup("输出质量", "采样分辨率与图片编码同时生效") {
                Quality.entries.forEach { quality ->
                    val text = when (quality) { Quality.HIGH -> "清晰 · 200 DPI"; Quality.STANDARD -> "标准 · 150 DPI"; Quality.SMALL -> "省空间 · 100 DPI" }
                    val description = when (quality) { Quality.HIGH -> "保留更多细节，文件通常较大"; Quality.STANDARD -> "适合日常文档与分享"; Quality.SMALL -> "减少文件体积，细节相对减少" }
                    Surface(onClick = { if (editable) actions.updateSettings(settings.copy(quality = quality)) }, enabled = editable, shape = RoundedCornerShape(14.dp), color = if (settings.quality == quality) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(if (settings.quality == quality) "●" else "○", color = MaterialTheme.colorScheme.primary)
                            Column { Text(text, fontWeight = FontWeight.Medium); Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("添加页码", fontWeight = FontWeight.Medium); Text("页码使用独立底部区域，不覆盖正文。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Switch(checked = settings.pageNumbers, onCheckedChange = { actions.updateSettings(settings.copy(pageNumbers = it)) }, enabled = editable, modifier = Modifier.semantics { contentDescription = "添加页码" })
            }
            Helper("生成完成后再选择保存位置。取消另存不会丢失已生成的完整 PDF。")
        }
        BottomAction {
            Button(onClick = actions::generate, enabled = editable && state.images.isNotEmpty() && state.images.none { it.error != null } && settings.fileName.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("生成 PDF · ${state.images.size} 页") }
        }
    }
}

@Composable
private fun ProgressScreen(state: AppUiState, actions: AppActions) {
    val task = state.task
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Spacer(Modifier.height(24.dp))
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(146.dp)) {
            if (task != null) CircularProgressIndicator(progress = { if (task.exporting && task.totalBytes > 0) (task.copiedBytes.toFloat() / task.totalBytes).coerceIn(0f, 1f) else if (task.total > 0) (task.completed.toFloat() / task.total).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxSize(), strokeWidth = 7.dp, trackColor = MaterialTheme.colorScheme.surfaceVariant)
            PaperSymbol(Modifier.size(58.dp))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(if (task?.cancelling == true) "正在取消" else task?.stage ?: "任务已停止", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(if (task == null) "可以返回整理页重新生成。" else if (task.exporting) "${formatBytes(task.copiedBytes)} / ${formatBytes(task.totalBytes)}" else "已写入 ${task.completed} / ${task.total} 页", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        InfoCard(if (task?.cancelling == true) "正在关闭资源并清理临时文件" else "处理留在设备上", when {
            task?.cancelling == true && task.exporting -> "当前写入步骤结束后会停止。应用内完整 PDF 会保留，请等待清理完成。"
            task?.cancelling == true -> "当前图片编码结束后会停止。请等待清理完成。"
            task?.exporting == true -> "完整 PDF 正在写入你选择的保存位置。只有写入并关闭文件后，才会提示保存成功。可以切换应用，通过通知返回任务。"
            else -> "图片逐页处理，不会一次加载全部原图。可以切换应用，通过通知返回任务。请勿强制停止应用。"
        })
        if (task != null) {
            OutlinedButton(onClick = actions::cancel, enabled = !task.cancelling, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text(if (task.cancelling) "正在取消，请稍候…" else "取消任务") }
            TextButton(onClick = { actions.navigate(AppScreen.HOME) }) { Text("返回首页，后台继续处理") }
        } else Button(onClick = { actions.navigate(AppScreen.ORGANIZE) }, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text("返回整理图片") }
    }
}

@Composable
private fun ResultScreen(state: AppUiState, actions: AppActions, onPreview: () -> Unit, onNew: () -> Unit) {
    val result = state.result
    if (result == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("暂无 PDF，请先选择图片并生成。") }; return }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PaperSymbol(Modifier.size(68.dp))
                Text("你的 PDF 已准备好", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(result.name, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                Text("${result.pageCount} 页  ·  ${formatBytes(result.bytes)}", style = MaterialTheme.typography.bodyLarge)
                Badge(if (result.exported) "已另存成功" else "已生成，尚未另存")
            }
        }
        if (state.task?.exporting == true) {
            Text("正在保存 ${formatBytes(state.task.copiedBytes)} / ${formatBytes(state.task.totalBytes)}", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            LinearProgressIndicator(progress = { if (state.task.totalBytes > 0) (state.task.copiedBytes.toFloat() / state.task.totalBytes).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = actions::cancel, enabled = !state.task.cancelling, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (state.task.cancelling) "正在取消保存…" else "取消保存") }
        }
        Button(onClick = actions::save, enabled = state.task == null, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(if (result.exported) "再次另存为" else "另存为 PDF", fontSize = 17.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onPreview, modifier = Modifier.weight(1f).heightIn(min = 54.dp)) { Text("预览 PDF") }
            OutlinedButton(onClick = actions::share, modifier = Modifier.weight(1f).heightIn(min = 54.dp)) { Text("分享") }
        }
        TextButton(onClick = actions::openExternal, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("用其他应用打开") }
        Helper("PDF 完整保存在应用私有目录。另存后可在所选位置找到；取消保存或保存失败后，仍可重新保存与分享。")
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        OutlinedButton(onClick = onNew, enabled = state.task == null, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text("继续新建") }
    }
}

@Composable
private fun ImagePreview(item: ImageEntry, repository: ImageRepository, onDismiss: () -> Unit) {
    var loadError by remember { mutableStateOf<String?>(null) }
    val bitmap by produceState<Bitmap?>(null, item.id, item.rotation) {
        value = try { withContext(Dispatchers.IO) { repository.loadPreview(item) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        if (value == null) loadError = item.error ?: "图片无法读取，请重新选择或移除该图片。"
    }
    PreviewShell(item.name, onDismiss, footer = { Text("双指缩放 · 预览已按屏幕预算采样", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall) }) {
        if (bitmap != null) ZoomImage(bitmap!!, item.name, "image-${item.id}-${item.rotation}")
        else if (loadError != null) Text(loadError!!, color = Color.White, modifier = Modifier.padding(24.dp), textAlign = TextAlign.Center)
        else CircularProgressIndicator()
    }
}

private data class PdfFrame(val bitmap: Bitmap, val pages: Int)

@Composable
private fun PdfPreview(result: ResultUi, onDismiss: () -> Unit) {
    var page by remember(result.filePath) { mutableIntStateOf(0) }
    var pages by remember(result.filePath) { mutableIntStateOf(result.pageCount) }
    var error by remember(result.filePath) { mutableStateOf<String?>(null) }
    val frame by produceState<PdfFrame?>(null, result.filePath, page) {
        error = null
        value = null
        try {
            value = withContext(Dispatchers.IO) {
                ParcelFileDescriptor.open(File(result.filePath), ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        renderer.openPage(page.coerceIn(0, renderer.pageCount - 1)).use { pdfPage ->
                            val ratio = 1600.0 / maxOf(pdfPage.width, pdfPage.height).coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap((pdfPage.width * ratio).toInt().coerceAtLeast(1), (pdfPage.height * ratio).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            PdfFrame(bitmap, renderer.pageCount)
                        }
                    }
                }
            }
            pages = value?.pages ?: pages
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "暂时无法渲染这一页，可尝试用其他应用打开 PDF。" }
    }
    PreviewShell(result.name, onDismiss, footer = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${page + 1} / $pages", color = Color.White)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { page-- }, enabled = page > 0, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("上一页", color = if (page > 0) Color.White else Color.Gray) }
                TextButton(onClick = { page++ }, enabled = page < pages - 1, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("下一页", color = if (page < pages - 1) Color.White else Color.Gray) }
            }
            Text("双指缩放 · 每次仅加载当前页", color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
        }
    }) {
        if (frame != null) ZoomImage(frame!!.bitmap, "PDF 第 ${page + 1} 页", "pdf-${result.filePath}-$page")
        else if (error != null) Text(error!!, color = Color.White, modifier = Modifier.padding(24.dp), textAlign = TextAlign.Center)
        else CircularProgressIndicator()
    }
}

@Composable
private fun PreviewShell(title: String, onDismiss: () -> Unit, footer: @Composable () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF121A17)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("关闭", color = Color.White) }
                }
                Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) { content() }
                Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) { footer() }
            }
        }
    }
}

@Composable
private fun ZoomImage(bitmap: Bitmap, description: String, imageKey: String) {
    var zoom by remember(imageKey) { mutableFloatStateOf(1f) }
    var offset by remember(imageKey) { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().pointerInput(imageKey) {
        detectTransformGestures { _, pan, scale, _ ->
            zoom = (zoom * scale).coerceIn(1f, 4f)
            offset = if (zoom == 1f) Offset.Zero else Offset((offset.x + pan.x).coerceIn(-size.width * (zoom - 1f) / 2, size.width * (zoom - 1f) / 2), (offset.y + pan.y).coerceIn(-size.height * (zoom - 1f) / 2, size.height * (zoom - 1f) / 2))
        }
    }, contentAlignment = Alignment.Center) {
        Image(bitmap.asImageBitmap(), description, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = offset.x; translationY = offset.y })
        if (zoom > 1f) TextButton(onClick = { zoom = 1f; offset = Offset.Zero }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) { Text("还原缩放", color = Color.White, modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(8.dp)) }
    }
}

@Composable
private fun BottomAction(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) { Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() } }
}

@Composable
private fun SettingGroup(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) Helper(subtitle)
        content()
    }
}

@Composable
private fun <T> ChoiceRow(choices: List<Pair<T, String>>, selected: T, enabled: Boolean, onSelect: (T) -> Unit) {
    // Two chips per row keep the labels readable at larger system font sizes.
    Column {
        choices.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { (value, label) -> FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(label) }, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) }
                if (row.size == 1 && choices.size > 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Helper(text: String) { Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp) }

@Composable
private fun InfoCard(title: String, text: String, content: @Composable () -> Unit = {}) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 22.sp)
            content()
        }
    }
}

@Composable
private fun Badge(text: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)) { Text(text, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
}

@Composable
private fun Step(number: Int, text: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("0$number", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Light)
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ConfirmDialog(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(message) }, confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } }, dismissButton = { TextButton(onClick = onDismiss) { Text("返回") } })
}

@Composable
private fun PaperSymbol(modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply { moveTo(w * 0.19f, h * 0.04f); lineTo(w * 0.65f, h * 0.04f); lineTo(w * 0.88f, h * 0.28f); lineTo(w * 0.88f, h * 0.96f); lineTo(w * 0.19f, h * 0.96f); close() }
        drawPath(path, color.copy(alpha = 0.18f))
        drawLine(color, Offset(w * 0.65f, h * 0.04f), Offset(w * 0.65f, h * 0.28f), strokeWidth = w * 0.035f)
        drawLine(color, Offset(w * 0.65f, h * 0.28f), Offset(w * 0.88f, h * 0.28f), strokeWidth = w * 0.035f)
        listOf(0.47f, 0.61f, 0.75f).forEach { y -> drawLine(color, Offset(w * 0.32f, h * y), Offset(w * 0.72f, h * y), strokeWidth = w * 0.045f) }
    }
}

@Composable
private fun PaperStack(modifier: Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    Box(modifier, contentAlignment = Alignment.Center) {
        Surface(Modifier.width(112.dp).height(140.dp).graphicsLayer { rotationZ = -13f; translationX = -32f }, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer, shadowElevation = 2.dp) {}
        Surface(Modifier.width(112.dp).height(140.dp).graphicsLayer { rotationZ = 11f; translationX = 26f }, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f), shadowElevation = 2.dp) {}
        Surface(Modifier.width(112.dp).height(146.dp), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PaperSymbol(Modifier.size(30.dp))
                Spacer(Modifier.height(3.dp))
                repeat(3) { Box(Modifier.fillMaxWidth(if (it == 2) 0.65f else 1f).height(3.dp).background(lineColor, RoundedCornerShape(2.dp))) }
                Text("PDF", fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.CHINA, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.CHINA, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
}
