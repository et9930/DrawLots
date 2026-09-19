package com.drawlots.app.ui.room

import com.drawlots.app.room.RoomRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 界面能力真值表。
 *
 * 这些规则必须与 `RoomHost.permissionError` 一致：界面不显示房主一定会拒绝的入口。
 */
class RoomPermissionsTest {

    private val roles = listOf(RoomRole.Offline, RoomRole.Host, RoomRole.Member)

    @Test
    fun drawingAndNotesAreAlwaysAllowed() {
        for (role in roles) {
            assertTrue("抽签应始终可用：$role", RoomPermissions.canDraw(role))
            assertTrue("备注应始终可用：$role", RoomPermissions.canSetNote(role))
        }
    }

    @Test
    fun recoverDependsOnTheHostSwitchForMembersOnly() {
        assertTrue(RoomPermissions.canRecover(RoomRole.Offline, allowRecover = false))
        assertTrue(RoomPermissions.canRecover(RoomRole.Host, allowRecover = false))
        assertFalse(RoomPermissions.canRecover(RoomRole.Member, allowRecover = false))
        assertTrue(RoomPermissions.canRecover(RoomRole.Member, allowRecover = true))
    }

    @Test
    fun poolEditingDependsOnTheHostSwitchForMembersOnly() {
        assertTrue(RoomPermissions.canEditPool(RoomRole.Offline, allowEdit = false))
        assertTrue(RoomPermissions.canEditPool(RoomRole.Host, allowEdit = false))
        assertFalse(RoomPermissions.canEditPool(RoomRole.Member, allowEdit = false))
        assertTrue(RoomPermissions.canEditPool(RoomRole.Member, allowEdit = true))
    }

    @Test
    fun resetAndModeChangeAreNeverAvailableToMembers() {
        for (allowRecover in listOf(false, true)) {
            for (allowEdit in listOf(false, true)) {
                assertTrue(RoomPermissions.canReset(RoomRole.Offline))
                assertTrue(RoomPermissions.canReset(RoomRole.Host))
                assertFalse("成员永远不能重置", RoomPermissions.canReset(RoomRole.Member))
                assertTrue(RoomPermissions.canChangeMode(RoomRole.Host))
                assertFalse("成员不能切模式", RoomPermissions.canChangeMode(RoomRole.Member))
                assertTrue(RoomPermissions.canEditPool(RoomRole.Member, allowEdit).let { true })
                assertTrue(allowRecover || !RoomPermissions.canRecover(RoomRole.Member, allowRecover))
            }
        }
    }

    @Test
    fun imageLotsCanOnlyBeAddedByTheHost() {
        assertTrue(RoomPermissions.canAddImageLot(RoomRole.Offline))
        assertTrue(RoomPermissions.canAddImageLot(RoomRole.Host))
        assertFalse(RoomPermissions.canAddImageLot(RoomRole.Member))
    }

    @Test
    fun hintsOnlyAppearForMembersWithoutPermissions() {
        assertNull(RoomPermissions.poolReadOnlyHint(RoomRole.Host, allowEdit = false))
        assertNull(RoomPermissions.poolReadOnlyHint(RoomRole.Member, allowEdit = true))
        assertEquals("房主未开放编辑签池", RoomPermissions.poolReadOnlyHint(RoomRole.Member, allowEdit = false))

        assertNull(RoomPermissions.recoverDisabledHint(RoomRole.Member, allowRecover = true))
        assertEquals("房主未开放放回", RoomPermissions.recoverDisabledHint(RoomRole.Member, allowRecover = false))
    }
}
