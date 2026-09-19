package com.drawlots.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoolCodecTest {

    @Test
    fun roundTripKeepsEveryField() {
        val lots = listOf(
            Lot(id = "l1", kind = LotKind.TEXT, text = "张三", quantity = 3),
            Lot(
                id = "l2",
                kind = LotKind.IMAGE,
                text = "图片备注",
                imagePath = "/data/user/0/com.drawlots.app/files/lots/a.jpg",
                quantity = 2,
            ),
            Lot(id = "l3", kind = LotKind.IMAGE, text = "", imagePath = null, quantity = 1),
        )
        val results = listOf(
            DrawRecord(
                seq = 1,
                lotId = "l1",
                kind = LotKind.TEXT,
                text = "张三",
                imagePath = null,
                consumedCopy = true,
                mode = DrawMode.WITHOUT_REPLACEMENT,
                atMillis = 1_700_000_000_000L,
                note = "小明抽到",
                drawnBy = "小明",
            ),
            DrawRecord(
                seq = 2,
                lotId = "l2",
                kind = LotKind.IMAGE,
                text = "图片备注",
                imagePath = "/data/user/0/com.drawlots.app/files/lots/a.jpg",
                consumedCopy = false,
                mode = DrawMode.WITH_REPLACEMENT,
                atMillis = 1_700_000_100_000L,
            ),
        )
        val snapshot = PoolSnapshot(lots, results, DrawMode.WITH_REPLACEMENT)

        val decoded = PoolCodec.decode(PoolCodec.encode(snapshot))

        assertEquals(snapshot, decoded)
    }

    @Test
    fun encodedStateCanBeDecodedAgain() {
        val json = PoolCodec.encode(
            PoolSnapshot(
                lots = listOf(Lot(id = "a", kind = LotKind.TEXT, text = "抽 签\n第二行", quantity = 5)),
                results = emptyList(),
                mode = DrawMode.WITHOUT_REPLACEMENT,
            ),
        )

        val decoded = PoolCodec.decode(json)

        assertEquals("抽 签\n第二行", decoded.lots.single().text)
        assertEquals(5, decoded.lots.single().quantity)
        assertEquals(DrawMode.WITHOUT_REPLACEMENT, decoded.mode)
    }

    @Test
    fun decodeOfGarbageFallsBackToAnEmptyPool() {
        val decoded = PoolCodec.decode("{ this is not json")

        assertTrue(decoded.lots.isEmpty())
        assertTrue(decoded.results.isEmpty())
        assertEquals(DrawMode.WITHOUT_REPLACEMENT, decoded.mode)
    }

    @Test
    fun oldStateWithoutNoteFieldLoadsAsEmptyNote() {
        // 1 版存档里没有 note / drawnBy 字段
        val legacy = """
            {"version":1,"mode":"WITHOUT_REPLACEMENT",
             "lots":[{"id":"l1","kind":"TEXT","text":"张三","image":null,"qty":1}],
             "results":[{"seq":1,"lotId":"l1","kind":"TEXT","text":"张三","image":null,
                         "consumed":true,"mode":"WITHOUT_REPLACEMENT","at":1700000000000}]}
        """.trimIndent()

        val decoded = PoolCodec.decode(legacy)

        assertEquals(1, decoded.results.size)
        assertEquals("", decoded.results.single().note)
        assertEquals("", decoded.results.single().drawnBy)
    }

    @Test
    fun jsonObjectFormIsTheSameSchemaAsTheStringForm() {
        val snapshot = PoolSnapshot(
            lots = listOf(Lot(id = "a", kind = LotKind.TEXT, text = "张三", quantity = 2)),
            results = listOf(
                DrawRecord(
                    seq = 1,
                    lotId = "a",
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

        // 协议层直接嵌套对象；两条路径必须产出/读回同一份 schema
        val fromObject = PoolCodec.decodeFromJson(PoolCodec.encodeToJson(snapshot))

        assertEquals(snapshot, fromObject)
        assertEquals(PoolCodec.encode(snapshot), PoolCodec.encodeToJson(snapshot).toString())
    }

    @Test
    fun malformedJsonObjectFallsBackToAnEmptyPool() {
        val decoded = PoolCodec.decodeFromJson(org.json.JSONObject("{\"lots\":\"not-an-array\"}"))

        assertTrue(decoded.lots.isEmpty())
        assertTrue(decoded.results.isEmpty())
    }

    @Test
    fun decodeOfNullFallsBackToAnEmptyPool() {
        val decoded = PoolCodec.decode(null)

        assertTrue(decoded.lots.isEmpty())
        assertTrue(decoded.results.isEmpty())
    }
}
