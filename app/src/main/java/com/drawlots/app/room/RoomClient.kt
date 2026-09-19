package com.drawlots.app.room

import com.drawlots.app.domain.PoolSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** 成员侧的连接状态。 */
sealed interface ClientStatus {
    data object Idle : ClientStatus
    data object Connecting : ClientStatus
    data class Connected(val roomName: String) : ClientStatus
    data class Reconnecting(val attempt: Int, val reason: String) : ClientStatus
    data class Closed(val reason: String) : ClientStatus
}

/** 成员侧持有的房间副本（由房主广播覆盖）。 */
data class RoomReplica(
    val roomName: String,
    val hostName: String,
    val allowRecover: Boolean,
    val allowEdit: Boolean,
    val revision: Long,
    val members: List<String>,
    val snapshot: PoolSnapshot,
)

sealed interface RequestResult {
    data class Ok(val revision: Long) : RequestResult
    data class Failed(val reason: String) : RequestResult
}

/** 房主返回的 error 消息（带上错误码，便于区分“未开放”和“格式不对”）。 */
class RoomRequestException(val code: String, message: String) : Exception(message)

/**
 * 成员：只持有副本，所有变更都作为请求发给房主。
 *
 * - 收到 `welcome`/`state` 就整体覆盖副本；`revision` 落后或跳号时主动 `resync`；
 * - 请求 5 秒没有 ack 就报「房主没有响应」；
 * - 心跳 5 秒一次，15 秒收不到房主任何消息就判定断线并按 1/2/4/8/15 秒退避重连；
 * - 图片按 `imageRequest` 分片拉取并拼装。
 */
class RoomClient(
    private val transport: RoomTransport,
    private val scope: CoroutineScope,
    private val deviceId: String,
    private val name: String,
    private val backoffMillis: List<Long> = DEFAULT_BACKOFF_MILLIS,
    private val connectWaitMillis: Long = DEFAULT_CONNECT_WAIT_MILLIS,
    private val heartbeatIntervalMillis: Long = RoomLimits.HEARTBEAT_INTERVAL_MS,
    private val imageTimeoutMillis: Long = RoomLimits.LAN_IMAGE_TIMEOUT_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class ImageBuffer(val expectedSize: Int) {
        val bytes = ByteArray(expectedSize)
        var filled = 0
    }

    private val _status = MutableStateFlow<ClientStatus>(ClientStatus.Idle)
    val status: StateFlow<ClientStatus> = _status.asStateFlow()

    private val _replica = MutableStateFlow<RoomReplica?>(null)
    val replica: StateFlow<RoomReplica?> = _replica.asStateFlow()

    private val pendingAcks = ConcurrentHashMap<String, CompletableDeferred<Long>>()
    private val pendingImages = ConcurrentHashMap<String, CompletableDeferred<ByteArray>>()
    private val imageBuffers = ConcurrentHashMap<String, ImageBuffer>()
    private val counter = AtomicInteger(0)

    private var target: RoomTarget? = null
    private var jobs = mutableListOf<Job>()
    private var reconnectJob: Job? = null

    @Volatile
    private var lastHostSeenAtMillis = 0L

    suspend fun connect(target: RoomTarget) {
        this.target = target
        jobs.forEach { it.cancel() }
        jobs = mutableListOf()
        _status.value = ClientStatus.Connecting
        lastHostSeenAtMillis = now()
        jobs += scope.launch { transport.events.collect { handleEvent(it) } }
        jobs += scope.launch {
            while (isActive) {
                delay(heartbeatIntervalMillis)
                heartbeatTick()
            }
        }
        // 建连是阻塞调用（带超时），放到 IO 线程
        withContext(Dispatchers.IO) { transport.connectTo(target) }
    }

    /** 让 UI 主动放弃最后的几帧（例如房主结束房间）。 */
    fun disconnect(reason: String = "已退出房间") {
        jobs.forEach { it.cancel() }
        jobs = mutableListOf()
        reconnectJob?.cancel()
        pendingAcks.values.forEach { it.cancel() }
        pendingAcks.clear()
        pendingImages.values.forEach { it.cancel() }
        pendingImages.clear()
        imageBuffers.clear()
        _status.value = ClientStatus.Closed(reason)
        transport.stop()
    }

    /** 发一个请求并等 ack。 */
    suspend fun request(
        action: RoomAction,
        timeoutMillis: Long = RoomLimits.REQUEST_TIMEOUT_MS,
    ): RequestResult {
        val id = "req-${counter.incrementAndGet()}"
        val deferred = CompletableDeferred<Long>()
        pendingAcks[id] = deferred
        try {
            transport.send(RoomFrame.Json(RoomMessage.Request(id, action)))
            return RequestResult.Ok(withTimeout(timeoutMillis) { deferred.await() })
        } catch (e: TimeoutCancellationException) {
            return RequestResult.Failed("房主没有响应")
        } catch (e: RoomRequestException) {
            return RequestResult.Failed(e.message ?: "操作被拒绝")
        } catch (e: Exception) {
            return RequestResult.Failed(e.message ?: "发送失败")
        } finally {
            pendingAcks.remove(id)
        }
    }

    /** 按 imageKey 向房主拉一张图；失败返回 null。 */
    suspend fun fetchImage(imageKey: String): ByteArray? {
        val id = "img-${counter.incrementAndGet()}"
        val deferred = CompletableDeferred<ByteArray>()
        pendingImages[id] = deferred
        return try {
            transport.send(RoomFrame.Json(RoomMessage.ImageRequest(id, imageKey)))
            withTimeout(imageTimeoutMillis) { deferred.await() }
        } catch (e: Exception) {
            null
        } finally {
            pendingImages.remove(id)
            imageBuffers.remove(id)
        }
    }

    /** 心跳：发 ping，并检查房主是否太久没消息。ticker 会调用；测试里可直接调用。 */
    suspend fun heartbeatTick() {
        if (_status.value !is ClientStatus.Connected) return
        if (now() - lastHostSeenAtMillis > RoomLimits.MEMBER_TIMEOUT_MS) {
            scheduleReconnect("与房主失去联系")
            return
        }
        transport.send(RoomFrame.Json(RoomMessage.Ping(now())))
    }

    // ---------- 事件 ----------

    private suspend fun handleEvent(event: TransportEvent) {
        when (event) {
            is TransportEvent.MemberConnected -> if (_status.value !is ClientStatus.Connected) {
                transport.send(
                    RoomFrame.Json(
                        RoomMessage.Hello(
                            protocolVersion = RoomLimits.PROTOCOL_VERSION,
                            deviceId = deviceId,
                            name = name,
                        ),
                    ),
                )
            }

            is TransportEvent.MemberDisconnected -> scheduleReconnect(event.reason)
            is TransportEvent.FrameReceived -> handleFrame(event.frame)
            is TransportEvent.Failed -> {
                if (_status.value is ClientStatus.Connected) {
                    scheduleReconnect(event.reason)
                } else {
                    _status.value = ClientStatus.Closed(event.reason)
                }
            }
        }
    }

    private suspend fun handleFrame(frame: RoomFrame) {
        lastHostSeenAtMillis = now()
        when (frame) {
            is RoomFrame.Binary -> {
                val buffer = imageBuffers[frame.id] ?: return
                if (frame.offset < 0 || frame.offset + frame.bytes.size > buffer.bytes.size) return
                frame.bytes.copyInto(buffer.bytes, frame.offset)
                buffer.filled += frame.bytes.size
            }

            is RoomFrame.Json -> handleMessage(frame.message)
        }
    }

    private suspend fun handleMessage(message: RoomMessage) {
        when (message) {
            is RoomMessage.Welcome -> {
                install(
                    RoomReplica(
                        roomName = message.roomName,
                        hostName = message.hostName,
                        allowRecover = message.allowRecover,
                        allowEdit = message.allowEdit,
                        revision = message.revision,
                        members = message.members,
                        snapshot = RoomMessages.decodeState(message.state),
                    ),
                )
                _status.value = ClientStatus.Connected(message.roomName)
                reconnectJob?.cancel()
            }

            is RoomMessage.RoomState -> {
                val current = _replica.value
                when {
                    current == null -> install(message.toReplica())
                    message.revision < current.revision -> Unit // 旧帧，忽略
                    message.revision == current.revision -> install(message.toReplica())
                    message.revision == current.revision + 1 -> install(message.toReplica())
                    else -> {
                        // 跳号说明中间少了帧（比如刚重连上来），请求一次全量
                        transport.send(RoomFrame.Json(RoomMessage.Resync))
                    }
                }
            }

            is RoomMessage.Ack -> pendingAcks.remove(message.id)?.complete(message.revision)

            is RoomMessage.Failure -> {
                val pending = message.id?.let { pendingAcks.remove(it) }
                if (pending != null) {
                    pending.completeExceptionally(RoomRequestException(message.code, message.message))
                } else if (message.code == RoomErrors.VERSION_MISMATCH) {
                    _status.value = ClientStatus.Closed(message.message)
                } else {
                    pendingImages.remove(message.id)?.completeExceptionally(RoomRequestException(message.code, message.message))
                }
            }

            is RoomMessage.ImageBegin -> imageBuffers[message.id] = ImageBuffer(message.size)

            is RoomMessage.ImageEnd -> {
                val buffer = imageBuffers.remove(message.id)
                val deferred = pendingImages.remove(message.id)
                if (buffer != null && deferred != null) {
                    if (buffer.filled >= buffer.bytes.size) {
                        deferred.complete(buffer.bytes)
                    } else {
                        deferred.completeExceptionally(RoomRequestException(RoomErrors.BAD_REQUEST, "图片传输不完整"))
                    }
                }
            }

            is RoomMessage.Bye -> {
                _status.value = ClientStatus.Closed(message.reason.ifBlank { "房间已结束" })
                reconnectJob?.cancel()
            }

            else -> Unit
        }
    }

    private fun RoomMessage.RoomState.toReplica(): RoomReplica = RoomReplica(
        roomName = _replica.value?.roomName ?: "",
        hostName = _replica.value?.hostName ?: "",
        allowRecover = allowRecover,
        allowEdit = allowEdit,
        revision = revision,
        members = members,
        snapshot = RoomMessages.decodeState(state),
    )

    private fun install(replica: RoomReplica) {
        _replica.value = replica
    }

    private fun scheduleReconnect(reason: String) {
        val destination = target ?: return
        if (reconnectJob?.isActive == true) return
        _replica.value = _replica.value // 保留副本，界面继续显示最后一次状态
        _status.value = ClientStatus.Reconnecting(attempt = 1, reason = reason)
        reconnectJob = scope.launch {
            var index = 0
            while (isActive && _status.value !is ClientStatus.Connected) {
                delay(backoffMillis[minOf(index, backoffMillis.lastIndex)])
                if (!isActive) return@launch
                index++
                _status.value = ClientStatus.Reconnecting(attempt = index, reason = reason)
                withContext(Dispatchers.IO) { transport.connectTo(destination) }
                val connected = withTimeoutOrNull(connectWaitMillis) {
                    status.first { it is ClientStatus.Connected }
                }
                if (connected != null) return@launch
            }
        }
    }

    companion object {
        val DEFAULT_BACKOFF_MILLIS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)
        const val DEFAULT_CONNECT_WAIT_MILLIS = 8_000L
    }
}
