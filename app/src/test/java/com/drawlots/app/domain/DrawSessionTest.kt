package com.drawlots.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DrawSessionTest {

    private fun lots(vararg pairs: Pair<String, Int>): List<Lot> =
        pairs.map { (text, quantity) ->
            Lot(id = text, kind = LotKind.TEXT, text = text, quantity = quantity)
        }

    private fun session(
        vararg pairs: Pair<String, Int>,
        mode: DrawMode = DrawMode.WITHOUT_REPLACEMENT,
        seed: Int = 42,
    ): DrawSession = DrawSession(lots(*pairs), emptyList(), mode, Random(seed))

    // ---------- 不放回 ----------

    @Test
    fun withoutReplacement_drawingWholePoolYieldsEveryCopyExactlyOnce() {
        val session = session("A" to 3, "B" to 2, "C" to 1)

        val drawn = session.draw(100) // 请求数超过签池总数

        assertEquals(6, drawn.size)
        assertEquals(0, session.totalRemaining)
        assertEquals(3, drawn.count { it.text == "A" })
        assertEquals(2, drawn.count { it.text == "B" })
        assertEquals(1, drawn.count { it.text == "C" })
        assertFalse(session.canDraw())
        assertTrue(session.draw(1).isEmpty())
    }

    @Test
    fun withoutReplacement_exhaustedLotIsNeverDrawnAgain() {
        val session = session("A" to 1, "B" to 5)

        val drawn = session.draw(6)

        assertEquals(6, drawn.size)
        assertEquals(1, drawn.count { it.text == "A" })
        assertEquals(0, session.totalRemaining)
    }

    @Test
    fun withoutReplacement_firstDrawProbabilityFollowsQuantity() {
        val random = Random(7)
        val runs = 8000
        var pickedA = 0

        repeat(runs) {
            val session = DrawSession(
                lots("A" to 3, "B" to 1),
                emptyList(),
                DrawMode.WITHOUT_REPLACEMENT,
                random,
            )
            if (session.drawOne()?.text == "A") pickedA++
        }

        val share = pickedA.toDouble() / runs
        assertTrue("A 3:1 应该约占 75%，实际 $share", share in 0.72..0.78)
    }

    // ---------- 放回 ----------

    @Test
    fun withReplacement_neverConsumesThePool() {
        val session = session("A" to 2, "B" to 1, mode = DrawMode.WITH_REPLACEMENT)

        val drawn = session.draw(50)

        assertEquals(50, drawn.size)
        assertEquals(3, session.totalRemaining)
        assertEquals(0, session.drawnCountOf("A"))
        assertTrue(session.canDraw())
    }

    @Test
    fun withReplacement_drawProbabilityFollowsQuantity() {
        val session = session("A" to 3, "B" to 1, mode = DrawMode.WITH_REPLACEMENT, seed = 3)

        val drawn = session.draw(8000)

        val share = drawn.count { it.text == "A" }.toDouble() / drawn.size
        assertTrue("A 3:1 应该约占 75%，实际 $share", share in 0.73..0.77)
    }

    // ---------- 顺序 / 结果 ----------

    @Test
    fun resultsKeepDrawOrderAndSequence() {
        val session = session("A" to 2, "B" to 2)

        val drawn = session.draw(4)

        assertEquals(listOf(1, 2, 3, 4), drawn.map { it.seq })
        assertEquals(drawn, session.results)
        assertEquals(5, session.nextSeq)
    }

    @Test
    fun drawRecordsKeepAContentSnapshotOfTheLot() {
        val session = session("A" to 1)
        val record = session.drawOne()
        assertNotNull(record)

        session.updateLot(session.lotById("A")!!.copy(text = "改名了"))
        session.removeLot("A")

        assertEquals("A", session.results.first().text)
        assertEquals(LotKind.TEXT, session.results.first().kind)
    }

    // ---------- 放回一支 / 重新开始 ----------

    @Test
    fun undoPutsTheLastCopyBack() {
        val session = session("A" to 2)
        session.draw(2)
        assertEquals(0, session.totalRemaining)

        val undone = session.undoLast()

        assertNotNull(undone)
        assertEquals("A", undone!!.text)
        assertEquals(1, session.totalRemaining)
        assertEquals(1, session.results.size)
        assertTrue(session.canDraw())
    }

    @Test
    fun undoInWithReplacementModeOnlyRemovesTheRecord() {
        val session = session("A" to 1, mode = DrawMode.WITH_REPLACEMENT)
        session.draw(3)
        assertEquals(3, session.results.size)

        session.undoLast()

        assertEquals(2, session.results.size)
        assertEquals(1, session.totalRemaining)
    }

    @Test
    fun undoOnEmptyHistoryIsANoOp() {
        val session = session("A" to 1)
        assertNull(session.undoLast())
        assertTrue(session.results.isEmpty())
        assertEquals(1, session.totalRemaining)
    }

    // ---------- 放回指定的某一条结果 ----------

    @Test
    fun putBackReturnsOneSpecificCopyAndKeepsTheOtherResults() {
        val session = session("A" to 1, "B" to 1, "C" to 1)
        val drawn = session.draw(3)
        assertEquals(0, session.totalRemaining)

        val middle = drawn[1]
        val returned = session.putBack(middle.seq)

        assertEquals(middle, returned)
        assertEquals(listOf(drawn[0].seq, drawn[2].seq), session.results.map { it.seq })
        assertEquals(1, session.totalRemaining)

        // 放回去的那一支还能再被抽到
        assertEquals(1, session.draw(1).size)
        assertEquals(0, session.totalRemaining)
    }

    @Test
    fun putBackInWithReplacementModeOnlyRemovesTheRecord() {
        val session = session("A" to 1, mode = DrawMode.WITH_REPLACEMENT)
        session.draw(3)
        assertEquals(3, session.results.size)

        session.putBack(session.results[1].seq)

        assertEquals(2, session.results.size)
        assertEquals(1, session.totalRemaining)
    }

    @Test
    fun putBackOfAnUnknownRecordChangesNothing() {
        val session = session("A" to 1)
        session.draw(1)
        assertEquals(0, session.totalRemaining)

        assertNull(session.putBack(999))

        assertEquals(1, session.results.size)
        assertEquals(0, session.totalRemaining)
    }

    @Test
    fun putBackOnlyConsumesTheCopiesActuallyTaken() {
        val session = session("A" to 2)
        session.draw(2)
        val first = session.results.first()

        session.putBack(first.seq)

        assertEquals(1, session.results.size)
        assertEquals(1, session.totalRemaining)
        assertEquals(1, session.drawnCountOf("A"))
    }

    // ---------- 备注 ----------

    @Test
    fun setNoteWritesOnlyThatRecord() {
        val session = session("A" to 1, "B" to 1)
        val drawn = session.draw(2)

        val updated = session.setNote(drawn[0].seq, "  小明抽到  ")

        assertEquals("小明抽到", updated?.note)
        assertEquals("小明抽到", session.results[0].note)
        assertEquals("", session.results[1].note)
        // 备注不影响顺序、也不影响剩余数量
        assertEquals(drawn.map { it.seq }, session.results.map { it.seq })
        assertEquals(0, session.totalRemaining)
    }

    @Test
    fun setNoteCanClearAnExistingNote() {
        val session = session("A" to 1)
        val record = session.draw(1).single()

        session.setNote(record.seq, "小明抽到")
        assertEquals("小明抽到", session.results.single().note)

        session.setNote(record.seq, "   ")
        assertEquals("", session.results.single().note)
    }

    @Test
    fun setNoteOnAnUnknownRecordChangesNothing() {
        val session = session("A" to 1)
        session.draw(1)

        assertNull(session.setNote(999, "小明抽到"))

        assertEquals("", session.results.single().note)
    }

    @Test
    fun noteSurvivesSnapshotAndRestore() {
        val session = session("A" to 1)
        session.draw(1)
        session.setNote(1, "小明抽到")

        val restored = DrawSession(
            session.snapshot().lots,
            session.snapshot().results,
            session.snapshot().mode,
        )

        assertEquals("小明抽到", restored.results.single().note)
    }

    // ---------- 联机署名 ----------

    @Test
    fun singleDeviceDrawHasNoDrawerName() {
        val session = session("A" to 1)

        val record = session.draw(1).single()

        assertEquals("", record.drawnBy)
    }

    @Test
    fun drawRecordsTheDrawerNameWhenGiven() {
        val session = session("A" to 1, "B" to 1)

        val drawn = session.draw(2, drawnBy = "  小明  ")

        assertEquals(listOf("小明", "小明"), drawn.map { it.drawnBy })
    }

    @Test
    fun drawerNameDoesNotAffectNotesOrRemainingCopies() {
        val session = session("A" to 2)

        session.draw(1, drawnBy = "小明")
        session.setNote(1, "自己写的备注")

        val record = session.results.single()
        assertEquals("小明", record.drawnBy)
        assertEquals("自己写的备注", record.note)
        assertEquals(1, session.totalRemaining)
    }

    @Test
    fun drawerNameSurvivesSnapshotAndRestore() {
        val session = session("A" to 1)
        session.draw(1, drawnBy = "小红")

        val snapshot = session.snapshot()
        val restored = DrawSession(snapshot.lots, snapshot.results, snapshot.mode)

        assertEquals("小红", restored.results.single().drawnBy)
    }

    @Test
    fun resetPoolClearsResultsAndReturnsEveryCopy() {
        val session = session("A" to 2, "B" to 1)
        session.draw(3)
        assertEquals(0, session.totalRemaining)

        session.resetPool()

        assertTrue(session.results.isEmpty())
        assertEquals(3, session.totalRemaining)
        assertTrue(session.canDraw())
    }

    // ---------- 数量变化 ----------

    @Test
    fun reducingQuantityPutsExcessCopiesBack() {
        val session = session("A" to 3)
        session.draw(2)
        assertEquals(1, session.totalRemaining)

        session.setQuantity("A", 1)

        assertEquals(1, session.drawnCountOf("A"))
        assertEquals(0, session.totalRemaining)
    }

    @Test
    fun increasingQuantityAddsDrawableCopies() {
        val session = session("A" to 1)
        session.draw(1)
        assertEquals(0, session.totalRemaining)

        session.setQuantity("A", 3)

        assertEquals(2, session.totalRemaining)
        assertEquals(2, session.plannedDrawCount(5))
    }

    @Test
    fun removingLotDropsItsCopiesButKeepsTheHistory() {
        val session = session("A" to 2)
        session.drawOne()

        session.removeLot("A")

        assertTrue(session.lots.isEmpty())
        assertEquals(0, session.totalRemaining)
        assertFalse(session.canDraw())
        assertEquals(1, session.results.size)
    }

    @Test
    fun clearingThePoolRemovesLotsAndResults() {
        val session = session("A" to 2, "B" to 1)
        session.draw(2)

        session.clearLots()

        assertTrue(session.lots.isEmpty())
        assertTrue(session.results.isEmpty())
        assertEquals(0, session.totalQuantity)
    }

    // ---------- 模式切换 / 计划抽取数 ----------

    @Test
    fun plannedDrawCountRespectsRemainingCopies() {
        val session = session("A" to 3)
        assertEquals(3, session.plannedDrawCount(5))

        session.draw(1)
        assertEquals(2, session.plannedDrawCount(5))

        session.setMode(DrawMode.WITH_REPLACEMENT)
        assertEquals(5, session.plannedDrawCount(5))
        assertEquals(0, session.plannedDrawCount(0))
    }

    @Test
    fun switchingToWithReplacementAllowsDrawingBeyondThePool() {
        val session = session("A" to 1, "B" to 1)
        session.draw(2)
        assertFalse(session.canDraw())

        session.setMode(DrawMode.WITH_REPLACEMENT)

        assertTrue(session.canDraw())
        assertEquals(3, session.draw(3).size)
        assertEquals(5, session.results.size)
        // 之前不放回时抽走的签不会因为切换模式而回到池子里
        assertEquals(0, session.totalRemaining)
    }

    @Test
    fun emptyPoolCannotDraw() {
        val session = DrawSession(emptyList())

        assertFalse(session.canDraw())
        assertNull(session.drawOne())
        assertTrue(session.draw(3).isEmpty())
        assertEquals(0, session.plannedDrawCount(5))
    }

    // ---------- 存档 / 读档 ----------

    @Test
    fun snapshotRoundTripContinuesWhereItStopped() {
        val random = Random(11)
        val session = DrawSession(
            lots("A" to 2, "B" to 2),
            emptyList(),
            DrawMode.WITHOUT_REPLACEMENT,
            random,
        )
        session.draw(2)

        val snapshot = session.snapshot()
        val restored = DrawSession(snapshot.lots, snapshot.results, snapshot.mode, random)

        assertEquals(2, restored.results.size)
        assertEquals(2, restored.totalRemaining)

        restored.draw(10)

        assertEquals(4, restored.results.size)
        assertEquals(0, restored.totalRemaining)
        assertEquals(listOf(1, 2, 3, 4), restored.results.map { it.seq })
    }
}
