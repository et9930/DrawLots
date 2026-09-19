package com.drawlots.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLotEntriesTest {

    @Test
    fun oneEntryPerNonEmptyLine() {
        val entries = buildTextLotEntries("张三\n李四\n\n  王五  \n", quantity = 2, onePerLine = true)

        assertEquals(listOf("张三" to 2, "李四" to 2, "王五" to 2), entries)
    }

    @Test
    fun wholeTextAsOneEntryKeepsLineBreaks() {
        val entries = buildTextLotEntries("第一行\n第二行", quantity = 1, onePerLine = false)

        assertEquals(listOf("第一行\n第二行" to 1), entries)
    }

    @Test
    fun blankInputProducesNothing() {
        assertTrue(buildTextLotEntries("   \n  ", quantity = 3, onePerLine = true).isEmpty())
        assertTrue(buildTextLotEntries("   \n  ", quantity = 3, onePerLine = false).isEmpty())
    }

    @Test
    fun singleLineIsTrimmed() {
        assertEquals(
            listOf("张三" to 5),
            buildTextLotEntries("  张三  ", quantity = 5, onePerLine = false),
        )
    }
}
