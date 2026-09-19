package com.drawlots.app.ui.room

import com.drawlots.app.room.RoomRole

/**
 * 界面能力判定（唯一 owner）。
 *
 * 这里的规则必须与 `RoomHost.permissionError` 一致：界面不显示房主一定会拒绝的入口，
 * 免得用户点了才收到「房主未开放」。
 */
object RoomPermissions {

    fun canDraw(role: RoomRole): Boolean = true

    fun canSetNote(role: RoomRole): Boolean = true

    fun canRecover(role: RoomRole, allowRecover: Boolean): Boolean = when (role) {
        RoomRole.Offline, RoomRole.Host -> true
        RoomRole.Member -> allowRecover
    }

    fun canEditPool(role: RoomRole, allowEdit: Boolean): Boolean = when (role) {
        RoomRole.Offline, RoomRole.Host -> true
        RoomRole.Member -> allowEdit
    }

    /** 重置（清空结果、全部放回）永远只有房主能做。 */
    fun canReset(role: RoomRole): Boolean = role != RoomRole.Member

    /** 切换放回/不放回是房间共享规则：成员不能改。 */
    fun canChangeMode(role: RoomRole): Boolean = role != RoomRole.Member

    /** 图片签只能房主添加（图片文件在房主设备上）。 */
    fun canAddImageLot(role: RoomRole): Boolean = role != RoomRole.Member

    /** 成员没被放权时签池只读，界面上给个提示。 */
    fun poolReadOnlyHint(role: RoomRole, allowEdit: Boolean): String? =
        if (role == RoomRole.Member && !allowEdit) "房主未开放编辑签池" else null

    fun recoverDisabledHint(role: RoomRole, allowRecover: Boolean): String? =
        if (role == RoomRole.Member && !allowRecover) "房主未开放放回" else null
}
