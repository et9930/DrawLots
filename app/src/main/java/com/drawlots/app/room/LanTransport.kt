package com.drawlots.app.room

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 局域网传输：TCP 长连接。
 *
 * - 房主：`ServerSocket(0)` 随机端口，accept 多个成员，每个连接一个读协程 + 一个写协程。
 * - 成员：连接房主的 `host:port`，只有一条连接，连接 id 固定为 [TransportEvent.SELF_ID]。
 *
 * 每个连接有独立的发送队列，保证多线程同时发送时不会把帧交错写坏。
 */
/** 每连接发送队列上限：对端卡住时丢最旧的帧，而不是无上限堆积。 */
private const val OUTBOUND_QUEUE_CAPACITY = 256

class LanTransport(
    private val connectTimeoutMillis: Int = 5_000,
) : RoomTransport {

    override val maxPayload: Int = RoomLimits.MAX_BINARY_CHUNK_BYTES

    private val _events = MutableSharedFlow<TransportEvent>(replay = 0, extraBufferCapacity = 256)
    override val events: Flow<TransportEvent> = _events

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionIds = AtomicInteger(0)
    private val connections = ConcurrentHashMap<String, Connection>()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var stopped = false

    /** 房主的实际监听端口（端口 0 = 由系统分配）。 */
    @Volatile
    var boundPort: Int = -1
        private set

    override suspend fun startAsHost(): HostEndpoint {
        val server = ServerSocket(0)
        serverSocket = server
        boundPort = server.localPort
        scope.launch { acceptLoop(server) }
        return HostEndpoint.Lan(port = server.localPort)
    }

    override suspend fun connectTo(target: RoomTarget) {
        val lan = target as? RoomTarget.Lan
        if (lan == null) {
            _events.tryEmit(TransportEvent.Failed("局域网传输不支持这个目标"))
            return
        }
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(lan.host, lan.port), connectTimeoutMillis)
        } catch (e: Exception) {
            runCatching { socket.close() }
            _events.tryEmit(TransportEvent.Failed("连接失败：${e.message ?: e::class.simpleName}"))
            return
        }
        val connection = Connection(TransportEvent.SELF_ID, socket).also {
            connections[TransportEvent.SELF_ID] = it
        }
        connection.start()
        _events.emit(TransportEvent.MemberConnected(TransportEvent.SELF_ID))
    }

    override suspend fun send(frame: RoomFrame, to: String?) {
        val targets = if (to == null) connections.values.toList() else listOfNotNull(connections[to])
        if (targets.isEmpty()) {
            _events.emit(TransportEvent.Failed(if (to == null) "尚未连接" else "连接不存在：$to"))
            return
        }
        val bytes = RoomFraming.encode(frame)
        targets.forEach { it.enqueue(bytes) }
    }

    override fun stop() {
        stopped = true
        runCatching { serverSocket?.close() }
        serverSocket = null
        connections.values.toList().forEach { it.close(silent = true) }
        connections.clear()
        scope.cancel()
    }

    private suspend fun acceptLoop(server: ServerSocket) {
        while (!stopped) {
            val socket = try {
                server.accept()
            } catch (e: Exception) {
                if (stopped) return else continue
            }
            val id = "conn-${connectionIds.incrementAndGet()}"
            val connection = Connection(id, socket).also { connections[id] = it }
            connection.start()
            _events.emit(TransportEvent.MemberConnected(id))
        }
    }

    private inner class Connection(val id: String, val socket: Socket) {
        // 有界 + 丢最旧：对端卡住（TCP 缓冲满、进程被冻结）时不要无上限堆积。
        // 状态是全量幂等快照，丢掉旧的那份没有代价；真要掉线，心跳超时会把成员摘掉。
        private val outgoing = Channel<ByteArray>(
            capacity = OUTBOUND_QUEUE_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        private val closed = AtomicBoolean(false)

        fun start() {
            scope.launch { readLoop() }
            scope.launch { writeLoop() }
        }

        fun enqueue(bytes: ByteArray) {
            if (!closed.get()) outgoing.trySend(bytes)
        }

        private suspend fun readLoop() {
            val decoder = RoomFrameDecoder()
            val buffer = ByteArray(8 * 1024)
            try {
                val input = socket.getInputStream()
                while (!stopped) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    for (frame in decoder.feed(buffer, read)) {
                        _events.emit(TransportEvent.FrameReceived(id, frame))
                    }
                    decoder.error?.let { break }
                }
                close(reason = "对方已断开")
            } catch (e: Exception) {
                close(reason = e.message ?: "读取失败")
            }
        }

        private suspend fun writeLoop() {
            try {
                val output = socket.getOutputStream()
                for (bytes in outgoing) {
                    output.write(bytes)
                    output.flush()
                }
            } catch (e: Exception) {
                close(reason = e.message ?: "写入失败")
            }
        }

        fun close(reason: String = "", silent: Boolean = false) {
            if (!closed.compareAndSet(false, true)) return
            runCatching { socket.close() }
            outgoing.close()
            connections.remove(id)
            if (!silent) {
                scope.launch { _events.emit(TransportEvent.MemberDisconnected(id, reason.ifBlank { "已断开" })) }
            }
        }
    }
}
