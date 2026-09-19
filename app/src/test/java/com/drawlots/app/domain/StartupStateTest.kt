package com.drawlots.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupStateTest {

    private val mine = PoolSnapshot(
        lots = listOf(Lot(id = "mine", kind = LotKind.TEXT, text = "我自己的签", quantity = 1)),
    )

    @Test
    fun withoutBackupTheCurrentStateIsUsed() {
        val choice = chooseStartupSnapshot(current = mine, offlineBackup = null)

        assertEquals(mine, choice.snapshot)
        assertFalse(choice.restoredFromBackup)
    }

    @Test
    fun withBackupTheOfflineStateIsRestored() {
        val backup = PoolSnapshot(
            lots = listOf(Lot(id = "mine", kind = LotKind.TEXT, text = "房间之前的签", quantity = 3)),
        )

        val choice = chooseStartupSnapshot(current = mine, offlineBackup = backup)

        assertEquals(backup, choice.snapshot)
        assertTrue(choice.restoredFromBackup)
    }

    @Test
    fun anEmptyBackupStillCountsAsABackup() {
        // 空签池也是一种状态：上次加入房间时本来就是空签池，也要恢复成空，而不是保留房间副本
        val empty = PoolSnapshot()

        val choice = chooseStartupSnapshot(current = mine, offlineBackup = empty)

        assertTrue(choice.restoredFromBackup)
        assertTrue(choice.snapshot.lots.isEmpty())
    }
}
