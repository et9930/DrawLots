package com.drawlots.app.room

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BLE 分片：一帧要切成多个 ATT 写包，接收端用同一个增量解码器还原。
 * 这条测试保证分片边界正确、不丢不重，且拼回去能被 [RoomFrameDecoder] 解出来。
 */
class BleChunkingTest {

    @Test
    fun splitsExactMultiples() {
        val chunks = chunkForBle(ByteArray(40), maxPayload = 20)

        assertEquals(2, chunks.size)
        assertTrue(chunks.all { it.size == 20 })
    }

    @Test
    fun splitsWithRemainder() {
        val chunks = chunkForBle(ByteArray(45), maxPayload = 20)

        assertEquals(listOf(20, 20, 5), chunks.map { it.size })
    }

    @Test
    fun singleChunkAndEmptyInput() {
        assertEquals(listOf(3), chunkForBle(ByteArray(3), maxPayload = 20).map { it.size })
        assertTrue(chunkForBle(ByteArray(0), maxPayload = 20).isEmpty())
        assertTrue("非法分片大小不应产生分片", chunkForBle(ByteArray(10), maxPayload = 0).isEmpty())
    }

    @Test
    fun chunkSizeCanBeAsSmallAsOneByte() {
        val chunks = chunkForBle(ByteArray(4) { it.toByte() }, maxPayload = 1)

        assertEquals(4, chunks.size)
        assertEquals(listOf<Byte>(0, 1, 2, 3), chunks.map { it[0] })
    }

    @Test
    fun chunksReassembleIntoDecodableFrames() {
        // 用真实的帧做一次「切片 → 拼回 → 解码」的完整往返
        val frame = RoomFraming.encode(
            RoomFrame.Json(RoomMessage.Hello(RoomLimits.PROTOCOL_VERSION, "dev-bt", "小明")),
        )
        val decoder = RoomFrameDecoder()
        val frames = mutableListOf<RoomFrame>()

        for (chunk in chunkForBle(frame, maxPayload = 7)) {
            frames += decoder.feed(chunk)
        }

        assertEquals(1, frames.size)
        assertEquals(
            RoomMessage.Hello(RoomLimits.PROTOCOL_VERSION, "dev-bt", "小明"),
            (frames.single() as RoomFrame.Json).message,
        )
    }

    @Test
    fun binaryFrameSurvivesChunkingWithRealisticMtu() {
        val bytes = ByteArray(600) { (it % 251).toByte() }
        val frame = RoomFraming.encode(RoomFrame.Binary(id = "img-1", offset = 0, bytes = bytes))
        val decoder = RoomFrameDecoder()
        val frames = mutableListOf<RoomFrame>()

        // 请求 517 MTU 后单包能装 514 字节
        for (chunk in chunkForBle(frame, maxPayload = 514)) {
            frames += decoder.feed(chunk)
        }

        val binary = frames.single() as RoomFrame.Binary
        assertArrayEquals(bytes, binary.bytes)
        assertEquals("img-1", binary.id)
    }

    @Test
    fun payloadNeverExceedsTheFrameworkAttributeLimit() {
        // MTU 517 → 514 会被框架拒绝（属性值上限 512），真机上崩过房主
        assertEquals(512, blePayloadSize(517))
        assertEquals(509, blePayloadSize(512))
        assertEquals(20, blePayloadSize(23))
        assertEquals("MTU 异常小时也不能小于 20", 20, blePayloadSize(5))
        assertTrue(blePayloadSize(Int.MAX_VALUE) <= BLE_MAX_PAYLOAD_BYTES)
    }

    @Test
    fun chunksOfAFullStateFitOneNotificationAtNegotiatedMtu() {
        // 协商到 517 后，一个 ~300 字节的状态帧应当只需要一个通知（这正是「两边同时显示」的关键）
        val state = ByteArray(300)

        assertEquals(1, chunkForBle(state, blePayloadSize(517)).size)
        assertEquals("未协商时按 20 字节分片", 15, chunkForBle(state, blePayloadSize(23)).size)
    }

    // ---- 分片发送：失败必须重试，不能静默丢弃（丢一片会让对端字节流永久错位） ----

    @Test
    fun retriesAFailedChunkInsteadOfDroppingIt() = runTest {
        val chunks = chunkForBle(ByteArray(30), maxPayload = 10)
        val attemptsPerChunk = mutableMapOf<Int, Int>()
        val delivered = mutableListOf<Byte>()

        val failed = sendChunksInOrder(chunks, attempts = 3, retryDelayMillis = 0) { chunk ->
            val index = delivered.size
            val attempt = (attemptsPerChunk[index] ?: 0) + 1
            attemptsPerChunk[index] = attempt
            // 第二片第一次发失败，重试才成功
            if (index == 1 && attempt == 1) false else { delivered += chunk[0]; true }
        }

        assertEquals(0, failed)
        assertEquals("失败的分片被重试后仍然全部送达", 3, delivered.size)
    }

    @Test
    fun reportsChunksThatNeverMakeItThrough() = runTest {
        val chunks = chunkForBle(ByteArray(30), maxPayload = 10)
        var calls = 0

        val failed = sendChunksInOrder(chunks, attempts = 2, retryDelayMillis = 0) {
            calls++
            false
        }

        assertEquals("全失败要如实上报，才能让上层断开重连而不是装作成功", 3, failed)
        assertEquals("每片都试满 attempts 次", 6, calls)
    }

    @Test
    fun sendsChunksStrictlyInOrder() = runTest {
        val chunks = chunkForBle(ByteArray(25) { it.toByte() }, maxPayload = 10)
        val sent = mutableListOf<List<Byte>>()

        sendChunksInOrder(chunks, retryDelayMillis = 0) { chunk ->
            sent += chunk.toList()
            true
        }

        assertEquals(listOf(listOf<Byte>(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
            (10..19).map { it.toByte() },
            (20..24).map { it.toByte() }), sent)
    }
}
