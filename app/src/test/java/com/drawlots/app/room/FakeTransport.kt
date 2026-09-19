package com.drawlots.app.room

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 测试用假传输：事件由测试注入，发出去的帧被记录下来。
 *
 * 用 `replay` 缓冲事件，避免「事件在收集协程启动前发出」导致的偶发丢事件。
 */
class FakeTransport(
    override val maxPayload: Int = RoomLimits.MAX_BINARY_CHUNK_BYTES,
) : RoomTransport {

    data class SentFrame(val to: String?, val frame: RoomFrame)

    private val _events = MutableSharedFlow<TransportEvent>(
        replay = 64,
        extraBufferCapacity = 64,
    )
    override val events: Flow<TransportEvent> = _events

    /**
     * 记录发出去的帧。
     *
     * 必须是线程安全的：写入来自传输层/房主/客户端各自的后台协程，而测试在主线程遍历读取，
     * 用普通 ArrayList 会偶发 ConcurrentModificationException（本会话真实出现过两次）。
     * CopyOnWriteArrayList 让遍历拿到快照，彻底消除这类偶发。
     */
    val sent = CopyOnWriteArrayList<SentFrame>()

    var hostEndpoint: HostEndpoint = HostEndpoint.Lan(port = 47001)
    var startHostFailure: String? = null
    var connectFailure: String? = null
    var autoConnected: Boolean = true

    override suspend fun startAsHost(): HostEndpoint {
        startHostFailure?.let { throw IllegalStateException(it) }
        return hostEndpoint
    }

    override suspend fun connectTo(target: RoomTarget) {
        val failure = connectFailure
        if (failure != null) {
            _events.emit(TransportEvent.Failed(failure))
            return
        }
        if (autoConnected) _events.emit(TransportEvent.MemberConnected(TransportEvent.SELF_ID))
    }

    override suspend fun send(frame: RoomFrame, to: String?) {
        sent += SentFrame(to, frame)
    }

    override fun stop() = Unit

    suspend fun emit(event: TransportEvent) {
        _events.emit(event)
    }

    fun messages(): List<RoomMessage> = sent.mapNotNull { (it.frame as? RoomFrame.Json)?.message }

    fun messagesTo(memberId: String): List<RoomMessage> =
        sent.filter { it.to == memberId }.mapNotNull { (it.frame as? RoomFrame.Json)?.message }

    fun welcomes(): List<RoomMessage.Welcome> = messages().filterIsInstance<RoomMessage.Welcome>()

    fun states(): List<RoomMessage.RoomState> = messages().filterIsInstance<RoomMessage.RoomState>()

    fun failures(): List<RoomMessage.Failure> = messages().filterIsInstance<RoomMessage.Failure>()

    fun acks(): List<RoomMessage.Ack> = messages().filterIsInstance<RoomMessage.Ack>()

    fun requests(): List<RoomMessage.Request> = messages().filterIsInstance<RoomMessage.Request>()

    fun hellos(): List<RoomMessage.Hello> = messages().filterIsInstance<RoomMessage.Hello>()

    fun pongs(): List<RoomMessage.Pong> = messages().filterIsInstance<RoomMessage.Pong>()

    fun images(): List<RoomMessage> = messages().filter {
        it is RoomMessage.ImageRequest || it is RoomMessage.ImageBegin || it is RoomMessage.ImageEnd
    }

    fun binaryFrames(id: String? = null): List<RoomFrame.Binary> =
        sent.mapNotNull { it.frame as? RoomFrame.Binary }.filter { id == null || it.id == id }

    fun clear() {
        sent.clear()
    }
}
