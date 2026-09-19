package com.drawlots.app.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.drawlots.app.R
import com.drawlots.app.room.DiscoveredRoom
import com.drawlots.app.room.RoomHostRequest
import com.drawlots.app.room.RoomTransportKind

private const val TAB_CREATE = 0
private const val TAB_JOIN = 1

/**
 * 联机面板（只在没进房间时打开）：
 * - 顶部固定「我的名称」——它同时决定抽签署名，两个页签都要用
 * - 「创建房间」页签：房间名 / 传输方式 / 两个放权开关（默认都关）
 * - 「加入房间」页签：扫码 / 附近房间 / 手输房间码
 */
@Composable
fun RoomSheet(
    myName: String,
    discoveredRooms: List<DiscoveredRoom>,
    busy: Boolean,
    bluetoothAvailable: Boolean,
    onMyNameChange: (String) -> Unit,
    onHost: (RoomHostRequest) -> Unit,
    onJoin: (DiscoveredRoom) -> Unit,
    onJoinByCode: (String, String?, Int?) -> Unit,
    onScanQr: (() -> Unit)?,
    onRefreshRooms: () -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(TAB_CREATE) }
    var roomName by remember { mutableStateOf("$myName 的抽签") }
    var kind by remember { mutableStateOf(RoomTransportKind.Lan) }
    var allowRecover by remember { mutableStateOf(false) }
    var allowEdit by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var manualHost by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 600.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            ) {
                Text("联机抓阄", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "一台手机当房主，其他人扫码或输房间码加入；大家看到同一份签池和结果。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // ---------- 我的名称（固定在最上面） ----------
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = myName,
                    onValueChange = onMyNameChange,
                    label = { Text("我的名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "抽出的签会自动署名这个名称",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // ---------- 两个页签 ----------
                Spacer(Modifier.height(12.dp))
                PrimaryTabRow(
                    selectedTabIndex = tab,
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    Tab(
                        selected = tab == TAB_CREATE,
                        onClick = { tab = TAB_CREATE },
                        text = { Text("创建房间") },
                    )
                    Tab(
                        selected = tab == TAB_JOIN,
                        onClick = { tab = TAB_JOIN },
                        text = { Text("加入房间") },
                    )
                }
                Spacer(Modifier.height(16.dp))

                if (tab == TAB_CREATE) {
                    OutlinedTextField(
                        value = roomName,
                        onValueChange = { roomName = it },
                        label = { Text("房间名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("连接方式", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TransportChip(
                            label = RoomTransportKind.Lan.label,
                            selected = kind == RoomTransportKind.Lan,
                            onClick = { kind = RoomTransportKind.Lan },
                        )
                        TransportChip(
                            label = RoomTransportKind.Bluetooth.label,
                            selected = kind == RoomTransportKind.Bluetooth,
                            enabled = bluetoothAvailable,
                            onClick = { kind = RoomTransportKind.Bluetooth },
                        )
                    }
                    if (!bluetoothAvailable) {
                        Text(
                            text = "蓝牙房间需要 Android 12 及以上，当前设备请用局域网",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    SwitchRow(
                        title = "允许其他人放回 / 撤销",
                        checked = allowRecover,
                        onCheckedChange = { allowRecover = it },
                    )
                    SwitchRow(
                        title = "允许其他人编辑签池",
                        checked = allowEdit,
                        onCheckedChange = { allowEdit = it },
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            onHost(
                                RoomHostRequest(
                                    roomName = roomName,
                                    kind = kind,
                                    allowRecover = allowRecover,
                                    allowEdit = allowEdit,
                                ),
                            )
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("创建房间") }
                } else {
                    if (onScanQr != null) {
                        OutlinedButton(onClick = onScanQr, modifier = Modifier.fillMaxWidth()) {
                            Icon(
                                painter = painterResource(R.drawable.ic_camera),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("扫二维码加入")
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("附近房间", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onRefreshRooms) { Text("刷新") }
                    }
                    if (discoveredRooms.isEmpty()) {
                        Text(
                            text = "暂时没发现房间（需要在同一个 Wi-Fi 或热点下）。也可以直接扫二维码 / 输房间码。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            discoveredRooms.forEach { room ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                        .clickable(enabled = !busy) { onJoin(room) }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(room.roomName, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            text = "房主 ${room.hostName} · ${room.host}:${room.port} · 码 ${room.roomCode}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        text = "加入",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.uppercase() },
                        label = { Text("房间码（6 位）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = manualHost,
                            onValueChange = { manualHost = it },
                            // 标签要短且不换行，否则会把输入框顶得比旁边的「端口」高
                            label = { Text("房主地址", maxLines = 1) },
                            placeholder = { Text("可留空", maxLines = 1) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = manualPort,
                            onValueChange = { manualPort = it.filter { ch -> ch.isDigit() } },
                            label = { Text("端口", maxLines = 1) },
                            placeholder = { Text("可选", maxLines = 1) },
                            singleLine = true,
                            modifier = Modifier.width(110.dp),
                        )
                    }
                    Text(
                        text = "同一 Wi-Fi 下这两项可以留空；广播被拦时再手填房主地址与端口。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { onJoinByCode(code, manualHost.ifBlank { null }, manualPort.toIntOrNull()) },
                        enabled = !busy && code.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("用房间码加入") }
                }

                Spacer(Modifier.height(14.dp))
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                Text(
                    text = if (busy) "正在连接…" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun TransportChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(
                when {
                    selected -> MaterialTheme.colorScheme.primary
                    enabled -> MaterialTheme.colorScheme.surface
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                selected -> MaterialTheme.colorScheme.onPrimary
                enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            },
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
