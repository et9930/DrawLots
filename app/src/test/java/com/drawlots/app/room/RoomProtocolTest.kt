package com.drawlots.app.room

import com.drawlots.app.domain.DrawMode
import com.drawlots.app.domain.DrawRecord
import com.drawlots.app.domain.Lot
import com.drawlots.app.domain.LotKind
import com.drawlots.app.domain.PoolSnapshot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomProtocolTest {

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
                atMillis = 5L,
                drawnBy = "小明",
            ),
        ),
        mode = DrawMode.WITHOUT_REPLACEMENT,
    )

    /** 协议往返：解出来的消息再编码一次，应当与第一次编码完全一致（字段顺序也稳定）。 */
    private fun assertMessageRoundTrip(message: RoomMessage) {
        val text = RoomMessages.encode(message)
        val decoded = RoomMessages.decode(text)
        assertNotNull("解码失败：$text", decoded)
        assertEquals(text, RoomMessages.encode(decoded!!))
    }

    @Test
    fun everyMessageRoundTrips() {
        val stateJson = RoomMessages.encodeState(state)
        assertMessageRoundTrip(RoomMessage.Hello(RoomLimits.PROTOCOL_VERSION, "dev-1", "小明"))
        assertMessageRoundTrip(
            RoomMessage.Welcome(
                roomName = "周五抽签",
                hostName = "房主",
                allowRecover = true,
                allowEdit = false,
                revision = 7L,
                members = listOf("房主", "小明"),
                state = stateJson,
            ),
        )
        assertMessageRoundTrip(
            RoomMessage.RoomState(
                revision = 8L,
                allowRecover = false,
                allowEdit = true,
                members = listOf("房主"),
                state = stateJson,
            ),
        )
        assertMessageRoundTrip(RoomMessage.Request("r1", RoomAction.Draw(2)))
        assertMessageRoundTrip(RoomMessage.Ack("r1", 9L))
        assertMessageRoundTrip(RoomMessage.Failure(null, RoomErrors.NOT_ALLOWED, "房主未开放编辑"))
        assertMessageRoundTrip(RoomMessage.Failure("r2", RoomErrors.BAD_REQUEST, "参数不对"))
        assertMessageRoundTrip(RoomMessage.Resync)
        assertMessageRoundTrip(RoomMessage.ImageRequest("i1", "abc123"))
        assertMessageRoundTrip(RoomMessage.ImageBegin("i1", "abc123", 4096))
        assertMessageRoundTrip(RoomMessage.ImageEnd("i1"))
        assertMessageRoundTrip(RoomMessage.Ping(123L))
        assertMessageRoundTrip(RoomMessage.Pong(123L))
        assertMessageRoundTrip(RoomMessage.Bye("host_closed"))
    }

    @Test
    fun statePayloadUsesTheSameSchemaAsPersistence() {
        val message = RoomMessages.decode(
            RoomMessages.encode(
                RoomMessage.RoomState(1L, false, false, emptyList(), RoomMessages.encodeState(state)),
            ),
        ) as RoomMessage.RoomState

        assertEquals(state, RoomMessages.decodeState(message.state))
        // 对象版与字符串版必须是同一份 schema
        assertEquals(RoomMessages.encodeState(state).toString(), message.state.toString())
    }

    @Test
    fun everyActionRoundTrips() {
        val actions = listOf(
            RoomAction.Draw(3),
            RoomAction.SetNote(2, "备注"),
            RoomAction.Undo,
            RoomAction.PutBack(4),
            RoomAction.ResetPool,
            RoomAction.AddLot(Lot(id = "x", kind = LotKind.TEXT, text = "新签", quantity = 1)),
            RoomAction.UpdateLot(Lot(id = "x", kind = LotKind.TEXT, text = "改过", quantity = 5)),
            RoomAction.RemoveLot("x"),
            RoomAction.SetQuantity("x", 7),
            RoomAction.ClearPool,
        )

        for (action in actions) {
            val json = RoomMessages.encodeAction(action)
            assertEquals("动作往返失败：$action", action, RoomMessages.decodeAction(json))
        }
    }

    @Test
    fun unknownMessageTypeAndBadJsonAreIgnored() {
        assertNull(RoomMessages.decode("{\"type\":\"nope\"}"))
        assertNull(RoomMessages.decode("{\"type\":\"request\"}")) // 缺 action
        assertNull(RoomMessages.decode("这不是 JSON"))
    }

    @Test
    fun unknownActionKindIsRejected() {
        assertNull(RoomMessages.decodeAction(org.json.JSONObject("{\"kind\":\"teleport\"}")))
    }

    // ---------- 分帧 ----------

    @Test
    fun jsonFrameRoundTripsThroughFraming() {
        val decoder = RoomFrameDecoder()
        val frames = decoder.feed(RoomFraming.encode(RoomFrame.Json(RoomMessage.Hello(1, "d", "小明"))))

        assertEquals(1, frames.size)
        assertEquals(RoomMessage.Hello(1, "d", "小明"), (frames.single() as RoomFrame.Json).message)
        assertNull(decoder.error)
    }

    @Test
    fun binaryFrameRoundTrips() {
        val bytes = ByteArray(300) { (it % 251).toByte() }
        val decoder = RoomFrameDecoder()

        val frames = decoder.feed(RoomFraming.encode(RoomFrame.Binary("img-1", offset = 4096, bytes = bytes)))

        assertEquals(1, frames.size)
        val binary = frames.single() as RoomFrame.Binary
        assertEquals("img-1", binary.id)
        assertEquals(4096, binary.offset)
        assertArrayEquals(bytes, binary.bytes)
        assertNull(decoder.error)
    }

    @Test
    fun decoderHandlesPartialAndStickyPackets() {
        val decoder = RoomFrameDecoder()
        val first = RoomFraming.encode(RoomFrame.Json(RoomMessage.Ping(1L)))
        val second = RoomFraming.encode(RoomFrame.Json(RoomMessage.Pong(2L)))

        val frames = ArrayList<RoomFrame>()
        // 半包：一个字节一个字节喂
        for (byte in first) frames += decoder.feed(byteArrayOf(byte))
        // 粘包：两帧一次喂进来
        frames += decoder.feed(first + second)

        assertEquals(3, frames.size)
        assertEquals(RoomMessage.Ping(1L), (frames[0] as RoomFrame.Json).message)
        assertEquals(RoomMessage.Ping(1L), (frames[1] as RoomFrame.Json).message)
        assertEquals(RoomMessage.Pong(2L), (frames[2] as RoomFrame.Json).message)
        assertNull(decoder.error)
    }

    @Test
    fun decoderSkipsUnknownMessageTypesWithoutFailing() {
        val decoder = RoomFrameDecoder()
        val raw = "{\"type\":\"future_thing\"}".toByteArray(Charsets.UTF_8)
        val frame = ByteArray(5 + raw.size)
        RoomFraming.writeInt(frame, 0, 1 + raw.size)
        frame[4] = RoomFraming.TYPE_JSON
        raw.copyInto(frame, 5)

        assertTrue(decoder.feed(frame).isEmpty())
        assertNull(decoder.error)

        // 坏帧之后的正常帧仍然能解出来
        assertEquals(1, decoder.feed(RoomFraming.encode(RoomFrame.Json(RoomMessage.Ping(1L)))).size)
    }

    @Test
    fun decoderFlagsOversizedFrame() {
        val decoder = RoomFrameDecoder(maxFrameBytes = 64)
        val frame = RoomFraming.encode(RoomFrame.Json(RoomMessage.Ping(1L)))
        RoomFraming.writeInt(frame, 0, 100_000)

        assertTrue(decoder.feed(frame).isEmpty())
        assertNotNull(decoder.error)
    }

    @Test
    fun decoderFlagsZeroLengthFrame() {
        val decoder = RoomFrameDecoder()
        val frame = RoomFraming.encode(RoomFrame.Json(RoomMessage.Ping(1L)))
        RoomFraming.writeInt(frame, 0, 0)

        assertTrue(decoder.feed(frame).isEmpty())
        assertNotNull(decoder.error)
    }

    @Test
    fun afterErrorTheDecoderStaysQuiet() {
        val decoder = RoomFrameDecoder(maxFrameBytes = 64)
        val bad = RoomFraming.encode(RoomFrame.Json(RoomMessage.Ping(1L)))
        RoomFraming.writeInt(bad, 0, 100_000)
        decoder.feed(bad)

        assertTrue(decoder.feed(RoomFraming.encode(RoomFrame.Json(RoomMessage.Ping(1L)))).isEmpty())
    }
}
