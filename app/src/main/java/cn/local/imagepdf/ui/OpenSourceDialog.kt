package cn.local.imagepdf.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun OpenSourceDialog(onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val notices by produceState("正在读取许可…") {
        value = withContext(Dispatchers.IO) {
            runCatching {
                listOf("COMPONENTS.txt", "pdfbox-android-NOTICE.txt", "pdfbox-android-LICENSE.txt")
                    .joinToString("\n\n────────\n\n") { name ->
                        app.assets.open("licenses/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }
                    }
            }.getOrDefault("许可文件暂时无法读取，请查看项目中的 THIRD_PARTY_NOTICES.md。")
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("开源许可") },
        text = { SelectionContainer { Text(notices, Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
