package cn.local.imagepdf.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.local.imagepdf.merge.MergeState

private val MenuLightColors = lightColorScheme(
    primary = Color(0xFF24685E), onPrimary = Color.White,
    primaryContainer = Color(0xFFD7EEE7), onPrimaryContainer = Color(0xFF123F36),
    secondary = Color(0xFF536786), secondaryContainer = Color(0xFFE2EAF7),
    onSecondaryContainer = Color(0xFF243F68),
    background = Color(0xFFF6F7F2), surface = Color(0xFFFCFDF9),
    surfaceVariant = Color(0xFFE8ECE5), onSurface = Color(0xFF1B2927),
    onSurfaceVariant = Color(0xFF55615C), outline = Color(0xFF788780),
)
private val MenuDarkColors = darkColorScheme(
    primary = Color(0xFF94D4C5), onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF174E43), onPrimaryContainer = Color(0xFFC9F5E8),
    secondary = Color(0xFFB1C8EE), secondaryContainer = Color(0xFF354865),
    onSecondaryContainer = Color(0xFFD7E5FF),
    background = Color(0xFF101916), surface = Color(0xFF17211D),
    surfaceVariant = Color(0xFF2B3731), onSurface = Color(0xFFE2EAE3),
    onSurfaceVariant = Color(0xFFBBC8BF), outline = Color(0xFF85988A),
)

/** Choosing a tool changes navigation only; both runtimes keep ownership of their drafts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolMenuScreen(
    imageState: AppUiState,
    mergeState: MergeState,
    onImages: () -> Unit,
    onMerge: () -> Unit,
) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) MenuDarkColors else MenuLightColors, typography = Typography()) {
        var showLicenses by remember { mutableStateOf(false) }
        val imageWorking = imageState.task != null || imageState.readingFolder || (imageState.importing && imageState.importTotal > 0)
        val mergeWorking = mergeState.task != null || (mergeState.importing && mergeState.importTotal > 0)
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Text("l君のpdf转换", fontWeight = FontWeight.Bold) },
                    actions = {
                        Text("本地处理", Modifier.padding(end = 20.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 680.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("PDF 工具", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                        Text("选择一个工具，开始处理 PDF。", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (imageWorking) MenuCurrentTask("图片合成 PDF", imageState.task,
                        if (imageState.readingFolder) "正在读取图片文件夹" else "正在导入图片 ${imageState.importDone}/${imageState.importTotal}", onImages)
                    if (mergeWorking) MenuCurrentTask("PDF 合并", mergeState.task,
                        "正在导入 PDF ${mergeState.importDone}/${mergeState.importTotal}", onMerge)
                    MenuToolCard(
                        title = "PDF 合并",
                        description = "将已有的多份 PDF 按顺序合成一份，保留原有页面尺寸。",
                        badges = listOf("2～50 份 PDF", "最多1000页"),
                        summary = mergeSummary(mergeState),
                        action = if (mergeWorking) "查看合并进度" else if (mergeState.entries.isNotEmpty() || mergeState.result != null) "继续 PDF 合并" else "打开 PDF 合并",
                        accent = MaterialTheme.colorScheme.secondary,
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        isImages = false,
                        onClick = onMerge,
                    )
                    MenuToolCard(
                        title = "图片合成 PDF",
                        description = "将照片、笔记和资料整理成一份 PDF，每张图片对应一页。",
                        badges = listOf("最多500张图片", "支持全选"),
                        summary = imageSummary(imageState),
                        action = if (imageWorking) "查看图片任务" else if (imageState.images.isNotEmpty() || imageState.result != null) "继续图片合成 PDF" else "打开图片合成 PDF",
                        accent = MaterialTheme.colorScheme.primary,
                        container = MaterialTheme.colorScheme.primaryContainer,
                        isImages = true,
                        onClick = onImages,
                    )
                    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("草稿会为你保留", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("返回菜单或切换工具，都可以继续上次的整理。处理中的任务也可以从这里返回查看。", style = MaterialTheme.typography.bodySmall, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { showLicenses = true }, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)) { Text("开源许可") }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
        if (showLicenses) OpenSourceDialog { showLicenses = false }
    }
}

@Composable
private fun MenuToolCard(
    title: String,
    description: String,
    badges: List<String>,
    summary: String,
    action: String,
    accent: Color,
    container: Color,
    isImages: Boolean,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "打开 $title" }, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(shape = RoundedCornerShape(15.dp), color = container) {
                    MenuToolSymbol(isImages, accent, Modifier.size(58.dp).padding(8.dp))
                }
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("→", color = accent, fontSize = 26.sp)
            }
            Text(description, style = MaterialTheme.typography.bodyMedium, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                badges.forEach { label ->
                    Surface(shape = RoundedCornerShape(8.dp), color = container.copy(alpha = 0.65f)) {
                        Text(label, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium, color = accent)
                    }
                }
            }
            Text(summary, style = MaterialTheme.typography.bodySmall, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(action, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = accent)
        }
    }
}

@Composable
private fun MenuCurrentTask(title: String, task: TaskUi?, fallback: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("当前任务 · $title", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(task?.stage ?: fallback, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (task != null && task.total > 0) {
                val fraction = if (task.exporting && task.totalBytes > 0) task.copiedBytes.toFloat() / task.totalBytes else task.completed.toFloat() / task.total
                LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            } else LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("返回当前任务 →", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

private fun imageSummary(state: AppUiState): String = when {
    state.task != null -> "${state.task.stage} · 点击可查看当前任务"
    state.readingFolder -> "正在读取图片文件夹，选择进度会继续保留。"
    state.importing && state.importTotal > 0 -> "正在导入 ${state.importDone}/${state.importTotal} 张图片"
    state.importing -> "正在恢复图片草稿…"
    state.folderSelection != null -> "有待完成的文件夹选择，点击继续。"
    state.pendingImportCount > 0 -> "有待确认的图片选择，点击继续。"
    state.result != null -> "已选 ${state.images.size} 张 · 已有 ${state.result.pageCount} 页结果${if (!state.result.exported) "，尚未另存" else ""}"
    state.images.isNotEmpty() -> "草稿已保留 · 已选 ${state.images.size}/500 张图片"
    else -> "从文件夹或相册选择图片，开始整理。"
}

private fun mergeSummary(state: MergeState): String = when {
    state.task != null -> "${state.task.stage} · 点击可查看当前任务"
    state.importing && state.importTotal > 0 -> "正在导入 ${state.importDone}/${state.importTotal} 份 PDF"
    state.importing -> "正在恢复合并草稿…"
    state.duplicateCount > 0 -> "有待确认的重复 PDF，点击继续。"
    state.result != null -> "已选 ${state.entries.size} 份 · 已有 ${state.result.pageCount} 页结果${if (!state.result.exported) "，尚未另存" else ""}"
    state.entries.isNotEmpty() -> "草稿已保留 · 已选 ${state.entries.size}/50 份，共 ${state.totalPages} 页"
    else -> "适用于基础静态 PDF，复杂文档会提示不支持。"
}

@Composable
private fun MenuToolSymbol(images: Boolean, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val line = 1.8.dp.toPx()
        val radius = CornerRadius(3.dp.toPx())
        drawRoundRect(tint.copy(alpha = 0.18f), Offset(w * 0.28f, h * 0.10f), Size(w * 0.55f, h * 0.70f), radius)
        drawRoundRect(tint, Offset(w * 0.15f, h * 0.20f), Size(w * 0.56f, h * 0.70f), radius, style = Stroke(line))
        if (images) {
            drawCircle(tint, w * 0.055f, Offset(w * 0.31f, h * 0.38f))
            val mountains = Path().apply {
                moveTo(w * 0.23f, h * 0.72f)
                lineTo(w * 0.38f, h * 0.51f)
                lineTo(w * 0.47f, h * 0.63f)
                lineTo(w * 0.55f, h * 0.54f)
                lineTo(w * 0.65f, h * 0.72f)
            }
            drawPath(mountains, tint, style = Stroke(line))
        } else {
            for (y in listOf(0.40f, 0.54f, 0.68f)) {
                drawLine(tint, Offset(w * 0.27f, h * y), Offset(w * 0.58f, h * y), line)
            }
        }
    }
}
