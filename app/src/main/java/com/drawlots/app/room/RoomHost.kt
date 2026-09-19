package com.drawlots.app.room

import com.drawlots.app.domain.DrawSession
import com.drawlots.app.domain.LotKind
import com.drawlots.app.domain.MAX_QUANTITY
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 房间的房主侧配置。 */
data class RoomConfig(
    val roomName: String,
    val hostName: String,
    val allowRecover: Boolean = false,
    val allowEdit: Boolean = false,
)

/** 房主给别人发图片时用的读取接口（Android 侧按 imageKey 读本地文件；测试里给假实现）。 */
fun interface RoomImageSource {
    suspend fun read(imageKey: String): ByteArray?
}

/**
 * 房主：房间状态的唯一权威。
 *
 * - 所有变更（本机操作与成员请求）都在同一把锁里串行应用，应用完 `revision++` 并广播全量状态；
 * - `draw` 由房主写入抽签人署名：成员请求用成员名字，房主自己抽用自己的名字；
 * - 权限矩阵：`draw`/`setNote` 人人可做；`undo`/`putBack` 需要 `allowRecover`；
 *   签池编辑需要 `allowEdit`（且成员只能加文字签）；`resetPool` 只有房主能做。
 */
private const val BROADCAST_MIN_INTERVAL_MS = 300L

@Suppress("LongParameterList")
class RoomHost(
    private val session: DrawSession,
    private val transport: RoomTransport,
    val config: RoomConfig,
    private val imageSource: RoomImageSource,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    /** 每次应用变更后回调，让房主自己的界面刷新。 */
    private val onApplied: () -> Unit = {},
) {
    private data class MemberConnection(
        val deviceId: String = "",
        val name: String? = null,
        val lastSeenAtMillis: Long = 0L,
    )

    private val applyLock = Mutex()
    private val connections = LinkedHashMap<String, MemberConnection>()
    private var jobs = mutableListOf<Job>()

    @Volatile
    private var currentRevision = 0L
    val revision: Long get() = currentRevision

    private val _members = MutableStateFlow(listOf(config.hostName))
    /** 展示用成员名列表，第一位是房主。 */
    val members: StateFlow<List<String>> = _members.asStateFlow()

    /** 上一次真正发出状态广播的时间；用于密集广播时的合并限流。 */
    private var lastBroadcastAtMillis = 0L
    private val broadcastPending = java.util.concurrent.atomic.AtomicBoolean(false)

    suspend fun start(): HostEndpoint {
        val endpoint = transport.startAsHost()
        jobs += scope.launch { transport.events.collect { handleEvent(it) } }
        jobs += scope.launch {
            while (isActive) {
                delay(RoomLimits.HEARTBEAT_INTERVAL_MS)
                pruneIdleMembers()
            }
        }
        // 合并后的补发：只对慢链路（BLE）启用；局域网逐条直发，不需要这套机制
        if (transport.isSlowLink) {
            jobs += scope.launch {
                while (isActive) {
                    delay(BROADCAST_MIN_INTERVAL_MS)
                    if (broadcastPending.compareAndSet(true, false)) {
                        lastBroadcastAtMillis = now()
                        transport.send(RoomFrame.Json(stateMessage()))
                    }
                }
            }
        }
        return endpoint
    }

    /** 房主自己的本地操作：走同一条应用路径，因此也会 `revision++` 并广播。 */
    suspend fun applyLocal(action: RoomAction) {
        applyAction(action = action, requesterName = config.hostName, isHost = true, requestId = null, fromMemberId = null)
    }

    /** 把超时没心跳的成员踢掉（心跳 ticker 会调用；测试里直接调用即可）。 */
    suspend fun pruneIdleMembers() {
        val cutoff = now() - RoomLimits.MEMBER_TIMEOUT_MS
        val stale = connections.filterValues { it.name != null && it.lastSeenAtMillis < cutoff }.keys.toList()
        if (stale.isEmpty()) return
        stale.forEach { connections.remove(it) }
        publishMembers()
        broadcastState()
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        transport.stop()
    }

    // ---------- 事件与消息 ----------

    private suspend fun handleEvent(event: TransportEvent) {
        when (event) {
            is TransportEvent.MemberConnected -> connections[event.memberId] = MemberConnection()
            is TransportEvent.MemberDisconnected -> {
                connections.remove(event.memberId)
                publishMembers()
                broadcastState()
            }

            is TransportEvent.FrameReceived -> handleFrame(event.memberId, event.frame)
            is TransportEvent.Failed -> Unit // 传输层自身的错误不影响房间状态
        }
    }

    private suspend fun handleFrame(memberId: String, frame: RoomFrame) {
        val message = (frame as? RoomFrame.Json)?.message ?: return
        when (message) {
            is RoomMessage.Hello -> handleHello(memberId, message)
            is RoomMessage.Ping -> {
                touch(memberId)
                transport.send(RoomFrame.Json(RoomMessage.Pong(message.ts)), to = memberId)
            }

            is RoomMessage.Request -> handleRequest(memberId, message)
            RoomMessage.Resync -> transport.send(RoomFrame.Json(stateMessage()), to = memberId)
            is RoomMessage.ImageRequest -> handleImageRequest(memberId, message)
            else -> Unit
        }
    }

    private suspend fun handleHello(memberId: String, hello: RoomMessage.Hello) {
        if (hello.protocolVersion != RoomLimits.PROTOCOL_VERSION) {
            transport.send(
                RoomFrame.Json(
                    RoomMessage.Failure(
                        id = null,
                        code = RoomErrors.VERSION_MISMATCH,
                        message = "版本不一致，请两台手机安装同一版本",
                    ),
                ),
                to = memberId,
            )
            connections.remove(memberId)
            return
        }
        connections[memberId] = MemberConnection(
            deviceId = hello.deviceId,
            name = sanitizeName(hello.name),
            lastSeenAtMillis = now(),
        )
        // 先更新成员表再发 welcome，新成员才能在名单里看到自己
        publishMembers()
        transport.send(RoomFrame.Json(welcomeMessage()), to = memberId)
        broadcastState()
    }

    private suspend fun handleRequest(memberId: String, request: RoomMessage.Request) {
        val member = connections[memberId]
        if (member?.name == null) {
            transport.send(
                RoomFrame.Json(RoomMessage.Failure(request.id, RoomErrors.BAD_REQUEST, "请先发送 hello")),
                to = memberId,
            )
            return
        }
        touch(memberId)
        applyAction(
            action = request.action,
            requesterName = member.name,
            isHost = false,
            requestId = request.id,
            fromMemberId = memberId,
        )
    }

    private suspend fun handleImageRequest(memberId: String, request: RoomMessage.ImageRequest) {
        touch(memberId)
        val bytes = imageSource.read(request.imageKey)
        when {
            bytes == null || bytes.isEmpty() -> transport.send(
                RoomFrame.Json(RoomMessage.Failure(request.id, RoomErrors.NOT_FOUND, "图片不存在")),
                to = memberId,
            )

            bytes.size > RoomLimits.MAX_IMAGE_BYTES -> transport.send(
                RoomFrame.Json(RoomMessage.Failure(request.id, RoomErrors.TOO_LARGE, "图片太大")),
                to = memberId,
            )

            else -> {
                val chunkSize = transport.maxPayload.coerceIn(256, RoomLimits.MAX_BINARY_CHUNK_BYTES)
                transport.send(
                    RoomFrame.Json(RoomMessage.ImageBegin(request.id, request.imageKey, bytes.size)),
                    to = memberId,
                )
                var offset = 0
                while (offset < bytes.size) {
                    val end = minOf(offset + chunkSize, bytes.size)
                    transport.send(
                        RoomFrame.Binary(
                            id = request.id,
                            offset = offset,
                            bytes = bytes.copyOfRange(offset, end),
                        ),
                        to = memberId,
                    )
                    offset = end
                }
                transport.send(RoomFrame.Json(RoomMessage.ImageEnd(request.id)), to = memberId)
            }
        }
    }

    // ---------- 应用与广播 ----------

    private suspend fun applyAction(
        action: RoomAction,
        requesterName: String,
        isHost: Boolean,
        requestId: String?,
        fromMemberId: String?,
    ) {
        val rejection = applyLock.withLock {
            // 多人同时抽签时，可能轮到某人时签已经被别人抽光了：
            // 明确回一个错误，而不是「成功但没有结果」让对方的界面干等。
            val error = permissionError(action, isHost)
                ?: if (action is RoomAction.Draw && session.totalRemaining <= 0) {
                    RoomErrors.BAD_REQUEST
                } else {
                    null
                }
            if (error == null) {
                apply(action, drawerName = requesterName)
                currentRevision++
                // 立刻反映到房主本地，并且立刻广播（见下面 Draw 的分支）：
                // 不做「房主延后揭示」——各设备自己按「拿到结果的时刻 + 一个滚动时长」计时，
                // 谁也不用等谁，误差只有一个链路延迟。
                onApplied()
            }
            error
        }

        if (fromMemberId != null) {
            if (rejection != null) {
                transport.send(
                    RoomFrame.Json(RoomMessage.Failure(requestId, rejection, describeError(rejection))),
                    to = fromMemberId,
                )
            } else if (requestId != null) {
                transport.send(RoomFrame.Json(RoomMessage.Ack(requestId, currentRevision)), to = fromMemberId)
            }
        }
        if (rejection == null) {
            // 发起人优先：直发一份最新状态，不等合并限流
            if (fromMemberId != null) {
                transport.send(RoomFrame.Json(stateMessage()), to = fromMemberId)
            }
            if (action is RoomAction.Draw) {
                // 抽签结果**立刻广播**给所有人（不等 300ms 合并）：各设备收到后自己计时揭示
                lastBroadcastAtMillis = now()
                transport.send(RoomFrame.Json(stateMessage()))
            } else {
                broadcastState()
            }
        }
    }

    private fun permissionError(action: RoomAction, isHost: Boolean): String? {
        if (isHost) return null
        return when (action) {
            is RoomAction.Draw, is RoomAction.SetNote -> null
            is RoomAction.Undo, is RoomAction.PutBack ->
                if (config.allowRecover) null else RoomErrors.NOT_ALLOWED

            RoomAction.ResetPool -> RoomErrors.NOT_ALLOWED
            is RoomAction.SetMode -> RoomErrors.NOT_ALLOWED
            is RoomAction.AddLot, is RoomAction.UpdateLot ->
                when {
                    !config.allowEdit -> RoomErrors.NOT_ALLOWED
                    // v1：成员只能加文字签，图片签只能房主添加（图片文件在房主设备上）
                    action is RoomAction.AddLot && action.lot.kind == LotKind.IMAGE -> RoomErrors.NOT_ALLOWED
                    action is RoomAction.UpdateLot && action.lot.kind == LotKind.IMAGE -> RoomErrors.NOT_ALLOWED
                    else -> null
                }

            is RoomAction.RemoveLot, is RoomAction.SetQuantity, RoomAction.ClearPool ->
                if (config.allowEdit) null else RoomErrors.NOT_ALLOWED
        }
    }

    private fun apply(action: RoomAction, drawerName: String) {
        applyRoomAction(session, action, drawerName)
    }

    private fun welcomeMessage(): RoomMessage.Welcome = RoomMessage.Welcome(
        roomName = config.roomName,
        hostName = config.hostName,
        allowRecover = config.allowRecover,
        allowEdit = config.allowEdit,
        revision = currentRevision,
        members = _members.value,
        state = RoomMessages.encodeState(session.snapshot()),
    )

    private fun stateMessage(): RoomMessage.RoomState = RoomMessage.RoomState(
        revision = currentRevision,
        allowRecover = config.allowRecover,
        allowEdit = config.allowEdit,
        members = _members.value,
        state = RoomMessages.encodeState(session.snapshot()),
    )

    /**
     * 广播最新状态：链路空闲时**立刻**发（保持原有同步语义），密集时合并成稍后的一份。
     *
     * 为什么必须限流（真机教训）：局域网随便广播没问题，但 BLE 每包只有 20 字节、约 1.5KB/s。
     * 真机上抓到过「一次成员请求触发约 350 次 apply」，几千个几百字节的全量状态帧把链路灌满，
     * 成员的 `ack` 被挤到几分钟之后才到，表现就是「抽签提示房主无响应」——而心跳还在流动，
     * 所以连自动重连都不会触发。全量状态是幂等快照，中间的旧快照丢掉没有任何代价，
     * 但**响应帧（ack/error）必须保持直发**，不能被合并。
     */
    private suspend fun broadcastState() {
        if (!transport.isSlowLink) {
            // 快链路（局域网）：保持逐条直发，语义最简单
            transport.send(RoomFrame.Json(stateMessage()))
            return
        }
        val nowMillis = now()
        if (nowMillis - lastBroadcastAtMillis >= BROADCAST_MIN_INTERVAL_MS) {
            lastBroadcastAtMillis = nowMillis
            transport.send(RoomFrame.Json(stateMessage()))
        } else {
            broadcastPending.set(true)
        }
    }

    private fun touch(memberId: String) {
        val existing = connections[memberId] ?: return
        connections[memberId] = existing.copy(lastSeenAtMillis = now())
    }

    private fun publishMembers() {
        _members.value = listOf(config.hostName) + connections.values.mapNotNull { it.name }
    }

    private fun sanitizeName(raw: String): String {
        val trimmed = raw.trim().take(RoomLimits.MAX_NAME_LENGTH)
        return trimmed.ifBlank { "某位" }
    }

    private fun describeError(code: String): String = when (code) {
        RoomErrors.NOT_ALLOWED -> "房主未开放这个操作"
        RoomErrors.BAD_REQUEST -> "请求格式不对"
        RoomErrors.NOT_FOUND -> "找不到对应内容"
        RoomErrors.TOO_LARGE -> "内容太大"
        RoomErrors.BUSY -> "房主忙"
        else -> "操作失败"
    }
}
