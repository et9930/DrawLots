package com.drawlots.app.room

import com.drawlots.app.domain.DrawMode
import com.drawlots.app.domain.DrawSession
import com.drawlots.app.domain.Lot
import com.drawlots.app.domain.LotKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 房主逻辑测试：权限矩阵、署名、revision、成员超时、图片分片。
 * 用假传输 + 假时钟驱动，不依赖网络与真实时间。
 */
class RoomHostTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var clock = 0L

    @After
    fun tearDown() = scope.cancel()

    private class Fixture(
        val host: RoomHost,
        val transport: FakeTransport,
        val session: DrawSession,
    )

    private fun fixture(
        lots: List<Lot> = listOf(Lot(id = "l1", kind = LotKind.TEXT, text = "张三", quantity = 3)),
        allowRecover: Boolean = false,
        allowEdit: Boolean = false,
        imageBytes: ByteArray? = null,
        hostName: String = "房主",
    ): Fixture {
        val session = DrawSession(initialLots = lots, initialMode = DrawMode.WITHOUT_REPLACEMENT)
        val transport = FakeTransport()
        val host = RoomHost(
            session = session,
            transport = transport,
            config = RoomConfig(
                roomName = "周五抽签",
                hostName = hostName,
                allowRecover = allowRecover,
                allowEdit = allowEdit,
            ),
            imageSource = { key -> if (key == "img-key") imageBytes else null },
            scope = scope,
            now = { clock },
        )
        return Fixture(host, transport, session)
    }

    private fun json(message: RoomMessage): RoomFrame = RoomFrame.Json(message)

    private fun hello(name: String = "小明", version: Int = RoomLimits.PROTOCOL_VERSION) =
        RoomMessage.Hello(protocolVersion = version, deviceId = "dev-$name", name = name)

    private suspend fun awaitUntil(what: String, condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(5)
        }
        assertTrue("等待超时：$what", condition())
    }

    private suspend fun Fixture.join(name: String = "小明", memberId: String = "conn-1") {
        transport.emit(TransportEvent.MemberConnected(memberId))
        transport.emit(TransportEvent.FrameReceived(memberId, json(hello(name))))
        awaitUntil("welcome") { transport.welcomes().isNotEmpty() }
    }

    // ---------- 握手 ----------

    @Test
    fun helloGetsWelcomeWithCurrentState() = runBlocking {
        val f = fixture()
        f.host.start()

        f.join()

        val welcome = f.transport.welcomes().single()
        assertEquals("周五抽签", welcome.roomName)
        assertEquals("房主", welcome.hostName)
        assertEquals(false, welcome.allowRecover)
        assertEquals(false, welcome.allowEdit)
        assertEquals(0L, welcome.revision)
        assertEquals(listOf("房主", "小明"), welcome.members)
        assertEquals(f.session.snapshot(), RoomMessages.decodeState(welcome.state))
    }

    @Test
    fun protocolVersionMismatchIsRejected() = runBlocking {
        val f = fixture()
        f.host.start()
        f.transport.emit(TransportEvent.MemberConnected("conn-1"))
        f.transport.emit(TransportEvent.FrameReceived("conn-1", json(hello(version = 99))))

        awaitUntil("版本错误") { f.transport.failures().isNotEmpty() }
        assertEquals(RoomErrors.VERSION_MISMATCH, f.transport.failures().single().code)
        assertTrue("不应发 welcome", f.transport.welcomes().isEmpty())

        // 没通过握手的成员发请求会被拒
        f.transport.clear()
        f.transport.emit(TransportEvent.FrameReceived("conn-1", json(RoomMessage.Request("r1", RoomAction.Draw(1)))))
        awaitUntil("拒绝请求") { f.transport.failures().isNotEmpty() }
        assertEquals(RoomErrors.BAD_REQUEST, f.transport.failures().single().code)
    }

    // ---------- 抽签与署名 ----------

    @Test
    fun twoMembersDrawingAtTheSameTimeAreSerializedWithoutConflicts() = runBlocking {
        // 只有两支可抽：两人同时抽正好抽完。验证并发下不超抽、署名不串、revision 连续、两人都拿到 ack。
        val f = fixture(lots = listOf(Lot(id = "l1", kind = LotKind.TEXT, text = "张三", quantity = 2)))
        f.host.start()
        f.join("小明", memberId = "conn-1")
        f.join("小红", memberId = "conn-2")
        f.transport.clear()

        // 两个请求几乎同时到达（同一个事件流里连着投递）
        f.transport.emit(TransportEvent.FrameReceived("conn-1", json(RoomMessage.Request("r1", RoomAction.Draw(1)))))
        f.transport.emit(TransportEvent.FrameReceived("conn-2", json(RoomMessage.Request("r2", RoomAction.Draw(1)))))

        awaitUntil("两次抽签都被应用") { f.session.results.size == 2 }
        assertEquals("签池正好抽完", 0, f.session.totalRemaining)
        assertEquals("两人各署名一次", setOf("小明", "小红"), f.session.results.map { it.drawnBy }.toSet())
        assertEquals("两次应用后 revision 必须是 2（没有丢更新）", 2L, f.host.revision)
        assertEquals("两个请求都要有 ack", setOf("r1", "r2"), f.transport.acks().map { it.id }.toSet())

        val states = f.transport.sent.mapNotNull {
            (it.frame as? RoomFrame.Json)?.message as? RoomMessage.RoomState
        }
        assertTrue("抽签要广播全量状态", states.size >= 2)
        assertEquals("最后一次广播应含两条结果", 2, RoomMessages.decodeState(states.last().state).results.size)
    }

    @Test
    fun theSecondDrawerGetsAnErrorWhenThePoolRanOutMidFlight() = runBlocking {
        // 只有一支：第二个人轮到时已经没签了，应收到明确错误，而不是「成功但没结果」让界面干等
        val f = fixture(lots = listOf(Lot(id = "l1", kind = LotKind.TEXT, text = "张三", quantity = 1)))
        f.host.start()
        f.join("小明", memberId = "conn-1")
        f.join("小红", memberId = "conn-2")
        f.transport.clear()

        f.transport.emit(TransportEvent.FrameReceived("conn-1", json(RoomMessage.Request("r1", RoomAction.Draw(1)))))
        awaitUntil("第一支被抽走") { f.session.results.size == 1 }
        f.transport.emit(TransportEvent.FrameReceived("conn-2", json(RoomMessage.Request("r2", RoomAction.Draw(1)))))

        awaitUntil("第二个人收到错误") { f.transport.failures().any { it.id == "r2" } }
        assertEquals(RoomErrors.BAD_REQUEST, f.transport.failures().single { it.id == "r2" }.code)
        assertEquals("签池空了就不再新增结果", 1, f.session.results.size)
    }

    @Test
    fun memberDrawIsAttributedToThatMemberAndBroadcast() = runBlocking {
        val f = fixture()
        f.host.start()
        f.join("小明")
        f.transport.clear()

        f.transport.emit(TransportEvent.FrameReceived("conn-1", json(RoomMessage.Request("r1", RoomAction.Draw(1)))))

        awaitUntil("抽签被应用") { f.session.results.size == 1 }
        val record = f.session.results.single()
        assertEquals("小明", record.drawnBy)
        assertEquals(1L, f.host.revision)

        // 请求方收到 ack，所有人收到全量状态
        val ack = f.transport.acks().single()
        assertEquals("r1", ack.id)
        assertEquals(1L, ack.revision)
        val stateFrame = f.transport.sent.last { (it.frame as? RoomFrame.Json)?.message is RoomMessage.RoomState }
        assertNull("全量广播应发给所有人", stateFrame.to)
        val broadcast = (stateFrame.frame as RoomFrame.Json).message as RoomMessage.RoomState
        assertEquals(1L, broadcast.revision)
        assertEquals("小明", RoomMessages.decodeState(broadcast.state).results.single().drawnBy)
    }

    @Test
    fun hostOwnDrawIsAttributedToTheHost() = runBlocking {
        val f = fixture(hostName = "主持人")
        f.host.start()
        f.join("小明")
        f.transport.clear()

        f.host.applyLocal(RoomAction.Draw(2))

        assertEquals(2, f.session.results.size)
        assertEquals(listOf("主持人", "主持人"), f.session.results.map { it.drawnBy })
        assertEquals(1L, f.host.revision)
        assertEquals(1L, f.transport.states().last().revision)
    }

    @Test
    fun memberNoteIsAllowedAndApplied() = runBlocking {
        val f = fixture()
        f.host.start()
        f.join("小明")
        f.transport.clear()
        f.transport.emit(TransportEvent.FrameReceived("conn-1", json(RoomMessage.Request("r1", RoomAction.Draw(1)))))
        awaitUntil("抽签") { f.session.results.size == 1 }
        val seq = f.session.results.single().seq

        f.transport.emit(
            TransportEvent.FrameReceived("conn-1", json(RoomMessage.Request("r2", RoomAction.SetNote(seq, "我抽到的")))),
        )

        awaitUntil("备注被写入") { f.session.results.single().note == "我抽到的" }
        assertEquals("小明", f.session.results.single().drawnBy)
    }

    // ---------- 权限矩阵 ----------

    @Test
    fun undoAndPutBackRequireAllowRecover() = runBlocking {
        val denied = fixture(allowRecover = false)
        denied.host.start()
        denied.join("小明")
        denied.transport.emit(TransportEvent.FrameReceived("conn-1", json(RoomMessage.Request("r1", RoomAction.Draw(1)))))
        awaitUntil("抽签") { denied.session.results.size == 1 }
        denied.transport.clear()

        denied.transport.emit(TransportEvent.FrameReceived("conn-1", json(RoomMessage.Request("r2", RoomAction.Undo))))
        awaitUntil("拒绝撤销") { denied.transport.failures().isNotEmpty() }
        assertEquals(RoomErrors.NOT_ALLOWED, denied.transport.failures().single().code)
        assertEquals(1, denied.session.results.size)
        assertEquals(2, denied.session.totalRemaining)

        val allowed = fixture(allowRecover = true)
        allowed.host.start()
        allowed.join("小明")
        allowed.transport.emit(TransportEvent.FrameReceived("conn-1", json(RoomMessage.Request("r1", RoomAction.Draw(1)))))
        awaitUntil("抽签") { allowed.session.results.size == 1 }
        allowed.transport.clear()
        allowed.transport.emit(TransportEvent.FrameReceived("conn-1", json(RoomMessage.Request("r2", RoomAction.Undo))))

        awaitUntil("撤销生效") { allowed.session.results.isEmpty() }
        assertEquals(3, allowed.session.totalRemaining)
        assertTrue(allowed.transport.acks().any { it.id == "r2" })
    }

    @Test
    fun poolEditingRequiresAllowEdit() = runBlocking {
        val denied = fixture(allowEdit = false)
        denied.host.start()
        denied.join("小明")
        denied.transport.clear()
        val newLot = Lot(id = "new-1", kind = LotKind.TEXT, text = "新签", quantity = 1)

        denied.transport.emit(
            TransportEvent.FrameReceived("conn-1", json(RoomMessage.Request("r1", RoomAction.AddLot(newLot)))),
        )

        awaitUntil("拒绝加签") { denied.transport.failures().isNotEmpty() }
        assertEquals(RoomErrors.NOT_ALLOWED, denied.transport.failures().single().code)
        assertEquals(1, denied.session.lots.size)

        val allowed = fixture(allowEdit = true)
        allowed.host.start()
        allowed.join("小明")
        allowed.transport.clear()
        allowed.transport.emit(
            TransportEvent.FrameReceived("conn-1", json(RoomMessage.Request("r1", RoomAction.AddLot(newLot)))),
        )

        awaitUntil("加签生效") { allowed.session.lots.size == 2 }
        assertEquals(4, allowed.session.totalQuantity)
    }

    @Test
    fun memberCannotAddImageLotEvenWhenEditingIsAllowed() = runBlocking {
        val f = fixture(allowEdit = true)
        f.host.start()
        f.join("小明")
        f.transport.clear()
        val imageLot = Lot(id = "img-1", kind = LotKind.IMAGE, imagePath = "/data/x.jpg", quantity = 1)

        f.transport.emit(
            TransportEvent.FrameReceived("conn-1", json(RoomMessage.Request("r1", RoomAction.AddLot(imageLot)))),
        )

        awaitUntil("拒绝图片签") { f.transport.failures().isNotEmpty() }
        assertEquals(RoomErrors.NOT_ALLOWED, f.transport.failures().single().code)
        assertEquals(1, f.session.lots.size)
    }

    @Test
    fun resetPoolIsAlwaysRejectedForMembers() = runBlocking {
        val f = fixture(allowRecover = true, allowEdit = true)
        f.host.start()
        f.join("小明")
        f.transport.emit(TransportEvent.FrameReceived("conn-1", json(RoomMessage.Request("r1", RoomAction.Draw(1)))))
        awaitUntil("抽签") { f.session.results.size == 1 }
        f.transport.clear()

        f.transport.emit(TransportEvent.FrameReceived("conn-1", json(RoomMessage.Request("r2", RoomAction.ResetPool))))

        awaitUntil("拒绝重置") { f.transport.failures().isNotEmpty() }
        assertEquals(RoomErrors.NOT_ALLOWED, f.transport.failures().single().code)
        assertEquals(1, f.session.results.size)

        // 房主自己的重置是允许的
        f.host.applyLocal(RoomAction.ResetPool)
        assertTrue(f.session.results.isEmpty())
    }

    @Test
    fun modeChangeIsRejectedForMembersButAllowedForTheHost() = runBlocking {
        val f = fixture(allowRecover = true, allowEdit = true)
        f.host.start()
        f.join("小明")
        f.transport.clear()
        assertEquals(DrawMode.WITHOUT_REPLACEMENT, f.session.mode)

        f.transport.emit(
            TransportEvent.FrameReceived(
                "conn-1",
                json(RoomMessage.Request("r1", RoomAction.SetMode(DrawMode.WITH_REPLACEMENT))),
            ),
        )

        awaitUntil("拒绝切模式") { f.transport.failures().isNotEmpty() }
        assertEquals(RoomErrors.NOT_ALLOWED, f.transport.failures().single().code)
        assertEquals(DrawMode.WITHOUT_REPLACEMENT, f.session.mode)

        f.host.applyLocal(RoomAction.SetMode(DrawMode.WITH_REPLACEMENT))
        assertEquals(DrawMode.WITH_REPLACEMENT, f.session.mode)
        assertEquals(1L, f.host.revision)
    }

    // ---------- 成员与心跳 ----------

    @Test
    fun idleMembersAreDroppedAfterTimeoutAndPingRefreshesThem() = runBlocking {
        val f = fixture()
        f.host.start()
        f.join("小明")
        assertEquals(listOf("房主", "小明"), f.host.members.value)

        // 10 秒时还有心跳 → 不回被清
        clock = 10_000L
        f.transport.emit(TransportEvent.FrameReceived("conn-1", json(RoomMessage.Ping(10_000L))))
        awaitUntil("pong") { f.transport.pongs().isNotEmpty() }
        clock = 16_000L
        f.host.pruneIdleMembers()
        assertEquals(listOf("房主", "小明"), f.host.members.value)

        // 很久没心跳 → 被踢出，并广播新成员表
        clock = 40_000L
        f.transport.clear()
        f.host.pruneIdleMembers()
        assertEquals(listOf("房主"), f.host.members.value)
        assertEquals(listOf("房主"), f.transport.states().last().members)
    }

    @Test
    fun memberDisconnectRemovesItFromTheList() = runBlocking {
        val f = fixture()
        f.host.start()
        f.join("小明")

        f.transport.clear()
        f.transport.emit(TransportEvent.MemberDisconnected("conn-1", "对方已断开"))

        awaitUntil("成员移除") { f.host.members.value == listOf("房主") }
        assertEquals(listOf("房主"), f.transport.states().last().members)
    }

    // ---------- 图片 ----------

    @Test
    fun imageRequestIsStreamedInChunks() = runBlocking {
        val bytes = ByteArray(9_000) { (it % 251).toByte() }
        val f = fixture(imageBytes = bytes)
        f.host.start()
        f.join("小明")
        f.transport.clear()

        f.transport.emit(
            TransportEvent.FrameReceived("conn-1", json(RoomMessage.ImageRequest("i1", "img-key"))),
        )

        awaitUntil("图片发完") { f.transport.messages().any { it is RoomMessage.ImageEnd } }
        val begin = f.transport.messages().filterIsInstance<RoomMessage.ImageBegin>().single()
        assertEquals(9_000, begin.size)
        assertEquals("img-key", begin.imageKey)

        val chunks = f.transport.binaryFrames("i1")
        assertEquals(listOf(0, 4096, 8192), chunks.map { it.offset })
        assertEquals(listOf(4096, 4096, 808), chunks.map { it.bytes.size })
        val reassembled = ByteArray(9_000)
        chunks.forEach { it.bytes.copyInto(reassembled, it.offset) }
        assertTrue(bytes.contentEquals(reassembled))
    }

    @Test
    fun imageRequestForUnknownKeyReportsNotFound() = runBlocking {
        val f = fixture(imageBytes = null)
        f.host.start()
        f.join("小明")
        f.transport.clear()

        f.transport.emit(TransportEvent.FrameReceived("conn-1", json(RoomMessage.ImageRequest("i1", "img-key"))))

        awaitUntil("not found") { f.transport.failures().isNotEmpty() }
        assertEquals(RoomErrors.NOT_FOUND, f.transport.failures().single().code)
    }

    // ---------- resync ----------

    @Test
    fun resyncSendsCurrentStateToThatMemberOnly() = runBlocking {
        val f = fixture()
        f.host.start()
        f.join("小明")
        f.join("小红", memberId = "conn-2")
        // 两次 join 各触发一次成员广播，等它们落地再清空记录
        awaitUntil("成员广播落地") { f.transport.states().size >= 2 }
        f.transport.clear()

        f.transport.emit(TransportEvent.FrameReceived("conn-2", json(RoomMessage.Resync)))

        awaitUntil("回全量状态") { f.transport.states().isNotEmpty() }
        assertEquals(1, f.transport.sent.size)
        assertEquals("conn-2", f.transport.sent.single().to)
        assertTrue((f.transport.sent.single().frame as RoomFrame.Json).message is RoomMessage.RoomState)
    }
}
