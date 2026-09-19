package com.drawlots.app.room

import com.drawlots.app.domain.DrawRecord
import com.drawlots.app.domain.DrawMode
import com.drawlots.app.domain.Lot
import com.drawlots.app.domain.LotKind
import com.drawlots.app.domain.PoolSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 成员逻辑测试：副本一致性、请求-ack、resync、心跳与重连、图片拼装。 */
class RoomClientTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var clock = 0L

    @After
    fun tearDown() = scope.cancel()

    private val state = PoolSnapshot(
        lots = listOf(Lot(id = "l1", kind = LotKind.TEXT, text = "张三", quantity = 2)),
        results = listOf(
            DrawRecord(
                seq = 1,
                lotId = "l1",
                kind = LotKind.TEXT,
                text = "张三",
                imagePath = null,
                consumedCopy = true,
                mode = DrawMode.WITHOUT_REPLACEMENT,
                atMillis = 1L,
                drawnBy = "小明",
            ),
        ),
        mode = DrawMode.WITHOUT_REPLACEMENT,
    )

    private fun client(
        transport: FakeTransport = FakeTransport(),
        backoffMillis: List<Long> = listOf(10L),
        connectWaitMillis: Long = 300L,
    ): Pair<RoomClient, FakeTransport> {
        val client = RoomClient(
            transport = transport,
            scope = scope,
            deviceId = "dev-me",
            name = "小明",
            backoffMillis = backoffMillis,
            connectWaitMillis = connectWaitMillis,
            heartbeatIntervalMillis = 50L,
            imageTimeoutMillis = 1_000L,
            now = { clock },
        )
        return client to transport
    }

    private fun welcome(
        revision: Long = 1L,
        roomName: String = "周五抽签",
        allowRecover: Boolean = false,
        allowEdit: Boolean = false,
        members: List<String> = listOf("房主", "小明"),
        snapshot: PoolSnapshot = state,
    ) = RoomMessage.Welcome(
        roomName = roomName,
        hostName = "房主",
        allowRecover = allowRecover,
        allowEdit = allowEdit,
        revision = revision,
        members = members,
        state = RoomMessages.encodeState(snapshot),
    )

    private fun stateMessage(revision: Long, snapshot: PoolSnapshot = state) = RoomMessage.RoomState(
        revision = revision,
        allowRecover = true,
        allowEdit = true,
        members = listOf("房主", "小明"),
        state = RoomMessages.encodeState(snapshot),
    )

    private suspend fun RoomClient.connectAndWelcome(transport: FakeTransport, revision: Long = 1L) {
        connect(RoomTarget.Lan("10.0.0.2", 47001))
        awaitUntil("hello") { transport.hellos().isNotEmpty() }
        transport.emit(TransportEvent.FrameReceived(TransportEvent.SELF_ID, RoomFrame.Json(welcome(revision = revision))))
        awaitUntil("连接完成") { status.value is ClientStatus.Connected }
    }

    private suspend fun awaitUntil(what: String, condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(5)
        }
        assertTrue("等待超时：$what", condition())
    }

    @Test
    fun welcomeInstallsTheReplicaAndSendsHello() = runBlocking {
        val (client, transport) = client()

        client.connectAndWelcome(transport)

        val replica = client.replica.value
        assertEquals("周五抽签", replica?.roomName)
        assertEquals("房主", replica?.hostName)
        assertEquals(1L, replica?.revision)
        assertEquals(listOf("房主", "小明"), replica?.members)
        assertEquals(state, replica?.snapshot)
        assertEquals(ClientStatus.Connected("周五抽签"), client.status.value)
        assertEquals(RoomLimits.PROTOCOL_VERSION, transport.hellos().single().protocolVersion)
    }

    @Test
    fun staleRevisionIsIgnoredAndGapTriggersResync() = runBlocking {
        val (client, transport) = client()
        client.connectAndWelcome(transport, revision = 5L)
        transport.clear()

        // 旧帧：忽略
        transport.emit(TransportEvent.FrameReceived(TransportEvent.SELF_ID, RoomFrame.Json(stateMessage(4L))))
        delay(100)
        assertEquals(5L, client.replica.value?.revision)

        // 连续帧：接受
        transport.emit(TransportEvent.FrameReceived(TransportEvent.SELF_ID, RoomFrame.Json(stateMessage(6L))))
        awaitUntil("接受连续帧") { client.replica.value?.revision == 6L }

        // 跳号（7 → 9）：请求全量重同步，且不直接套用
        transport.emit(TransportEvent.FrameReceived(TransportEvent.SELF_ID, RoomFrame.Json(stateMessage(9L))))
        awaitUntil("请求 resync") { transport.messages().any { it is RoomMessage.Resync } }
        assertEquals(6L, client.replica.value?.revision)
    }

    @Test
    fun ackResolvesRequestAndUpdatesRevision() = runBlocking {
        val (client, transport) = client()
        client.connectAndWelcome(transport)
        transport.clear()

        val pending = async { client.request(RoomAction.Draw(1), timeoutMillis = 3_000) }
        awaitUntil("请求发出") { transport.requests().isNotEmpty() }
        val id = transport.requests().single().id
        transport.emit(TransportEvent.FrameReceived(TransportEvent.SELF_ID, RoomFrame.Json(RoomMessage.Ack(id, 2L))))

        val result = pending.await()
        assertTrue("应成功：$result", result is RequestResult.Ok)
        assertEquals(2L, (result as RequestResult.Ok).revision)
        assertEquals(RoomAction.Draw(1), transport.requests().single().action)
    }

    @Test
    fun requestWithoutAckTimesOut() = runBlocking {
        val (client, transport) = client()
        client.connectAndWelcome(transport)
        transport.clear()

        val result = client.request(RoomAction.Undo, timeoutMillis = 80)

        assertTrue("应超时：$result", result is RequestResult.Failed)
        assertEquals("房主没有响应", (result as RequestResult.Failed).reason)
    }

    @Test
    fun rejectionBecomesAFailedRequest() = runBlocking {
        val (client, transport) = client()
        client.connectAndWelcome(transport)
        transport.clear()

        val pending = async { client.request(RoomAction.Undo, timeoutMillis = 3_000) }
        awaitUntil("请求发出") { transport.requests().isNotEmpty() }
        val id = transport.requests().single().id
        transport.emit(
            TransportEvent.FrameReceived(
                TransportEvent.SELF_ID,
                RoomFrame.Json(RoomMessage.Failure(id, RoomErrors.NOT_ALLOWED, "房主未开放这个操作")),
            ),
        )

        val result = pending.await()
        assertTrue(result is RequestResult.Failed)
        assertEquals("房主未开放这个操作", (result as RequestResult.Failed).reason)
    }

    @Test
    fun versionMismatchClosesTheClient() = runBlocking {
        val (client, transport) = client()
        client.connectAndWelcome(transport)

        transport.emit(
            TransportEvent.FrameReceived(
                TransportEvent.SELF_ID,
                RoomFrame.Json(RoomMessage.Failure(null, RoomErrors.VERSION_MISMATCH, "版本不一致")),
            ),
        )

        awaitUntil("已关闭") { client.status.value is ClientStatus.Closed }
        assertEquals("版本不一致", (client.status.value as ClientStatus.Closed).reason)
    }

    @Test
    fun byeClosesTheClient() = runBlocking {
        val (client, transport) = client()
        client.connectAndWelcome(transport)

        transport.emit(TransportEvent.FrameReceived(TransportEvent.SELF_ID, RoomFrame.Json(RoomMessage.Bye("host_closed"))))

        awaitUntil("已关闭") { client.status.value is ClientStatus.Closed }
        assertEquals("host_closed", (client.status.value as ClientStatus.Closed).reason)
    }

    @Test
    fun imageIsAssembledFromChunks() = runBlocking {
        val (client, transport) = client()
        client.connectAndWelcome(transport)
        transport.clear()
        val bytes = ByteArray(9_000) { (it % 251).toByte() }

        val pending = async { client.fetchImage("img-key") }
        awaitUntil("图片请求发出") { transport.messages().any { it is RoomMessage.ImageRequest } }
        val id = transport.messages().filterIsInstance<RoomMessage.ImageRequest>().single().id
        transport.emit(TransportEvent.FrameReceived(TransportEvent.SELF_ID, RoomFrame.Json(RoomMessage.ImageBegin(id, "img-key", bytes.size))))
        transport.emit(TransportEvent.FrameReceived(TransportEvent.SELF_ID, RoomFrame.Binary(id, 0, bytes.copyOfRange(0, 4096))))
        transport.emit(TransportEvent.FrameReceived(TransportEvent.SELF_ID, RoomFrame.Binary(id, 4096, bytes.copyOfRange(4096, 8192))))
        transport.emit(TransportEvent.FrameReceived(TransportEvent.SELF_ID, RoomFrame.Binary(id, 8192, bytes.copyOfRange(8192, 9000))))
        transport.emit(TransportEvent.FrameReceived(TransportEvent.SELF_ID, RoomFrame.Json(RoomMessage.ImageEnd(id))))

        val image = pending.await()
        assertTrue("图片应为完整内容", image != null && bytes.contentEquals(image))
    }

    @Test
    fun incompleteImageTransferFails() = runBlocking {
        val (client, transport) = client()
        client.connectAndWelcome(transport)
        transport.clear()

        val pending = async { client.fetchImage("img-key") }
        awaitUntil("图片请求发出") { transport.messages().any { it is RoomMessage.ImageRequest } }
        val id = transport.messages().filterIsInstance<RoomMessage.ImageRequest>().single().id
        transport.emit(TransportEvent.FrameReceived(TransportEvent.SELF_ID, RoomFrame.Json(RoomMessage.ImageBegin(id, "img-key", 100))))
        transport.emit(TransportEvent.FrameReceived(TransportEvent.SELF_ID, RoomFrame.Binary(id, 0, ByteArray(10))))
        transport.emit(TransportEvent.FrameReceived(TransportEvent.SELF_ID, RoomFrame.Json(RoomMessage.ImageEnd(id))))

        assertNull(pending.await())
    }

    @Test
    fun heartbeatSendsPingAndLostContactTriggersReconnect() = runBlocking {
        val (client, transport) = client()
        client.connectAndWelcome(transport)
        transport.clear()

        // 房主有消息 → 正常发 ping
        clock = 1_000L
        transport.emit(TransportEvent.FrameReceived(TransportEvent.SELF_ID, RoomFrame.Json(stateMessage(2L))))
        awaitUntil("接受状态") { client.replica.value?.revision == 2L }
        client.heartbeatTick()
        assertTrue("应发出 ping", transport.messages().any { it is RoomMessage.Ping })

        // 很久没收到房主消息 → 进入重连并重发 hello
        clock = 60_000L
        transport.clear()
        client.heartbeatTick()

        awaitUntil("重连中") { client.status.value is ClientStatus.Reconnecting }
        awaitUntil("重发 hello") { transport.hellos().isNotEmpty() }
    }

    @Test
    fun disconnectLeadsToReconnectWithBackoff() = runBlocking {
        val (client, transport) = client(backoffMillis = listOf(10L, 20L))
        client.connectAndWelcome(transport)
        transport.clear()

        transport.emit(TransportEvent.MemberDisconnected(TransportEvent.SELF_ID, "对方已断开"))

        awaitUntil("重连中") { client.status.value is ClientStatus.Reconnecting }
        awaitUntil("重发 hello") { transport.hellos().size >= 1 }

        // 房主再次 welcome 后回到已连接
        transport.emit(TransportEvent.FrameReceived(TransportEvent.SELF_ID, RoomFrame.Json(welcome(revision = 7L))))
        awaitUntil("恢复连接") { client.status.value is ClientStatus.Connected }
        assertEquals(7L, client.replica.value?.revision)
    }
}
