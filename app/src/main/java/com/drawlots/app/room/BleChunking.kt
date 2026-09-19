package com.drawlots.app.room

import kotlinx.coroutines.delay
import kotlin.math.min

/** BLE 单包上限：ATT 属性值最大 512 字节（Android 框架硬限制，超了会抛 IllegalArgumentException）。 */
internal const val BLE_MAX_PAYLOAD_BYTES = 512

/**
 * 按协商 MTU 算单包负载：`MTU - 3`（ATT 头），但**绝不能超过 512 字节**。
 *
 * 真机教训：MTU 517 → 514 > 512，`notifyCharacteristicChanged` 直接抛
 * `IllegalArgumentException: Notification should not be longer than max length of an attribute value`，
 * 抛在发送协程里没人接 → **房主进程崩溃**。
 */
internal fun blePayloadSize(mtu: Int): Int = (mtu - 3).coerceIn(20, BLE_MAX_PAYLOAD_BYTES)

/**
 * 把一帧拆成 BLE 单包能装下的分片（单包上限 = MTU - 3，且不超过 512）。
 *
 * 抽出来是因为这是 BLE 传输里唯一能在 JVM 上测的部分；GATT 管线只能真机验证。
 */
internal fun chunkForBle(bytes: ByteArray, maxPayload: Int): List<ByteArray> {
    if (maxPayload <= 0) return emptyList()
    if (bytes.isEmpty()) return emptyList()
    val chunks = ArrayList<ByteArray>((bytes.size + maxPayload - 1) / maxPayload)
    var offset = 0
    while (offset < bytes.size) {
        val end = min(offset + maxPayload, bytes.size)
        chunks += bytes.copyOfRange(offset, end)
        offset = end
    }
    return chunks
}

/**
 * 按顺序逐片发送，单片失败就重试；返回最终仍然失败的分片数。
 *
 * 为什么必须重试而不能丢掉：[RoomFraming] 的帧之间没有校验和，对端靠帧头长度增量解析。
 * 中间少一片会让对端的字节流**永久错位**——之后所有帧（包括 ack）都被当成上一帧的残余吞掉，
 * 表现就是「请求发出去了，房主却永远没响应」。BLE 的 notify/write 在协议栈队列满时会返回
 * false，所以这里必须重试并把最终失败如实上报。
 */
internal suspend fun sendChunksInOrder(
    chunks: List<ByteArray>,
    attempts: Int = 3,
    retryDelayMillis: Long = 25,
    send: suspend (ByteArray) -> Boolean,
): Int {
    var failed = 0
    for (chunk in chunks) {
        var sent = false
        for (attempt in 1..attempts.coerceAtLeast(1)) {
            if (send(chunk)) {
                sent = true
                break
            }
            if (attempt < attempts) delay(retryDelayMillis)
        }
        if (!sent) failed++
    }
    return failed
}
