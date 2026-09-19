package com.drawlots.app.room

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/** 房主每秒广播一次的信标内容。 */
data class LanBeacon(
    val roomName: String,
    val roomCode: String,
    val port: Int,
    val hostName: String,
    val revision: Long,
)

/** 加入房间面板里列出的一条房间。 */
data class DiscoveredRoom(
    val roomName: String,
    val roomCode: String,
    val host: String,
    val port: Int,
    val hostName: String,
    val lastSeenAtMillis: Long,
) {
    val target: RoomTarget get() = RoomTarget.Lan(host = host, port = port)
}

/**
 * 信标编解码（纯函数）。
 *
 * 实测环境里广播可能被路由器/热点拦掉，所以加入方除了被动听信标，还会主动发一个探测包，
 * 房主收到探测后单播回一个信标——这条路径在回环测试里是确定性的。
 */
object LanBeaconCodec {

    const val MAGIC = "DRAWLOTS1"
    const val PROBE = "PROBE"

    fun encode(beacon: LanBeacon, host: String): String = JSONObject()
        .put("magic", MAGIC)
        .put("h", host)
        .put("p", beacon.port)
        .put("c", beacon.roomCode)
        .put("r", beacon.roomName)
        .put("n", beacon.hostName)
        .put("rev", beacon.revision)
        .toString()

    /** 探测包：没有房间信息，只是催房主回一个信标。 */
    fun encodeProbe(): String = JSONObject().put("magic", MAGIC).put("probe", true).toString()

    fun isProbe(text: String): Boolean {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return false
        return json.optString("magic") == MAGIC && json.optBoolean("probe", false)
    }

    /** 解析信标；magic 不对、缺 host/port、房间码非法都返回 null。 */
    fun decode(text: String, now: Long): DiscoveredRoom? {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (json.optString("magic") != MAGIC) return null
        val host = json.optString("h").takeIf { it.isNotBlank() } ?: return null
        val port = json.optInt("p", 0).takeIf { it in 1..65535 } ?: return null
        val code = RoomCode.normalize(json.optString("c")) ?: return null
        return DiscoveredRoom(
            roomName = json.optString("r").ifBlank { "抽签房间" },
            roomCode = code,
            host = host,
            port = port,
            hostName = json.optString("n").ifBlank { "房主" },
            lastSeenAtMillis = now,
        )
    }
}

/** 房间列表维护（纯函数）：按 host:port 去重 + 超时淘汰。 */
object LanRoomList {

    /** 超过这个时间没再收到信标就认为房间没了。 */
    const val TTL_MILLIS = 3_000L

    fun upsert(
        rooms: List<DiscoveredRoom>,
        room: DiscoveredRoom,
        now: Long,
        ttlMillis: Long = TTL_MILLIS,
    ): List<DiscoveredRoom> {
        val kept = prune(rooms, now, ttlMillis).filterNot { it.host == room.host && it.port == room.port }
        return kept + room.copy(lastSeenAtMillis = now)
    }

    fun prune(
        rooms: List<DiscoveredRoom>,
        now: Long,
        ttlMillis: Long = TTL_MILLIS,
    ): List<DiscoveredRoom> = rooms.filter { now - it.lastSeenAtMillis <= ttlMillis }
}

/**
 * 房主侧的信标收发：一个 socket 同时负责
 * 1) 每秒向广播地址发一次信标；2) 收到加入方的探测包时单播回一个信标。
 *
 * 端口传 0 时由系统分配，测试里用 [boundPort] 读回来。
 */
class LanBeaconSender(
    private val scope: CoroutineScope,
    private val discoveryPort: Int = DEFAULT_DISCOVERY_PORT,
    private val broadcastAddress: String = "255.255.255.255",
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var socket: DatagramSocket? = null
    private var jobs = mutableListOf<Job>()

    @Volatile
    var boundPort: Int = -1
        private set

    fun start(beaconProvider: () -> LanBeacon?, hostAddressProvider: () -> String?) {
        val created = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(discoveryPort))
        }
        socket = created
        boundPort = created.localPort

        jobs += scope.launch(Dispatchers.IO) {
            while (isActive) {
                val beacon = beaconProvider()
                val host = hostAddressProvider()
                if (beacon != null && !host.isNullOrBlank()) {
                    announce(beacon, host, broadcastAddress, boundPort)
                }
                delay(BEACON_INTERVAL_MILLIS)
            }
        }
        jobs += scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(2048)
            while (isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    created.receive(packet)
                } catch (e: Exception) {
                    if (!isActive) return@launch else continue
                }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                if (!LanBeaconCodec.isProbe(text)) continue
                val beacon = beaconProvider() ?: continue
                val host = hostAddressProvider() ?: continue
                // 单播回探测方
                announce(beacon, host, packet.address.hostAddress ?: continue, packet.port)
            }
        }
    }

    /** 主动发一次信标；默认发到广播地址，测试里可以指定目标。 */
    fun announce(beacon: LanBeacon, host: String, address: String = broadcastAddress, port: Int = boundPort) {
        val target = socket ?: return
        val bytes = LanBeaconCodec.encode(beacon, host).toByteArray(Charsets.UTF_8)
        runCatching {
            target.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(address), port))
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        const val DEFAULT_DISCOVERY_PORT = 47002
        const val BEACON_INTERVAL_MILLIS = 1_000L
    }
}

/**
 * 加入方侧：探测 + 监听信标，维护房间列表。
 *
 * 优先绑定约定端口（这样也能被动收到周期广播），绑不上就退到临时端口——探测路径仍然可用。
 */
class LanBeaconListener(
    private val scope: CoroutineScope,
    private val discoveryPort: Int = LanBeaconSender.DEFAULT_DISCOVERY_PORT,
    private val broadcastAddress: String = "255.255.255.255",
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var socket: DatagramSocket? = null
    private var jobs = mutableListOf<Job>()

    private val _rooms = MutableStateFlow<List<DiscoveredRoom>>(emptyList())
    val rooms: StateFlow<List<DiscoveredRoom>> = _rooms.asStateFlow()

    @Volatile
    var boundPort: Int = -1
        private set

    fun start() {
        if (socket != null) return
        val created = createSocket()
        socket = created
        boundPort = created.localPort

        // 阻塞式 socket 读写必须离开主线程：调用方传进来的 scope 可能是 Main 调度器
        jobs += scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(2048)
            while (isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    created.receive(packet)
                } catch (e: Exception) {
                    if (!isActive) return@launch else continue
                }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                LanBeaconCodec.decode(text, now())?.let { room ->
                    _rooms.value = LanRoomList.upsert(_rooms.value, room, now())
                }
            }
        }
        jobs += scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(LanRoomList.TTL_MILLIS / 3)
                _rooms.value = LanRoomList.prune(_rooms.value, now())
            }
        }
    }

    /** 主动探测一次（加入面板打开时调用）。默认发到广播地址；测试里可指定单播目标。 */
    fun probe(
        address: String = broadcastAddress,
        port: Int = discoveryPort,
    ) {
        val target = socket ?: return
        val bytes = LanBeaconCodec.encodeProbe().toByteArray(Charsets.UTF_8)
        runCatching {
            target.send(
                DatagramPacket(
                    bytes,
                    bytes.size,
                    InetAddress.getByName(address),
                    port,
                ),
            )
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        runCatching { socket?.close() }
        socket = null
        _rooms.value = emptyList()
    }

    private fun createSocket(): DatagramSocket = try {
        DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(discoveryPort))
        }
    } catch (e: Exception) {
        DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(0))
        }
    }
}
