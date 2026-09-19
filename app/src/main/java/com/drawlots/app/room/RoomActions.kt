package com.drawlots.app.room

import com.drawlots.app.domain.DrawSession
import com.drawlots.app.domain.MAX_QUANTITY

/**
 * 把一个 [RoomAction] 应用到状态上。
 *
 * 房主应用成员请求、房主应用自己的操作、以及单机模式，都走这一个函数，
 * 保证三条路径的语义完全一致（唯一 owner）。
 *
 * [drawerName] 只在联机时传入，用来写抽签署名；单机模式传空串。
 */
fun applyRoomAction(
    session: DrawSession,
    action: RoomAction,
    drawerName: String = "",
) {
    when (action) {
        is RoomAction.Draw -> session.draw(action.count.coerceIn(1, 99), drawnBy = drawerName)
        is RoomAction.SetNote -> session.setNote(action.seq, action.note)
        RoomAction.Undo -> session.undoLast()
        is RoomAction.PutBack -> session.putBack(action.seq)
        RoomAction.ResetPool -> session.resetPool()
        is RoomAction.SetMode -> session.setMode(action.mode)
        is RoomAction.AddLot -> session.addLot(
            action.lot.copy(quantity = action.lot.quantity.coerceIn(1, MAX_QUANTITY)),
        )
        is RoomAction.UpdateLot -> session.updateLot(action.lot)
        is RoomAction.RemoveLot -> session.removeLot(action.id)
        is RoomAction.SetQuantity -> session.setQuantity(action.id, action.quantity)
        RoomAction.ClearPool -> session.clearLots()
    }
}
