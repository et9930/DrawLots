package com.drawlots.app.room

import kotlinx.coroutines.flow.Flow

/** 传输层上报的事件。房主与成员都只通过这个流感知连接变化与收到的帧。 */
sealed interface TransportEvent {
    /** 有一条连接建立（房主端是某个成员接入；成员端是自己的连接，id = [SELF_ID]）。 */
    data class MemberConnected(val memberId: String) : TransportEvent

    data class MemberDisconnected(val memberId: String, val reason: String) : TransportEvent

    data class FrameReceived(val memberId: String, val frame: RoomFrame) : TransportEvent

    /** 传输层自身出错（监听失败、连接失败、往不存在的连接发送）。 */
    data class Failed(val reason: String) : TransportEvent

    companion object {
        /** 成员端只有一条连接，用固定 id。 */
        const val SELF_ID = "self"
    }
}

/** 房主监听成功后要写进二维码的接入信息。 */
sealed interface HostEndpoint {
    data class Lan(val port: Int) : HostEndpoint
    data class Bluetooth(val serviceUuid: String) : HostEndpoint
}

/**
 * 传输层接口：只管把 [RoomFrame] 搬过去，不懂业务。
 *
 * 局域网（[LanTransport]）与蓝牙（BluetoothTransport）各自实现；上层 RoomHost / RoomClient 只依赖它，
 * 因此协议与房主逻辑可以在 JVM 上用假传输完整测试。
 */
interface RoomTransport {
    /** 单个二进制分片负载上限（蓝牙按 MTU 会更小）。 */
    val maxPayload: Int

    /**
     * 这条链路是否是慢链路（BLE 每包 20 字节、约 1.5KB/s）。
     *
     * 慢链路上房主会**合并**全量状态广播：状态是幂等快照，中间那些旧快照没有价值，
     * 但把它们全排上去会把链路灌满，让真正的响应（ack）排到几分钟之后——
     * 真机表现就是「抽签提示房主无响应」。局域网默认 false，逐条照发。
     */
    val isSlowLink: Boolean get() = false

    val events: Flow<TransportEvent>

    /** 房主：开始监听并返回接入点信息；失败抛异常。 */
    suspend fun startAsHost(): HostEndpoint

    /** 成员：连接房主；失败会通过 [TransportEvent.Failed] 上报，不抛异常。 */
    suspend fun connectTo(target: RoomTarget)

    /** 发一帧；[to] 为 null 表示发给所有连接。 */
    suspend fun send(frame: RoomFrame, to: String? = null)

    fun stop()
}
