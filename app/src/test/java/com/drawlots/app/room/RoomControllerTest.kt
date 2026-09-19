package com.drawlots.app.room

import com.drawlots.app.domain.DrawMode
import com.drawlots.app.domain.DrawSession
import com.drawlots.app.domain.Lot
import com.drawlots.app.domain.LotKind
import com.drawlots.app.domain.PoolSnapshot
import com.drawlots.app.domain.RoomBackupStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 房间控制器：建房、加入/退出、离线备份与恢复、房间码加入、名称处理。 */
class RoomControllerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private class FakeBackupStore : RoomBackupStore {
        var backup: PoolSnapshot? = null
            private set

        override fun saveOfflineBackup(snapshot: PoolSnapshot) {
            backup = snapshot
        }

        override fun loadOfflineBackup(): PoolSnapshot? = backup

        override fun clearOfflineBackup() {
            backup = null
        }
    }

    private class Fixture(
        val controller: RoomController,
        val session: DrawSession,
        val backups: FakeBackupStore,
        val transports: MutableList<FakeTransport>,
        val names: MutableList<String>,
    )

    private fun fixture(
        lots: List<Lot> = listOf(Lot(id = "mine", kind = LotKind.TEXT, text = "我自己的签", quantity = 2)),
        hostAddress: String? = "192.168.1.5",
        transportFactory: ((RoomTransportKind) -> RoomTransport)? = null,
        myName: String = "小明",
    ): Fixture {
        val session = DrawSession(initialLots = lots, initialMode = DrawMode.WITHOUT_REPLACEMENT)
        val backups = FakeBackupStore()
        val transports = mutableListOf<FakeTransport>()
        val names = mutableListOf<String>()
        val controller = RoomController(
            scope = scope,
            backupStore = backups,
            session = session,
            imageSource = { null },
            transportFactory = transportFactory ?: { _ ->
                FakeTransport().also { transports += it }
            },
            hostAddressProvider = { hostAddress },
            deviceId = "dev-me",
            initialName = myName,
            onNameChanged = { names += it },
            onSessionChanged = {},
            now = { 1_000L },
            beaconSenderFactory = null,
        )
        return Fixture(controller, session, backups, transports, names)
    }

    private suspend fun awaitUntil(what: String, condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(5)
        }
        assertTrue("等待超时：$what", condition())
    }

    private fun welcome(
        roomName: String = "周五抽签",
        hostName: String = "房主",
        snapshot: PoolSnapshot,
        members: List<String> = listOf("房主", "小明"),
        revision: Long = 1L,
    ) = RoomFrame.Json(
        RoomMessage.Welcome(
            roomName = roomName,
            hostName = hostName,
            allowRecover = false,
            allowEdit = false,
            revision = revision,
            members = members,
            state = RoomMessages.encodeState(snapshot),
        ),
    )

    // ---------- 建房 ----------

    @Test
    fun hostingOnLanPublishesInfoInviteAndMembers() = runBlocking {
        val f = fixture()

        val result = f.controller.hostRoom(RoomHostRequest(roomName = "周五抽签"))

        val info = (result as RoomActionResult.Ok).info
        assertNotNull(info)
        assertEquals("周五抽签", info!!.roomName)
        assertEquals("小明", info.hostName)
        assertEquals(6, info.roomCode.length)
        assertTrue(RoomCode.isValid(info.roomCode))
        assertEquals(listOf("小明"), info.members)
        assertEquals(RoomRole.Host, f.controller.role.value)
        assertEquals(RoomStatus.Hosting, f.controller.status.value)

        // 二维码内容里必须带可连接的地址与端口，房间码与界面一致
        val invite = info.invite
        assertNotNull(invite)
        val target = invite!!.target as RoomTarget.Lan
        assertEquals("192.168.1.5", target.host)
        assertEquals(info.roomCode, invite.roomCode)
        assertEquals(invite, RoomCode.decodeInvite(RoomCode.encodeInvite(invite)))
    }

    @Test
    fun hostingFailsWithoutAWifiAddressAndStaysOffline() = runBlocking {
        val f = fixture(hostAddress = null)

        val result = f.controller.hostRoom(RoomHostRequest(roomName = "周五抽签"))

        assertTrue(result is RoomActionResult.Failed)
        assertEquals("请先连接 Wi-Fi 或开热点", (result as RoomActionResult.Failed).reason)
        assertEquals(RoomRole.Offline, f.controller.role.value)
        assertNull(f.controller.roomInfo.value)
    }

    @Test
    fun hostingWithAnUnavailableTransportFailsGracefully() = runBlocking {
        val f = fixture(transportFactory = { throw IllegalStateException("蓝牙房间暂未支持") })

        val result = f.controller.hostRoom(
            RoomHostRequest(roomName = "蓝牙房", kind = RoomTransportKind.Bluetooth),
        )

        assertTrue(result is RoomActionResult.Failed)
        assertTrue((result as RoomActionResult.Failed).reason.contains("蓝牙房间暂未支持"))
        assertEquals(RoomRole.Offline, f.controller.role.value)
    }

    // ---------- 加入与退出 ----------

    @Test
    fun joiningBacksUpOfflineStateThenLeavingRestoresIt() = runBlocking {
        val f = fixture()
        val before = f.session.snapshot()
        val roomPool = PoolSnapshot(
            lots = listOf(Lot(id = "room-lot", kind = LotKind.TEXT, text = "房间里的签", quantity = 5)),
            mode = DrawMode.WITH_REPLACEMENT,
        )

        val joined = f.controller.joinRoom(
            RoomInvite(
                target = RoomTarget.Lan("192.168.1.9", 47001),
                roomCode = "K7M2QX",
                roomName = "周五抽签",
            ),
        )
        assertTrue(joined is RoomActionResult.Ok)
        val transport = f.transports.single()
        awaitUntil("发出 hello") { transport.hellos().isNotEmpty() }

        // 离线状态已备份，且此刻仍是自己的签池（还没收到 welcome）
        assertEquals(before, f.backups.backup)
        assertEquals(RoomRole.Member, f.controller.role.value)

        // 收到 welcome → 副本覆盖本地
        transport.emit(TransportEvent.FrameReceived(TransportEvent.SELF_ID, welcome(snapshot = roomPool)))
        awaitUntil("副本安装") { f.session.lots.map { it.id } == listOf("room-lot") }
        assertEquals(DrawMode.WITH_REPLACEMENT, f.session.mode)
        assertEquals("周五抽签", f.controller.roomInfo.value?.roomName)
        assertEquals("房主", f.controller.roomInfo.value?.hostName)
        assertEquals(listOf("房主", "小明"), f.controller.roomInfo.value?.members)

        // 退出 → 恢复自己的签池并清掉备份
        f.controller.leaveRoom()

        assertEquals(before, f.session.snapshot())
        assertNull(f.backups.backup)
        assertEquals(RoomRole.Offline, f.controller.role.value)
        assertNull(f.controller.roomInfo.value)
    }

    @Test
    fun hostClosingTheRoomMakesTheClientRestoreItsOwnPool() = runBlocking {
        val f = fixture()
        val before = f.session.snapshot()
        f.controller.joinRoom(
            RoomInvite(RoomTarget.Lan("192.168.1.9", 47001), "K7M2QX", "周五抽签"),
        )
        val transport = f.transports.single()
        awaitUntil("发出 hello") { transport.hellos().isNotEmpty() }
        transport.emit(
            TransportEvent.FrameReceived(
                TransportEvent.SELF_ID,
                welcome(snapshot = PoolSnapshot(lots = listOf(Lot(id = "room-lot", kind = LotKind.TEXT, text = "房间", quantity = 1)))),
            ),
        )
        awaitUntil("副本安装") { f.session.lots.map { it.id } == listOf("room-lot") }

        // 房主结束房间
        transport.emit(TransportEvent.FrameReceived(TransportEvent.SELF_ID, RoomFrame.Json(RoomMessage.Bye("host_closed"))))

        awaitUntil("恢复自己的签池") { f.session.snapshot() == before }
        assertEquals(RoomRole.Offline, f.controller.role.value)
        assertNull(f.backups.backup)
    }

    @Test
    fun leavingAsHostKeepsLocalState() = runBlocking {
        val f = fixture()
        f.controller.hostRoom(RoomHostRequest(roomName = "周五抽签"))
        f.session.draw(1)

        f.controller.leaveRoom()

        assertEquals(RoomRole.Offline, f.controller.role.value)
        assertEquals(1, f.session.results.size)
        assertNull("房主不需要备份", f.backups.backup)
    }

    // ---------- 房间码 ----------

    @Test
    fun joinByCodeRejectsAnInvalidCode() = runBlocking {
        val f = fixture()

        val result = f.controller.joinByCode("ABC")

        assertTrue(result is RoomActionResult.Failed)
        assertEquals("房间码不对（应为 6 位）", (result as RoomActionResult.Failed).reason)
        assertEquals(RoomRole.Offline, f.controller.role.value)
    }

    @Test
    fun joinByCodeNeedsAnAddressWhenTheRoomIsNotDiscovered() = runBlocking {
        val f = fixture()

        val result = f.controller.joinByCode("K7M2QX")

        assertTrue(result is RoomActionResult.Failed)
        assertTrue((result as RoomActionResult.Failed).reason.contains("没找到这个房间"))
    }

    @Test
    fun joinByCodeUsesTheProvidedAddress() = runBlocking {
        val f = fixture()

        val result = f.controller.joinByCode("k7m2-qx", host = "10.0.0.7", port = 47011)

        assertTrue(result is RoomActionResult.Ok)
        assertEquals(RoomRole.Member, f.controller.role.value)
        assertEquals("K7M2QX", f.controller.roomInfo.value?.roomCode)
    }

    // ---------- 名称 ----------

    @Test
    fun myNameIsTrimmedTruncatedAndPersisted() {
        val f = fixture(myName = "小明")

        f.controller.setMyName("  这是一个非常长的名字超过十二个字  ")

        assertEquals(12, f.controller.myName.value.length)
        assertEquals("这是一个非常长的名字超过", f.controller.myName.value)
        assertEquals(listOf("这是一个非常长的名字超过"), f.names)

        // 允许清空：删到空时状态必须跟着更新，否则输入框会弹回原值（真机表现为「最后一个字删不掉」）。
        // 署名/进房另有 effectiveName 回落默认名（见 RoomController.effectiveName）。
        f.controller.setMyName("   ")
        assertEquals("清空后状态应为空串", "", f.controller.myName.value)
        assertEquals("空串也要落盘", "", f.names.last())
    }

    @Test
    fun blankNameFallsBackToTheDefaultNameWhenHosting() = runBlocking {
        val f = fixture(myName = "小明")

        // 输入框允许清空（上面的用例），但开场/署名不能是空串
        f.controller.setMyName("")
        val result = f.controller.hostRoom(RoomHostRequest(roomName = "周五抽签"))

        assertTrue(result is RoomActionResult.Ok)
        assertEquals("空名开场时回落默认名", "小明", f.controller.roomInfo.value?.hostName)
    }

    @Test
    fun notHostingTwiceAndNotJoiningWhileInARoom() = runBlocking {
        val f = fixture()
        f.controller.hostRoom(RoomHostRequest(roomName = "周五抽签"))

        val again = f.controller.hostRoom(RoomHostRequest(roomName = "第二个房间"))
        assertTrue(again is RoomActionResult.Failed)

        val join = f.controller.joinRoom(RoomInvite(RoomTarget.Lan("1.2.3.4", 1), "K7M2QX", "别的房间"))
        assertTrue(join is RoomActionResult.Failed)
        assertFalse(f.transports.size > 1)
    }
}
