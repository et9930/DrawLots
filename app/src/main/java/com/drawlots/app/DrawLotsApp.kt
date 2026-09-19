package com.drawlots.app

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import com.drawlots.app.room.RoomActionResult
import com.drawlots.app.room.RoomInvite
import com.drawlots.app.room.RoomRole
import com.drawlots.app.room.RoomTransportKind
import com.drawlots.app.ui.DrawScreen
import com.drawlots.app.ui.PoolScreen
import com.drawlots.app.ui.components.LocalRemoteImageResolver
import com.drawlots.app.ui.components.RemoteImageResolver
import com.drawlots.app.ui.room.BluetoothPermissions
import com.drawlots.app.ui.room.QrScannerScreen
import com.drawlots.app.ui.room.RoomBanner
import com.drawlots.app.ui.room.RoomLobby
import com.drawlots.app.ui.room.RoomSheet
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable

private const val TAB_DRAW = 0
private const val TAB_POOL = 1

@Composable
fun DrawLotsApp(viewModel: AppViewModel = viewModel()) {
    val ui by viewModel.ui.collectAsState()
    val message by viewModel.message.collectAsState()
    val role by viewModel.roomRole.collectAsState()
    val roomStatus by viewModel.roomStatus.collectAsState()
    val roomInfo by viewModel.roomInfo.collectAsState()
    val myName by viewModel.myName.collectAsState()
    val discoveredRooms by viewModel.discoveredRooms.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // 第一次打开（签池为空）时先停在签池页，方便添加签。
    var tab by rememberSaveable { mutableIntStateOf(if (ui.isEmpty) TAB_POOL else TAB_DRAW) }
    var showRoomSheet by remember { mutableStateOf(false) }
    var showLobby by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var roomBusy by remember { mutableStateOf(false) }
    var pendingBluetoothAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val action = pendingBluetoothAction
        pendingBluetoothAction = null
        if (granted.values.all { it }) {
            action?.invoke()
        } else {
            viewModel.showMessage("需要蓝牙权限才能用蓝牙房间")
        }
    }

    /** 蓝牙房间要按需申请权限（房主申请广播、成员申请扫描），其余情况直接执行。 */
    fun withBluetoothPermissions(permissions: List<String>, action: () -> Unit) {
        val missing = BluetoothPermissions.missing(context, permissions)
        if (missing.isEmpty()) {
            action()
        } else {
            pendingBluetoothAction = action
            bluetoothPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.consumeMessage()
    }

    // 面板打开期间才发现附近房间（前台），关闭就停
    LaunchedEffect(showRoomSheet) {
        if (showRoomSheet) viewModel.startRoomDiscovery() else viewModel.stopRoomDiscovery()
    }

    // 退出房间后收起大厅
    LaunchedEffect(role) {
        if (role == RoomRole.Offline) showLobby = false
    }

    // 成员模式下图片要从房主那里按需拉取
    val imageResolver = remember(role, roomInfo?.roomCode) {
        if (role == RoomRole.Member) {
            RemoteImageResolver { path -> viewModel.resolveRoomImage(path) }
        } else {
            null
        }
    }

    CompositionLocalProvider(LocalRemoteImageResolver provides imageResolver) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    NavigationBarItem(
                        selected = tab == TAB_DRAW,
                        onClick = { tab = TAB_DRAW },
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_dice),
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                            )
                        },
                        label = { Text("抽签") },
                    )
                    NavigationBarItem(
                        selected = tab == TAB_POOL,
                        onClick = { tab = TAB_POOL },
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_edit),
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                            )
                        },
                        label = { Text("签池") },
                    )
                }
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                AppHeader(
                    summary = ui.summary,
                    roomActive = role != RoomRole.Offline,
                    onOpenRoom = {
                        // 在房间里就打开房间详情（二维码/房间码/成员），否则打开建房/加入面板
                        if (role == RoomRole.Offline) showRoomSheet = true else showLobby = true
                    },
                )
                if (role != RoomRole.Offline) {
                    RoomBanner(
                        role = role,
                        status = roomStatus,
                        info = roomInfo,
                        onOpenDetails = { showLobby = true },
                        onLeave = { viewModel.leaveRoom() },
                    )
                }
                when (tab) {
                    TAB_DRAW -> DrawScreen(
                        ui = ui,
                        viewModel = viewModel,
                        modifier = Modifier.weight(1f),
                    )

                    else -> PoolScreen(
                        ui = ui,
                        viewModel = viewModel,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
            // 扫码页覆盖整屏（相机预览需要自己在 Activity 窗口里，不能套在 Dialog 里）
            if (showScanner) {
                QrScannerScreen(
                    onDecoded = { invite ->
                        showScanner = false
                        val join: () -> Unit = {
                            roomBusy = true
                            viewModel.joinRoom(invite) { result ->
                                roomBusy = false
                                if (result is RoomActionResult.Ok) showRoomSheet = false
                            }
                        }
                        // 蓝牙房间需要扫描权限；局域网直接用
                        if (invite.target is com.drawlots.app.room.RoomTarget.Bluetooth) {
                            withBluetoothPermissions(BluetoothPermissions.forJoining(), join)
                        } else {
                            join()
                        }
                    },
                    onDismiss = {
                        // 手输房间码：回到面板
                        showScanner = false
                        showRoomSheet = true
                    },
                )
            }
        }
    }

    if (showRoomSheet) {
        RoomSheet(
            myName = myName,
            discoveredRooms = discoveredRooms,
            busy = roomBusy,
            onMyNameChange = viewModel::setMyName,
            onHost = { request ->
                val start: () -> Unit = {
                    roomBusy = true
                    viewModel.hostRoom(request) { result ->
                        roomBusy = false
                        if (result is RoomActionResult.Ok) {
                            showRoomSheet = false
                            showLobby = true
                        }
                    }
                }
                if (request.kind == RoomTransportKind.Bluetooth) {
                    withBluetoothPermissions(BluetoothPermissions.forHosting(), start)
                } else {
                    start()
                }
            },
            onJoin = { room ->
                roomBusy = true
                viewModel.joinRoom(
                    RoomInvite(target = room.target, roomCode = room.roomCode, roomName = room.roomName),
                ) { result ->
                    roomBusy = false
                    if (result is RoomActionResult.Ok) showRoomSheet = false
                }
            },
            onJoinByCode = { code, host, port ->
                roomBusy = true
                viewModel.joinByCode(code, host, port) { result ->
                    roomBusy = false
                    if (result is RoomActionResult.Ok) showRoomSheet = false
                }
            },
            onScanQr = {
                showRoomSheet = false
                showScanner = true
            },
            onRefreshRooms = viewModel::probeRoomDiscovery,
            onDismiss = { showRoomSheet = false },
            bluetoothAvailable = BluetoothPermissions.isSupported(),
        )
    }

    val info = roomInfo
    if (showLobby && info != null && role == RoomRole.Host) {
        RoomLobby(
            info = info,
            onLeave = {
                showLobby = false
                viewModel.leaveRoom()
            },
            onDismiss = { showLobby = false },
            onShareCode = { code -> shareText(context, "来抓阄：房间码 $code（${info.roomName}）") },
        )
    }
}

@Composable
private fun AppHeader(
    summary: String,
    roomActive: Boolean,
    onOpenRoom: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "抓阄抽签",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                )
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (roomActive) {
                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f)
                        } else {
                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.14f)
                        },
                    )
                    .clickable(onClick = onOpenRoom)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_group),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (roomActive) "房间" else "联机",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "分享房间码"))
}
