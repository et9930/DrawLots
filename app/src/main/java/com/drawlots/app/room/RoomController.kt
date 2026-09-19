package com.drawlots.app.room

import com.drawlots.app.domain.DrawSession
import com.drawlots.app.domain.RoomBackupStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 当前设备在房间里的角色。 */
enum class RoomRole {
    /** 没进房间：单机模式，一切照旧。 */
    Offline,

    /** 房主：权威状态在自己手上。 */
    Host,

    /** 成员：只有副本，变更都要发给房主。 */
    Member,
}

/** 房间用哪种传输。 */
enum class RoomTransportKind(val label: String) {
    Lan("局域网"),
    Bluetooth("蓝牙"),
}

/** 建房参数（界面收集）。 */
data class RoomHostRequest(
    val roomName: String,
    val kind: RoomTransportKind = RoomTransportKind.Lan,
    val allowRecover: Boolean = false,
    val allowEdit: Boolean = false,
)

/** 房间信息（房主与成员共用一份展示模型）。 */
data class RoomInfo(
    val roomName: String,
    val roomCode: String,
    val hostName: String,
    val kind: RoomTransportKind,
    val members: List<String> = emptyList(),
    val allowRecover: Boolean = false,
    val allowEdit: Boolean = false,
    /** 房主侧：二维码内容；成员侧为 null。 */
    val invite: RoomInvite? = null,
)

/** 面向界面的房间状态。 */
sealed interface RoomStatus {
    data object Idle : RoomStatus
    data object Hosting : RoomStatus
    data object Connecting : RoomStatus
    data class Connected(val roomName: String) : RoomStatus
    data class Reconnecting(val attempt: Int, val reason: String) : RoomStatus
    data class Closed(val reason: String) : RoomStatus
}

sealed interface RoomActionResult {
    data class Ok(val info: RoomInfo? = null) : RoomActionResult
    data class Failed(val reason: String) : RoomActionResult
}

/**
 * 房间生命周期的唯一 owner：角色、状态、房间信息、成员表、发现列表、离线备份与恢复。
 *
 * 不动 `DrawSession` 的抽签语义——房主直接改本地 session（权威），成员的 session 由广播覆盖。
 */
class RoomController(
    private val scope: CoroutineScope,
    private val backupStore: RoomBackupStore,
    private val session: DrawSession,
    private val imageSource: RoomImageSource,
    private val transportFactory: (RoomTransportKind) -> RoomTransport,
    private val hostAddressProvider: () -> String?,
    private val deviceId: String,
    private val initialName: String,
    private val onNameChanged: (String) -> Unit = {},
    private val onSessionChanged: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    /** 局域网建房时的信标发送器；测试里传 null（不广播）。 */
    private val beaconSenderFactory: ((Int) -> LanBeaconSender)? = null,
) {
    private val _role = MutableStateFlow(RoomRole.Offline)
    val role: StateFlow<RoomRole> = _role.asStateFlow()

    private val _status = MutableStateFlow<RoomStatus>(RoomStatus.Idle)
    val status: StateFlow<RoomStatus> = _status.asStateFlow()

    private val _roomInfo = MutableStateFlow<RoomInfo?>(null)
    val roomInfo: StateFlow<RoomInfo?> = _roomInfo.asStateFlow()

    private val _myName = MutableStateFlow(initialName)
    val myName: StateFlow<String> = _myName.asStateFlow()

    /**
     * 真正用于署名/进房的名字：输入框允许为空，但签名不能是空白，
     * 因此为空时回落到设备默认名。
     */
    private val effectiveName: String get() = _myName.value.ifBlank { initialName }

    private var host: RoomHost? = null
    private var client: RoomClient? = null
    private var transport: RoomTransport? = null
    private var beaconSender: LanBeaconSender? = null
    private var jobs = mutableListOf<Job>()
    private var discovery: LanBeaconListener? = null

    /** 加入房间面板打开期间显示的附近房间。 */
    val discoveredRooms: StateFlow<List<DiscoveredRoom>>
        get() = discoveryFlow

    private val discoveryFlow = MutableStateFlow<List<DiscoveredRoom>>(emptyList())
    private var discoveryJob: Job? = null

    fun setMyName(name: String) {
        // 允许临时为空：删掉最后一个字符时不能把这次修改拦回去，
        // 否则输入框会弹回原值，用户感觉「最后一个字删不掉」。
        // 真正要署名/进房时用 effectiveName 兜底。
        val trimmed = name.trim().take(RoomLimits.MAX_NAME_LENGTH)
        _myName.value = trimmed
        onNameChanged(trimmed)
    }

    // ---------- 建房 ----------

    suspend fun hostRoom(request: RoomHostRequest): RoomActionResult {
        if (_role.value != RoomRole.Offline) return RoomActionResult.Failed("已经在房间里了")

        val transport = try {
            transportFactory(request.kind)
        } catch (e: Exception) {
            // 例如蓝牙在后端还没接进来：明确报错，不静默降级成别的传输
            return RoomActionResult.Failed(e.message ?: "这种方式暂时不可用")
        }
        val host = RoomHost(
            session = session,
            transport = transport,
            config = RoomConfig(
                roomName = request.roomName.trim().ifBlank { "抽签房间" },
                hostName = effectiveName,
                allowRecover = request.allowRecover,
                allowEdit = request.allowEdit,
            ),
            imageSource = imageSource,
            scope = scope,
            now = now,
            onApplied = onSessionChanged,
        )

        val endpoint = try {
            // 绑定端口是阻塞调用，放到 IO 线程，别卡住主线程
            withContext(Dispatchers.IO) { host.start() }
        } catch (e: Exception) {
            transport.stop()
            return RoomActionResult.Failed("开房失败：${e.message ?: e::class.simpleName}")
        }

        val roomCode = RoomCode.generate()
        val invite = when (endpoint) {
            is HostEndpoint.Lan -> {
                val address = hostAddressProvider()
                if (address.isNullOrBlank()) {
                    host.stop()
                    return RoomActionResult.Failed("请先连接 Wi-Fi 或开热点")
                }
                beaconSenderFactory?.invoke(endpoint.port)?.also { sender ->
                    beaconSender = sender
                    sender.start(
                        beaconProvider = {
                            LanBeacon(
                                roomName = host.config.roomName,
                                roomCode = roomCode,
                                port = endpoint.port,
                                hostName = effectiveName,
                                revision = host.revision,
                            )
                        },
                        hostAddressProvider = { hostAddressProvider() },
                    )
                }
                RoomInvite(
                    target = RoomTarget.Lan(host = address, port = endpoint.port),
                    roomCode = roomCode,
                    roomName = host.config.roomName,
                )
            }

            is HostEndpoint.Bluetooth -> RoomInvite(
                target = RoomTarget.Bluetooth(serviceUuid = endpoint.serviceUuid),
                roomCode = roomCode,
                roomName = host.config.roomName,
            )
        }

        this.transport = transport
        this.host = host
        _role.value = RoomRole.Host
        _status.value = RoomStatus.Hosting
        val info = RoomInfo(
            roomName = host.config.roomName,
            roomCode = roomCode,
            hostName = effectiveName,
            kind = request.kind,
            members = host.members.value,
            allowRecover = request.allowRecover,
            allowEdit = request.allowEdit,
            invite = invite,
        )
        _roomInfo.value = info
        jobs += scope.launch {
            host.members.collect { members -> _roomInfo.value = _roomInfo.value?.copy(members = members) }
        }
        return RoomActionResult.Ok(info)
    }

    // ---------- 加入 ----------

    suspend fun joinRoom(invite: RoomInvite): RoomActionResult {
        if (_role.value != RoomRole.Offline) return RoomActionResult.Failed("已经在房间里了")

        val kind = when (invite.target) {
            is RoomTarget.Lan -> RoomTransportKind.Lan
            is RoomTarget.Bluetooth -> RoomTransportKind.Bluetooth
        }
        val transport = transportFactory(kind)
        val client = RoomClient(
            transport = transport,
            scope = scope,
            deviceId = deviceId,
            name = effectiveName,
            now = now,
        )

        // 先把自己的离线状态存一份，退出房间时恢复
        backupStore.saveOfflineBackup(session.snapshot())

        this.transport = transport
        this.client = client
        _role.value = RoomRole.Member
        _status.value = RoomStatus.Connecting
        _roomInfo.value = RoomInfo(
            roomName = invite.roomName,
            roomCode = invite.roomCode,
            hostName = "",
            kind = kind,
        )

        jobs += scope.launch {
            client.replica.collect { replica ->
                if (replica == null) return@collect
                // 房间状态整体覆盖本地副本
                session.replaceWith(replica.snapshot)
                _roomInfo.value = _roomInfo.value?.copy(
                    roomName = replica.roomName.ifBlank { invite.roomName },
                    hostName = replica.hostName,
                    members = replica.members,
                    allowRecover = replica.allowRecover,
                    allowEdit = replica.allowEdit,
                )
                onSessionChanged()
            }
        }
        jobs += scope.launch {
            client.status.collect { status ->
                _status.value = when (status) {
                    is ClientStatus.Idle -> RoomStatus.Idle
                    is ClientStatus.Connecting -> RoomStatus.Connecting
                    is ClientStatus.Connected -> RoomStatus.Connected(status.roomName)
                    is ClientStatus.Reconnecting -> RoomStatus.Reconnecting(status.attempt, status.reason)
                    is ClientStatus.Closed -> RoomStatus.Closed(status.reason)
                }
                if (status is ClientStatus.Closed) leaveRoom(status.reason)
            }
        }

        client.connect(invite.target)
        return RoomActionResult.Ok(_roomInfo.value)
    }

    /** 手输房间码：先在发现列表里找，找不到就用调用方补填的地址。 */
    suspend fun joinByCode(
        rawCode: String,
        host: String? = null,
        port: Int? = null,
    ): RoomActionResult {
        val code = RoomCode.normalize(rawCode)
            ?: return RoomActionResult.Failed("房间码不对（应为 6 位）")
        val discovered = discoveryFlow.value.firstOrNull { it.roomCode == code }
        val invite = when {
            discovered != null -> RoomInvite(
                target = discovered.target,
                roomCode = discovered.roomCode,
                roomName = discovered.roomName,
            )

            !host.isNullOrBlank() && port != null -> RoomInvite(
                target = RoomTarget.Lan(host = host.trim(), port = port),
                roomCode = code,
                roomName = "抽签房间",
            )

            else -> null
        }
        return if (invite == null) {
            RoomActionResult.Failed("没找到这个房间，请扫二维码或补填房主地址")
        } else {
            joinRoom(invite)
        }
    }

    // ---------- 退出 ----------

    suspend fun leaveRoom(reason: String = "已退出房间") {
        val wasMember = _role.value == RoomRole.Member
        jobs.forEach { it.cancel() }
        jobs = mutableListOf()
        client?.disconnect(reason)
        host?.stop()
        beaconSender?.stop()
        transport?.let { if (host == null) it.stop() }
        client = null
        host = null
        transport = null
        beaconSender = null
        _role.value = RoomRole.Offline
        _status.value = RoomStatus.Closed(reason)
        _roomInfo.value = null
        if (wasMember) restoreOfflineState()
    }

    /** 把自己加入房间前的状态恢复回来（进程重启时 AppViewModel 也会调用同一个规则）。 */
    fun restoreOfflineState() {
        val backup = backupStore.loadOfflineBackup() ?: return
        session.replaceWith(backup)
        backupStore.clearOfflineBackup()
        onSessionChanged()
    }

    // ---------- 房主/成员的动作入口 ----------

    /** 房主或离线时直接在本地应用；成员返回 null 表示应当走请求。 */
    suspend fun applyAsHost(action: RoomAction): Boolean {
        val host = this.host ?: return false
        host.applyLocal(action)
        return true
    }

    fun clientForRequests(): RoomClient? = client

    suspend fun fetchImage(imageKey: String): ByteArray? = client?.fetchImage(imageKey)

    // ---------- 附近房间发现 ----------

    fun startDiscovery() {
        if (discovery == null) {
            discovery = LanBeaconListener(scope = scope, now = now)
        }
        discovery?.start()
        discovery?.probe()
        if (discoveryJob == null) {
            val listener = discovery ?: return
            discoveryJob = scope.launch {
                listener.rooms.collect { discoveryFlow.value = it }
            }
        }
    }

    fun probeDiscovery() {
        discovery?.probe()
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        discovery?.stop()
        discoveryFlow.value = emptyList()
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = mutableListOf()
        stopDiscovery()
        client?.disconnect()
        host?.stop()
        beaconSender?.stop()
        transport?.stop()
        client = null
        host = null
        transport = null
        beaconSender = null
    }
}
