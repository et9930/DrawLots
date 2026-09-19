package com.drawlots.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.drawlots.app.R
import com.drawlots.app.domain.DrawRecord
import com.drawlots.app.domain.Lot
import com.drawlots.app.domain.LotKind
import com.drawlots.app.domain.buildTextLotEntries
import com.drawlots.app.ui.components.LocalImage
import com.drawlots.app.ui.components.QuantityStepper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 添加文字签：支持一次输入多行，每行一个签。 */
@Composable
fun AddTextDialog(
    onDismiss: () -> Unit,
    onConfirm: (List<Pair<String, Int>>) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var quantity by remember { mutableIntStateOf(1) }
    var perLine by remember { mutableStateOf(true) }

    val entries: List<Pair<String, Int>> = buildTextLotEntries(text, quantity, perLine)
    val lineCount = buildTextLotEntries(text, quantity, onePerLine = true).size

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加文字签") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("签的内容") },
                    placeholder = { Text("例如：张三") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("每个签的数量", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    QuantityStepper(value = quantity, onChange = { quantity = it })
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = perLine, onCheckedChange = { perLine = it })
                    Text("每行作为一个签（可批量输入）", style = MaterialTheme.typography.bodyMedium)
                }
                if (perLine && lineCount > 1) {
                    Text(
                        text = "将添加 $lineCount 个签，每个数量 $quantity",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(entries)
                    onDismiss()
                },
                enabled = entries.isNotEmpty(),
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 编辑一种签（文字 / 备注 + 数量）。 */
@Composable
fun EditLotDialog(
    lot: Lot,
    onDismiss: () -> Unit,
    onConfirm: (Lot) -> Unit,
) {
    var text by remember { mutableStateOf(lot.text) }
    var quantity by remember { mutableIntStateOf(lot.quantity) }
    val isImage = lot.kind == LotKind.IMAGE

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isImage) "编辑图片签" else "编辑文字签") },
        text = {
            Column {
                if (isImage) {
                    LocalImage(
                        path = lot.imagePath,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        targetSizePx = 720,
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("备注（可留空）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("签的内容") },
                        minLines = 2,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("数量", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    QuantityStepper(value = quantity, onChange = { quantity = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(lot.copy(text = text.trim(), quantity = quantity))
                    onDismiss()
                },
                enabled = isImage || text.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 通用确认框。 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = "确定",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
            ) { Text(confirmText, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 点击结果里的签时放大查看，可以写备注，也可以把这一支单独放回签池（有权限时）。 */
@Composable
fun ResultPreviewDialog(
    record: DrawRecord,
    position: Int,
    canRecover: Boolean,
    onNoteChange: (String) -> Unit,
    onPutBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    var note by remember(record.seq) { mutableStateOf(record.note) }
    var editingNote by remember(record.seq) { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "第 $position 个签",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
                val imagePath = record.imagePath
                if (record.kind == LotKind.IMAGE && !imagePath.isNullOrBlank()) {
                    LocalImage(
                        path = imagePath,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .clip(RoundedCornerShape(16.dp)),
                        targetSizePx = 1080,
                        contentScale = ContentScale.Fit,
                    )
                    if (record.text.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = record.text,
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    Text(
                        text = record.title,
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "抽取方式：${record.mode.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (record.drawnBy.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "抽签人：${record.drawnBy}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatTime(record.atMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(14.dp))
                if (editingNote) {
                    OutlinedTextField(
                        value = note,
                        onValueChange = {
                            note = it
                            onNoteChange(it)
                        },
                        label = { Text("备注") },
                        placeholder = { Text("例如：谁抽到的") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "备注自动保存，也会跟着结果一起分享/复制",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                } else {
                    if (note.isNotBlank()) {
                        Text(
                            text = "备注：$note",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    TextButton(onClick = { editingNote = true }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_edit),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (note.isBlank()) "添加备注" else "修改备注")
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (canRecover) {
                        TextButton(onClick = onPutBack) {
                            Icon(
                                painter = painterResource(R.drawable.ic_undo),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (record.consumedCopy) "放回这个签" else "从结果中移除")
                        }
                    }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

fun formatTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))
