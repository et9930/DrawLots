package com.drawlots.app.room

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanDiscoveryTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    private fun beacon(
        roomName: String = "周五抽签",
        roomCode: String = "K7M2QX",
        port: Int = 47001,
        hostName: String = "房主",
        revision: Long = 3L,
    ) = LanBeacon(roomName = roomName, roomCode = roomCode, port = port, hostName = hostName, revision = revision)

    // ---------- 纯逻辑 ----------

    @Test
    fun beaconRoundTrips() {
        val room = LanBeaconCodec.decode(LanBeaconCodec.encode(beacon(), host = "192.168.1.5"), now = 100L)

        assertEquals("周五抽签", room?.roomName)
        assertEquals("K7M2QX", room?.roomCode)
        assertEquals("192.168.1.5", room?.host)
        assertEquals(47001, room?.port)
        assertEquals("房主", room?.hostName)
        assertEquals(100L, room?.lastSeenAtMillis)
    }

    @Test
    fun beaconDecodeRejectsGarbage() {
        assertNull(LanBeaconCodec.decode("not json", 0L))
        assertNull("magic 不对", LanBeaconCodec.decode("{\"magic\":\"OTHER\"}", 0L))
        assertNull("缺 host", LanBeaconCodec.decode("{\"magic\":\"DRAWLOTS1\",\"p\":47001,\"c\":\"K7M2QX\"}", 0L))
        assertNull("端口非法", LanBeaconCodec.decode("{\"magic\":\"DRAWLOTS1\",\"h\":\"1.2.3.4\",\"p\":0,\"c\":\"K7M2QX\"}", 0L))
        assertNull("房间码非法", LanBeaconCodec.decode("{\"magic\":\"DRAWLOTS1\",\"h\":\"1.2.3.4\",\"p\":47001,\"c\":\"XX\"}", 0L))
    }

    @Test
    fun beaconDecodeNormalizesCodeAndDefaultsName() {
        val room = LanBeaconCodec.decode(
            "{\"magic\":\"DRAWLOTS1\",\"h\":\"1.2.3.4\",\"p\":47001,\"c\":\"k7m2-qx\"}",
            now = 0L,
        )

        assertEquals("K7M2QX", room?.roomCode)
        assertEquals("抽签房间", room?.roomName)
        assertEquals("房主", room?.hostName)
    }

    @Test
    fun probePacketsAreRecognised() {
        assertTrue(LanBeaconCodec.isProbe(LanBeaconCodec.encodeProbe()))
        assertFalse(LanBeaconCodec.isProbe(LanBeaconCodec.encode(beacon(), "1.2.3.4")))
        assertFalse(LanBeaconCodec.isProbe("garbage"))
        assertNull("探测包不是信标", LanBeaconCodec.decode(LanBeaconCodec.encodeProbe(), 0L))
    }

    @Test
    fun roomListUpsertsByHostAndPortAndPrunesExpiredRooms() {
        val first = LanBeaconCodec.decode(LanBeaconCodec.encode(beacon(), "1.2.3.4"), now = 1_000L)!!
        val updated = first.copy(roomName = "改名了")

        var rooms = LanRoomList.upsert(emptyList(), first, now = 1_000L)
        assertEquals(1, rooms.size)

        // 同一个 host:port 再出现 → 覆盖而不是新增
        rooms = LanRoomList.upsert(rooms, updated, now = 2_000L)
        assertEquals(1, rooms.size)
        assertEquals("改名了", rooms.single().roomName)
        assertEquals(2_000L, rooms.single().lastSeenAtMillis)

        // 另一个房间 → 列表两条
        val other = first.copy(host = "1.2.3.5", port = 47002)
        rooms = LanRoomList.upsert(rooms, other, now = 5_000L)
        assertEquals(2, rooms.size)

        // 再过 1ms：第一条（lastSeen=2000）超过 TTL 被淘汰，第二条（lastSeen=5000）还在
        rooms = LanRoomList.prune(rooms, now = 5_001L)
        assertEquals(1, rooms.size)
        assertEquals("1.2.3.5", rooms.single().host)
    }

    // ---------- 回环（真 socket，单播） ----------

    @Test
    fun listenerDiscoversHostThroughProbeAndAnnounce() = runBlocking {
        val sender = LanBeaconSender(
            scope = scope,
            discoveryPort = 0,
            broadcastAddress = "127.0.0.1",
        )
        val listener = LanBeaconListener(
            scope = scope,
            discoveryPort = 0,
            broadcastAddress = "127.0.0.1",
        )
        try {
            sender.start(
                beaconProvider = { beacon(port = sender.boundPort) },
                hostAddressProvider = { "127.0.0.1" },
            )
            listener.start()

            // 1) 加入方主动探测 → 房主单播回信标
            listener.probe(address = "127.0.0.1", port = sender.boundPort)
            val discovered = withTimeout(10_000) {
                while (listener.rooms.value.isEmpty()) delay(50)
                listener.rooms.value.single()
            }
            assertEquals("周五抽签", discovered.roomName)
            assertEquals("K7M2QX", discovered.roomCode)
            assertEquals("127.0.0.1", discovered.host)
            assertEquals(sender.boundPort, discovered.port)

            // 2) 房主主动发一次信标 → 加入方也能收到
            listener.stop()
            listener.start()
            sender.announce(beacon(), host = "127.0.0.1", address = "127.0.0.1", port = listener.boundPort)
            val announced = withTimeout(10_000) {
                while (listener.rooms.value.isEmpty()) delay(50)
                listener.rooms.value.single()
            }
            assertEquals("周五抽签", announced.roomName)
        } finally {
            sender.stop()
            listener.stop()
        }
    }
}
