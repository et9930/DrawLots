package com.drawlots.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ResultsTextTest {

    private fun record(
        seq: Int,
        title: String,
        note: String = "",
        drawnBy: String = "",
    ) = DrawRecord(
        seq = seq,
        lotId = "lot-$seq",
        kind = LotKind.TEXT,
        text = title,
        imagePath = null,
        consumedCopy = true,
        mode = DrawMode.WITHOUT_REPLACEMENT,
        atMillis = 0L,
        note = note,
        drawnBy = drawnBy,
    )

    @Test
    fun emptyResultsProduceAHint() {
        assertEquals("还没有抽签结果", formatResultsText(emptyList(), DrawMode.WITHOUT_REPLACEMENT))
    }

    @Test
    fun notesAreAppendedInParentheses() {
        val text = formatResultsText(
            listOf(
                record(1, "张三", note = "小明抽到"),
                record(2, "李四"),
            ),
            DrawMode.WITHOUT_REPLACEMENT,
        )

        assertEquals(
            """
            抽签结果（共 2 个 · 不放回）
            1. 张三（小明抽到）
            2. 李四
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun numberingFollowsTheCurrentPositionsNotTheOriginalSequence() {
        // 中间那条被单独放回后，剩下的 seq 是 1 和 3，但导出应该按当前位次重新编号
        val text = formatResultsText(
            listOf(record(1, "A"), record(3, "C")),
            DrawMode.WITH_REPLACEMENT,
        )

        assertEquals(
            """
            抽签结果（共 2 个 · 放回）
            1. A
            2. C
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun drawerNameIsShownAsWhoDrewIt() {
        val text = formatResultsText(
            listOf(record(1, "张三", drawnBy = "小明")),
            DrawMode.WITHOUT_REPLACEMENT,
        )

        assertEquals(
            """
            抽签结果（共 1 个 · 不放回）
            1. 张三（小明抽到）
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun drawerNameAndNoteAreBothExported() {
        val text = formatResultsText(
            listOf(record(1, "张三", note = "我记的备注", drawnBy = "小明")),
            DrawMode.WITHOUT_REPLACEMENT,
        )

        assertEquals(
            """
            抽签结果（共 1 个 · 不放回）
            1. 张三（小明抽到）—— 我记的备注
            """.trimIndent(),
            text,
        )
    }
}
