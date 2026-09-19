@file:Suppress("DEPRECATION")

package com.drawlots.app.room

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 蓝牙传输（BLE GATT）。
 *
 * - 房主：开一个 GATT Server，用随机 `serviceUuid` 作为房间标识，并广播这个 UUID（二维码里带的就是它）。
 * - 成员：按 `serviceUuid` 过滤扫描 → 连上 → 请求大 MTU → 打开通知 → 开始收发。
 * - 分帧仍走 [RoomFraming]；因为单次 ATT 写入装不下一帧，发送前按 `MTU-3` 切片，
 *   接收端把字节喂给同一个增量解码器（与局域网一致）。
 *
 * 只在 Android 12+ 提供：更低版本 BLE 扫描要额外申请定位权限，与「零权限」冲突太大，老设备走局域网。
 *
 * 权限说明：蓝牙权限由界面层在进入「蓝牙房间」前按需申请（见 `ui/room/BluetoothPermissions.kt`），
 * 这里只负责调用；万一没授权，下面的入口会把 SecurityException 收成 [TransportEvent.Failed] 报给上层，
 * 不会直接崩。
 */
@SuppressLint("MissingPermission")
class BluetoothTransport(private val context: Context) : RoomTransport {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<TransportEvent>(replay = 0, extraBufferCapacity = 256)
    override val events: Flow<TransportEvent> = _events

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter get() = bluetoothManager?.adapter

    private var decoder = RoomFrameDecoder()

    /**
     * 每个成员一条独立解码流。
     *
     * 共用一个是错的：字节流一旦错位（丢片/半帧），残余字节会一直卡在解码器里，
     * 该成员重连之后新连接的帧仍然会被当成旧帧的残余吞掉，表现为「连上了但房主永远不响应」。
     */
    private val memberDecoders = ConcurrentHashMap<String, RoomFrameDecoder>()

    /** 协商出来的 MTU；默认 23，减去 3 字节 ATT 头就是一包能装的数据。 */
    @Volatile
    private var mtu = DEFAULT_MTU

    override val maxPayload: Int get() = blePayloadSize(mtu)

    /** BLE 每包 20 字节、约 1.5KB/s：房主要合并全量状态广播，别把链路灌满。 */
    override val isSlowLink: Boolean get() = true

    private var serviceUuid: UUID = UUID.randomUUID()
    private var gattServer: BluetoothGattServer? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null

    /** 房主侧：每个成员一条发送队列，避免并发写把帧写坏。 */
    private val outgoing = ConcurrentHashMap<String, Channel<ByteArray>>()

    /**
     * 每成员发送队列：**有界 + 丢最旧**。
     *
     * BLE 有效速率约 1.5KB/s，几百字节的帧要发几百毫秒。如果对端/链路变慢，
     * 无界队列会积压几千个已经过期的全量快照，把真正的响应（ack）挤到几分钟之后。
     * 丢最旧的帧是安全的：状态是全量幂等快照，旧的那份本来就没有价值。
     */
    private fun newOutboundQueue() = Channel<ByteArray>(
        capacity = OUTBOUND_QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val writeWaiters = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    /**
     * 每个成员**唯一**的发送协程。
     *
     * 断开时必须 cancel，不能只 close channel：close 之后旧协程仍会把队列里积压的帧发完，
     * 而重连会立刻启动新的发送协程——两个协程交错往同一个成员发片，对端按长度前缀解析的字节流
     * 就永久错位（真机日志抓到过：客户端收了几百个分片、一帧都拼不出来）。
     */
    private val writeJobs = ConcurrentHashMap<String, Job>()

    /** 只对真正的连接状态变化发事件；平台会重复上报断开，重复事件会让房主重复广播整份状态。 */
    private val connectedMembers = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val clientConnected = AtomicBoolean(false)

    /**
     * 成员侧同一时刻只允许一个未完成的 GATT 写。
     * 心跳 ping 与抽签请求是不同协程发出的，不串行化就会出现「第二个写被协议栈拒绝 → 半帧写出 →
     * 房主字节流永久错位 → 之后所有请求都收不到响应」。
     */
    private val writeMutex = Mutex()

    /** 房主侧每个成员协商到的 MTU（Server 端不会自己拿到，必须从回调里记）。 */
    private val memberMtu = ConcurrentHashMap<String, Int>()

    override suspend fun startAsHost(): HostEndpoint {
        val manager = bluetoothManager ?: error("这台设备不支持蓝牙")
        val device = adapter ?: error("蓝牙未开启")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            error("蓝牙房间需要 Android 12 及以上")
        }
        if (!device.isEnabled) error("请先打开蓝牙")

        serviceUuid = UUID.randomUUID()
        val server = manager.openGattServer(context, object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                val id = device.address ?: "bt-unknown"
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (connectedMembers.add(id)) {
                        outgoing[id] = newOutboundQueue()
                        writeJobs[id] = scope.launch { writeLoop(id) }
                        scope.launch { _events.emit(TransportEvent.MemberConnected(id)) }
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (connectedMembers.remove(id)) {
                        writeJobs.remove(id)?.cancel()
                        outgoing.remove(id)?.close()
                        memberMtu.remove(id)
                        memberDecoders.remove(id)
                        scope.launch { _events.emit(TransportEvent.MemberDisconnected(id, "蓝牙已断开")) }
                    }
                }
            }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                memberMtu[device.address] = mtu
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?,
            ) {
                if (value != null) {
                    val memberDecoder = memberDecoders.getOrPut(device.address) { RoomFrameDecoder() }
                    val frames = memberDecoder.feed(value)
                    for (frame in frames) {
                        scope.launch { _events.emit(TransportEvent.FrameReceived(device.address, frame)) }
                    }
                }
                if (responseNeeded) {
                    runCatching {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                }
            }
        }) ?: error("无法启动蓝牙服务")

        val service = BluetoothGattService(
            serviceUuid,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                NOTIFY_CHARACTERISTIC,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ,
            ),
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                WRITE_CHARACTERISTIC,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            ),
        )
        if (!server.addService(service)) {
            server.close()
            error("注册蓝牙服务失败")
        }
        gattServer = server

        val advertiser = device.bluetoothLeAdvertiser ?: run {
            server.close()
            error("这台设备不支持蓝牙广播")
        }
        val callback = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                scope.launch { _events.emit(TransportEvent.Failed("蓝牙广播启动失败（$errorCode）")) }
            }
        }
        advertiseCallback = callback
        advertiser.startAdvertising(
            AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .build(),
            AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid(serviceUuid))
                .setIncludeDeviceName(false)
                .build(),
            callback,
        )

        return HostEndpoint.Bluetooth(serviceUuid = serviceUuid.toString())
    }

    override suspend fun connectTo(target: RoomTarget) {
        try {
            connectToInternal(target)
        } catch (e: SecurityException) {
            _events.emit(TransportEvent.Failed("缺少蓝牙权限：${e.message ?: "请在系统设置里允许"}"))
        } catch (e: Exception) {
            _events.emit(TransportEvent.Failed("蓝牙连接失败：${e.message ?: e::class.simpleName}"))
        }
    }

    private suspend fun connectToInternal(target: RoomTarget) {
        val bluetooth = target as? RoomTarget.Bluetooth
        if (bluetooth == null) {
            _events.tryEmit(TransportEvent.Failed("蓝牙传输不支持这个目标"))
            return
        }
        val device = adapter
        if (device == null || !device.isEnabled) {
            _events.tryEmit(TransportEvent.Failed("请先打开蓝牙"))
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            _events.tryEmit(TransportEvent.Failed("蓝牙房间需要 Android 12 及以上"))
            return
        }
        val wanted = runCatching { UUID.fromString(bluetooth.serviceUuid) }.getOrNull()
        if (wanted == null) {
            _events.tryEmit(TransportEvent.Failed("房间的蓝牙标识不合法"))
            return
        }
        serviceUuid = wanted
        // 重连要从干净的字节流开始，别把上次残留的半帧带进新连接
        decoder = RoomFrameDecoder()

        val found = CompletableDeferred<BluetoothDevice>()
        val scanner = device.bluetoothLeScanner
        if (scanner == null) {
            _events.tryEmit(TransportEvent.Failed("蓝牙扫描不可用"))
            return
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                found.complete(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                found.completeExceptionally(IllegalStateException("扫描失败（$errorCode）"))
            }
        }
        scanCallback = callback
        scanner.startScan(
            listOf(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(wanted))
                    .build(),
            ),
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build(),
            callback,
        )

        val device2 = withTimeoutOrNull(SCAN_TIMEOUT_MILLIS) { found.await() }
        runCatching { scanner.stopScan(callback) }
        scanCallback = null
        if (device2 == null) {
            _events.emit(TransportEvent.Failed("没有找到蓝牙房间，确认房主已经开房并在附近"))
            return
        }
        connectGatt(device2)
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectGatt(device: BluetoothDevice) {
        val connected = CompletableDeferred<Unit>()
        val gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        runCatching { gatt.requestMtu(PREFERRED_MTU) }
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        val wasConnected = clientConnected.getAndSet(false)
                        if (!connected.isCompleted) {
                            connected.completeExceptionally(IllegalStateException("蓝牙连接已断开"))
                        } else if (wasConnected) {
                            scope.launch {
                                _events.emit(TransportEvent.MemberDisconnected(TransportEvent.SELF_ID, "蓝牙已断开"))
                            }
                        }
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, newMtu: Int, status: Int) {
                mtu = if (status == BluetoothGatt.GATT_SUCCESS) newMtu else DEFAULT_MTU
                runCatching { gatt.discoverServices() }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val characteristic = gatt.getService(serviceUuid)
                    ?.getCharacteristic(NOTIFY_CHARACTERISTIC)
                if (characteristic == null) {
                    connected.completeExceptionally(IllegalStateException("房间里没有找到蓝牙服务"))
                    return
                }
                runCatching {
                    gatt.setCharacteristicNotification(characteristic, true)
                    characteristic.getDescriptor(CCCD_UUID)?.let { descriptor ->
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    }
                }
                if (!connected.isCompleted) connected.complete(Unit)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                val value = characteristic.value ?: return
                val frames = decoder.feed(value)
                for (frame in frames) {
                    scope.launch { _events.emit(TransportEvent.FrameReceived(TransportEvent.SELF_ID, frame)) }
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                writeWaiters.remove(TransportEvent.SELF_ID)?.complete(Unit)
            }
        }, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) {
            _events.emit(TransportEvent.Failed("蓝牙连接失败"))
            return
        }
        bluetoothGatt = gatt
        val ok = withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) { connected.await() }
        if (ok == null) {
            runCatching { gatt.close() }
            bluetoothGatt = null
            _events.emit(TransportEvent.Failed("蓝牙连接超时"))
            return
        }
        clientConnected.set(true)
        _events.emit(TransportEvent.MemberConnected(TransportEvent.SELF_ID))
    }

    override suspend fun send(frame: RoomFrame, to: String?) {
        val bytes = RoomFraming.encode(frame)
        val targets = if (to == null) {
            // 成员端只有一条连接；房主端广播给所有成员
            if (gattServer == null) listOf(TransportEvent.SELF_ID) else outgoing.keys.toList()
        } else {
            listOf(to)
        }
        if (targets.isEmpty()) {
            _events.emit(TransportEvent.Failed("尚未连接"))
            return
        }
        targets.forEach { id ->
            if (gattServer != null) {
                outgoing[id]?.trySend(bytes)
            } else {
                // 成员端：串行分片写入（并发写会被协议栈拒绝并导致半帧）
                writeAsClient(bytes)
            }
        }
    }

    /** 成员侧写一帧：同一连接上串行、逐片重试，任一片最终失败就上报（不能装作成功）。 */
    @SuppressLint("MissingPermission")
    private suspend fun writeAsClient(bytes: ByteArray) {
        writeMutex.withLock {
            val gatt = bluetoothGatt ?: return
            val characteristic = gatt.getService(serviceUuid)
                ?.getCharacteristic(WRITE_CHARACTERISTIC) ?: return
            val failed = sendChunksInOrder(chunkForBle(bytes, maxPayload)) { chunk ->
                val waiter = CompletableDeferred<Unit>()
                writeWaiters[TransportEvent.SELF_ID] = waiter
                if (!writeChunk(gatt, characteristic, chunk)) {
                    writeWaiters.remove(TransportEvent.SELF_ID)
                    false
                } else {
                    val done = withTimeoutOrNull(WRITE_TIMEOUT_MILLIS) { waiter.await() } != null
                    if (!done) writeWaiters.remove(TransportEvent.SELF_ID)
                    done
                }
            }
            if (failed > 0) {
                _events.emit(TransportEvent.Failed("蓝牙写入失败，正在重连"))
            }
        }
    }

    /**
     * 写一片。API 33 起值必须显式传给 writeCharacteristic：
     * 老的 `characteristic.value = ...; writeCharacteristic(characteristic)` 在新系统上语义已变。
     */
    @SuppressLint("MissingPermission")
    private fun writeChunk(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        chunk: ByteArray,
    ): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // 框架可能因长度/状态等直接抛异常；传输层绝不能让它崩掉整个 App
        runCatching {
            gatt.writeCharacteristic(
                characteristic,
                chunk,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ) == BluetoothStatusCodes.SUCCESS
        }.getOrDefault(false)
    } else {
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = chunk
        runCatching { gatt.writeCharacteristic(characteristic) }.getOrDefault(false)
    }

    /** 通知一片（同样的 API 33 显式传值处理）。 */
    @SuppressLint("MissingPermission")
    private fun notifyChunk(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        chunk: ByteArray,
    ): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // 同上：长度超限/状态非法时框架会抛异常，这里收成 false 走重试与断开逻辑，不崩 App
        runCatching {
            server.notifyCharacteristicChanged(device, characteristic, false, chunk) ==
                BluetoothStatusCodes.SUCCESS
        }.getOrDefault(false)
    } else {
        characteristic.value = chunk
        runCatching { server.notifyCharacteristicChanged(device, characteristic, false) }
            .getOrDefault(false)
    }

    /** 房主侧：按成员逐片通知，逐片重试（丢一片会让成员端字节流永久错位）。 */
    @SuppressLint("MissingPermission")
    private suspend fun writeLoop(memberId: String) {
        val server = gattServer ?: return
        val queue = outgoing[memberId] ?: return
        val characteristic = server.getService(serviceUuid)
            ?.getCharacteristic(NOTIFY_CHARACTERISTIC) ?: return
        val device = runCatching { adapter?.getRemoteDevice(memberId) }.getOrNull() ?: return
        var loggedPayload = -1
        for (bytes in queue) {
            // 每帧重新取：MTU 是连上之后才协商出来的，写死成连上那一刻的值会让
            // 一个 300 字节的状态帧被拆成 15 个通知（≈450ms），而协商到 517 后一包就够（≈12ms）。
            val payload = blePayloadSize(memberMtu[memberId] ?: DEFAULT_MTU)
            if (payload != loggedPayload) {
                loggedPayload = payload
            }
            val failed = sendChunksInOrder(
                chunkForBle(bytes, payload),
                attempts = 4,
                // 片间必须留节奏：控制器发送缓冲很小，几毫秒连推几十片会直接丢片
                retryDelayMillis = NOTIFY_INTERVAL_MILLIS,
            ) { chunk ->
                notifyChunk(server, device, characteristic, chunk).also { ok ->
                    if (ok) delay(NOTIFY_INTERVAL_MILLIS)
                }
            }
            if (failed > 0) {
                writeJobs.remove(memberId)?.cancel()
                outgoing.remove(memberId)?.close()
                scope.launch {
                    _events.emit(TransportEvent.MemberDisconnected(memberId, "蓝牙发送失败"))
                }
                return
            }
            // 帧之间再留一点间隔，给协议栈喘息
            delay(NOTIFY_INTERVAL_MILLIS)
        }
    }

    override fun stop() {
        runCatching { advertiseCallback?.let { adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) } }
        advertiseCallback = null
        runCatching { scanCallback?.let { adapter?.bluetoothLeScanner?.stopScan(it) } }
        scanCallback = null
        runCatching { gattServer?.close() }
        gattServer = null
        runCatching { bluetoothGatt?.close() }
        bluetoothGatt = null
        clientConnected.set(false)
        connectedMembers.clear()
        writeJobs.values.forEach { it.cancel() }
        writeJobs.clear()
        outgoing.values.forEach { it.close() }
        outgoing.clear()
        writeWaiters.values.forEach { it.cancel() }
        writeWaiters.clear()
        scope.cancel()
    }

    private companion object {
        const val BLE_TAG = "DrawLotsBle"
        const val OUTBOUND_QUEUE_CAPACITY = 16
        const val DEFAULT_MTU = 23
        const val PREFERRED_MTU = 517
        const val ATT_HEADER_BYTES = 3
        const val SCAN_TIMEOUT_MILLIS = 15_000L
        const val CONNECT_TIMEOUT_MILLIS = 15_000L
        const val WRITE_TIMEOUT_MILLIS = 2_000L
        const val NOTIFY_INTERVAL_MILLIS = 12L

        val NOTIFY_CHARACTERISTIC: UUID = UUID.fromString("0000a001-0000-1000-8000-00805f9b34fb")
        val WRITE_CHARACTERISTIC: UUID = UUID.fromString("0000a002-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

/** 日志用：帧里装的是什么类型的消息（排查时能直接看出是不是状态广播在刷屏）。 */
internal fun RoomFrame.kindName(): String = when (this) {
    is RoomFrame.Json -> message::class.simpleName ?: "Json"
    is RoomFrame.Binary -> "Binary"
}

