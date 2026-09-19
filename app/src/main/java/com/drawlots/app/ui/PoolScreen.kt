package com.drawlots.app.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drawlots.app.AppViewModel
import com.drawlots.app.LotUi
import com.drawlots.app.R
import com.drawlots.app.UiState
import com.drawlots.app.domain.DrawMode
import com.drawlots.app.domain.Lot
import com.drawlots.app.domain.LotKind
import com.drawlots.app.media.ImageStore
import com.drawlots.app.ui.components.ActionButton
import com.drawlots.app.ui.components.LocalImage
import com.drawlots.app.ui.components.LotThumb
import com.drawlots.app.ui.components.QuantityStepper
import com.drawlots.app.ui.room.RoomPermissions
import java.io.File

/**
 * 签池编辑页：添加文字签 / 图库选图 / 拍照，并设置每个签的数量。
 */
@Composable
fun PoolScreen(
    ui: UiState,
    viewModel: AppViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val importing by viewModel.importing.collectAsState()
    val role by viewModel.roomRole.collectAsState()
    val roomInfo by viewModel.roomInfo.collectAsState()
    val allowEdit = roomInfo?.allowEdit ?: false
    val canEdit = RoomPermissions.canEditPool(role, allowEdit)
    val canAddImage = RoomPermissions.canAddImageLot(role)
    val readOnlyHint = RoomPermissions.poolReadOnlyHint(role, allowEdit)

    var showAddText by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Lot?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }

    // 系统相册选择器：不需要任何存储权限。
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9),
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.addGalleryImages(uris)
    }

    // 系统相机：拍照写入 FileProvider 提供的临时文件，同样不需要相机权限。
    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val file = pendingCameraFile
        pendingCameraFile = null
        if (file == null) return@rememberLauncherForActivityResult
        if (success) viewModel.addCameraImage(file) else file.delete()
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (readOnlyHint != null) {
            Text(
                text = readOnlyHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }
        if (canEdit) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ActionButton(
                    icon = painterResource(R.drawable.ic_edit),
                    text = "文字签",
                    onClick = { showAddText = true },
                    modifier = Modifier.weight(1f),
                    enabled = !importing,
                )
                if (canAddImage) {
                    ActionButton(
                        icon = painterResource(R.drawable.ic_gallery),
                        text = "图库",
                        onClick = {
                            pickImages.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !importing,
                    )
                    ActionButton(
                        icon = painterResource(R.drawable.ic_camera),
                        text = "拍照",
                        onClick = {
                            val file = ImageStore.newCameraFile(context)
                            try {
                                pendingCameraFile = file
                                takePicture.launch(ImageStore.uriFor(context, file))
                            } catch (e: ActivityNotFoundException) {
                                pendingCameraFile = null
                                file.delete()
                                viewModel.showMessage("没有找到可用的相机应用")
                            } catch (e: Exception) {
                                pendingCameraFile = null
                                file.delete()
                                viewModel.showMessage("无法启动相机")
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !importing,
                    )
                }
            }
        }

        if (importing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        HorizontalDivider()

        if (ui.isEmpty) {
            EmptyPool(modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(ui.lots, key = { _, item -> item.lot.id }) { index, item ->
                    LotCard(
                        index = index + 1,
                        item = item,
                        mode = ui.mode,
                        editable = canEdit,
                        onQuantityChange = { viewModel.setQuantity(item.lot.id, it) },
                        onEdit = { editing = item.lot },
                        onDelete = { viewModel.removeLot(item.lot.id) },
                    )
                }
                if (canEdit) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            TextButton(onClick = { showClearConfirm = true }) {
                                Text("清空签池", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddText) {
        AddTextDialog(
            onDismiss = { showAddText = false },
            onConfirm = { entries -> viewModel.addTextLots(entries) },
        )
    }

    editing?.let { lot ->
        EditLotDialog(
            lot = lot,
            onDismiss = { editing = null },
            onConfirm = { updated -> viewModel.updateLot(updated) },
        )
    }

    if (showClearConfirm) {
        ConfirmDialog(
            title = "清空签池？",
            message = "签池和抽签结果都会被清空，图片签的图片文件也会删除，此操作不可撤销。",
            confirmText = "清空",
            onConfirm = { viewModel.clearPool() },
            onDismiss = { showClearConfirm = false },
        )
    }
}

@Composable
private fun EmptyPool(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LotThumb(lot = null, size = 72.dp, badge = "签")
        Spacer(Modifier.height(16.dp))
        Text("签池还是空的", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "用上面的按钮添加签：\n· 文字签：手动输入内容，可以一次输入多行\n· 图库：从相册选图片做签\n· 拍照：直接拍一张照片做签\n\n每个签都能设置数量，数量越多越容易被抽到。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LotCard(
    index: Int,
    item: LotUi,
    mode: DrawMode,
    editable: Boolean,
    onQuantityChange: (Int) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val imagePath = item.lot.imagePath
            if (item.lot.kind == LotKind.IMAGE && !imagePath.isNullOrBlank()) {
                LocalImage(
                    path = imagePath,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    targetSizePx = 240,
                )
            } else {
                LotThumb(lot = item.lot, size = 64.dp, badge = "$index")
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.lot.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = statusText(item, mode),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (editable) {
                    Spacer(Modifier.height(6.dp))
                    QuantityStepper(value = item.lot.quantity, onChange = onQuantityChange)
                }
            }

            if (editable) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = onEdit) {
                        Icon(
                            painter = painterResource(R.drawable.ic_edit),
                            contentDescription = "编辑",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete),
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun statusText(item: LotUi, mode: DrawMode): String = when (mode) {
    DrawMode.WITHOUT_REPLACEMENT ->
        "数量 ×${item.lot.quantity}　已抽 ${item.drawnCount}　剩余 ${item.remaining}"

    DrawMode.WITH_REPLACEMENT ->
        "数量 ×${item.lot.quantity}　已抽 ${item.drawnCount}"
}
