package com.drawlots.app.room

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.drawlots.app.domain.DrawMode
import com.drawlots.app.domain.Lot
import com.drawlots.app.domain.LotKind
import com.drawlots.app.domain.PoolSnapshot

/**
 * 局域网传输的回环集成测试：真 [java.net.ServerSocket] ↔ 真 socket，
 * 跑通「房主监听 → 成员连接 → 双向收发（含二进制分片）→ 断开事件」。
 */
class LanTransportLoopbackTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cleanups = mutableListOf<() -> Unit>()

    @After
    fun tearDown() {
        cleanups.forEach { runCatching { it() } }
        scope.cancel()
    }

    private fun track(transport: RoomTransport) {
        cleanups += { transport.stop() }
    }

    private fun collect(transport: RoomTransport): Channel<TransportEvent> {
        val channel = Channel<TransportEvent>(Channel.UNLIMITED)
        scope.launch { transport.events.collect { channel.send(it) } }
        return channel
    }

    private suspend fun Channel<TransportEvent>.awaitConnect(): String = withTimeout(TIMEOUT) {
        while (true) {
            when (val event = receive()) {
                is TransportEvent.MemberConnected -> return@withTimeout event.memberId
                is TransportEvent.Failed -> error("传输失败：${event.reason}")
                else -> Unit
            }
        }
        error("unreachable")
    }

    private suspend fun Channel<TransportEvent>.awaitFrame(): Pair<String, RoomFrame> = withTimeout(TIMEOUT) {
        while (true) {
            when (val event = receive()) {
                is TransportEvent.FrameReceived -> return@withTimeout event.memberId to event.frame
                is TransportEvent.Failed -> error("传输失败：${event.reason}")
                else -> Unit
            }
        }
        error("unreachable")
    }

    private suspend fun Channel<TransportEvent>.awaitDisconnect(): String = withTimeout(TIMEOUT) {
        while (true) {
            when (val event = receive()) {
                is TransportEvent.MemberDisconnected -> return@withTimeout event.memberId
                else -> Unit
            }
        }
        error("unreachable")
    }

    private fun sampleState(): PoolSnapshot = PoolSnapshot(
        lots = listOf(Lot(id = "l1", kind = LotKind.TEXT, text = "张三", quantity = 2)),
        mode = DrawMode.WITHOUT_REPLACEMENT,
    )

    @Test
    fun hostAndClientExchangeJsonAndBinaryFrames() = runBlocking {
        val host = LanTransport()
        val client = LanTransport()
        track(host)
        track(client)
        val hostEvents = collect(host)
        val clientEvents = collect(client)

        val endpoint = host.startAsHost() as HostEndpoint.Lan
        client.connectTo(RoomTarget.Lan(host = "127.0.0.1", port = endpoint.port))

        val clientId = clientEvents.awaitConnect()
        assertEquals(TransportEvent.SELF_ID, clientId)
        val memberId = hostEvents.awaitConnect()

        // 成员 → 房主：hello
        client.send(RoomFrame.Json(RoomMessage.Hello(RoomLimits.PROTOCOL_VERSION, "dev-1", "小明")))
        val (receivedFrom, helloFrame) = hostEvents.awaitFrame()
        assertEquals(memberId, receivedFrom)
        assertEquals(RoomMessage.Hello(RoomLimits.PROTOCOL_VERSION, "dev-1", "小明"), (helloFrame as RoomFrame.Json).message)

        // 房主 → 成员：带完整状态的 welcome
        val welcome = RoomMessage.Welcome(
            roomName = "周五抽签",
            hostName = "房主",
            allowRecover = false,
            allowEdit = false,
            revision = 1L,
            members = listOf("房主", "小明"),
            state = RoomMessages.encodeState(sampleState()),
        )
        host.send(RoomFrame.Json(welcome), to = memberId)
        val (_, welcomeFrame) = clientEvents.awaitFrame()
        val receivedWelcome = (welcomeFrame as RoomFrame.Json).message as RoomMessage.Welcome
        assertEquals(welcome.roomName, receivedWelcome.roomName)
        assertEquals(sampleState(), RoomMessages.decodeState(receivedWelcome.state))

        // 二进制分片（图片）往返
        val bytes = ByteArray(3000) { (it % 251).toByte() }
        client.send(RoomFrame.Binary(id = "img-1", offset = 0, bytes = bytes))
        val (_, binaryFrame) = hostEvents.awaitFrame()
        val binary = binaryFrame as RoomFrame.Binary
        assertEquals("img-1", binary.id)
        assertTrue(bytes.contentEquals(binary.bytes))
    }

    @Test
    fun hostSeesDisconnectWhenClientStops() = runBlocking {
        val host = LanTransport()
        val client = LanTransport()
        track(host)
        track(client)
        val hostEvents = collect(host)

        val endpoint = host.startAsHost() as HostEndpoint.Lan
        client.connectTo(RoomTarget.Lan(host = "127.0.0.1", port = endpoint.port))
        val memberId = hostEvents.awaitConnect()

        client.stop()

        assertEquals(memberId, hostEvents.awaitDisconnect())
    }

    @Test
    fun multipleClientsAreTrackedSeparately() = runBlocking {
        val host = LanTransport()
        val first = LanTransport()
        val second = LanTransport()
        track(host)
        track(first)
        track(second)
        val hostEvents = collect(host)
        val firstEvents = collect(first)
        val secondEvents = collect(second)

        val endpoint = host.startAsHost() as HostEndpoint.Lan
        first.connectTo(RoomTarget.Lan("127.0.0.1", endpoint.port))
        val firstId = hostEvents.awaitConnect()
        second.connectTo(RoomTarget.Lan("127.0.0.1", endpoint.port))
        val secondId = hostEvents.awaitConnect()

        assertTrue("两个成员应有不同 id", firstId != secondId)

        // 只发给第一个成员
        host.send(RoomFrame.Json(RoomMessage.Ping(1L)), to = firstId)
        val (fromFirst, firstFrame) = firstEvents.awaitFrame()
        assertEquals(TransportEvent.SELF_ID, fromFirst)
        assertEquals(RoomMessage.Ping(1L), (firstFrame as RoomFrame.Json).message)

        // 广播：第二个成员收到的第一帧应该是广播的 Pong（说明定向发送没串到它）
        host.send(RoomFrame.Json(RoomMessage.Pong(2L)))
        val (_, secondFrame) = secondEvents.awaitFrame()
        assertEquals(RoomMessage.Pong(2L), (secondFrame as RoomFrame.Json).message)
    }

    @Test
    fun sendingToUnknownMemberReportsFailure() = runBlocking {
        val host = LanTransport()
        track(host)
        val hostEvents = collect(host)
        host.startAsHost()

        host.send(RoomFrame.Json(RoomMessage.Ping(1L)), to = "conn-does-not-exist")

        withTimeout(TIMEOUT) {
            while (true) {
                val event = hostEvents.receive()
                if (event is TransportEvent.Failed) {
                    assertTrue(event.reason.contains("连接不存在"))
                    return@withTimeout
                }
            }
        }
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
