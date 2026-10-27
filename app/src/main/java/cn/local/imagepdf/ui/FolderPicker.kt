package cn.local.imagepdf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.local.imagepdf.core.MAX_IMAGES
import cn.local.imagepdf.core.naturalCompare

/** The app owns these controls; their presence does not depend on a document provider's menu. */
@Composable
fun FolderPicker(selection: FolderSelection, onImport: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val images = remember(selection.images) {
        selection.images.distinctBy { it.uri }.sortedWith { a, b -> naturalCompare(a.name, b.name) }
    }
    val remaining = selection.remaining.coerceIn(0, MAX_IMAGES)
    var selected by rememberSaveable(selection.folderName, selection.images, remaining) {
        mutableStateOf(emptyList<String>())
    }
    var limitConfirm by rememberSaveable(selection.folderName, selection.images, remaining) { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = { if (!submitting) onDismiss() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
    )) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("选择要导入的图片", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        TextButton(onClick = onDismiss, enabled = !submitting, modifier = Modifier.heightIn(min = 48.dp)) { Text("关闭") }
                    }
                    Text(selection.folderName, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("已选 ${selected.size}/$remaining 张 · 文件夹共 ${images.size} 张", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = {
                            if (images.size > remaining) limitConfirm = true
                            else selected = images.map { it.uri }
                        }, enabled = images.isNotEmpty() && remaining > 0 && !submitting,
                            modifier = Modifier.weight(1f).heightIn(min = 50.dp)) { Text("全选", textAlign = TextAlign.Center) }
                        OutlinedButton(onClick = { selected = emptyList() }, enabled = selected.isNotEmpty() && !submitting,
                            modifier = Modifier.weight(1f).heightIn(min = 50.dp)) { Text("取消全选", textAlign = TextAlign.Center) }
                    }
                    Text("按文件名自然升序排列；可逐项勾选或点击上方全选。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
                if (images.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                        Text("此文件夹没有可导入的图片。\n请关闭后选择图片所在的文件夹。", textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
                        itemsIndexed(images, key = { _, image -> image.uri }) { index, image ->
                            val checked = image.uri in selected
                            val enabled = !submitting && (checked || selected.size < remaining)
                            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                                .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { check ->
                                    // Re-check live state: multiple taps can arrive before enabled recomposes.
                                    if (!check) selected = selected - image.uri
                                    else if (image.uri !in selected && selected.size < remaining) selected = selected + image.uri
                                }).padding(horizontal = 20.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(image.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
                            }
                            HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                        }
                    }
                }
                Surface(shadowElevation = 4.dp) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (remaining == 0) "当前任务已达到500张上限，请返回整理页移除部分图片。"
                            else "当前还可导入 $remaining 张。关闭此页不会更改已整理的图片。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = {
                            val chosen = selected.toSet()
                            val uris = images.filter { it.uri in chosen }.map { it.uri }
                            if (uris.isNotEmpty() && uris.size <= remaining) {
                                submitting = true
                                onImport(uris)
                            }
                        }, enabled = selected.isNotEmpty() && !submitting,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Text(if (submitting) "正在开始导入…" else "导入已选 ${selected.size} 张", textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
        if (limitConfirm) {
            AlertDialog(onDismissRequest = { limitConfirm = false }, title = { Text("超过可导入数量") },
                text = { Text("文件夹共有 ${images.size} 张图片，当前最多再添加 $remaining 张。是否选中文件名自然升序排列的前 $remaining 张？") },
                confirmButton = { TextButton(onClick = {
                    selected = images.take(remaining).map { it.uri }
                    limitConfirm = false
                }) { Text("选前 $remaining 张") } },
                dismissButton = { TextButton(onClick = { limitConfirm = false }) { Text("取消") } })
        }
    }
}
