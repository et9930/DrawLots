package com.drawlots.app.domain

/**
 * 加入房间前暂存「自己的离线状态」的端口。
 *
 * 由 `data/PoolStore` 实现（SharedPreferences），联机控制器只依赖这个接口，
 * 因此房间逻辑可以在 JVM 上测试。
 */
interface RoomBackupStore {
    fun saveOfflineBackup(snapshot: PoolSnapshot)

    /** 没有备份时返回 null；备份是空签池时返回空签池（用「键是否存在」区分）。 */
    fun loadOfflineBackup(): PoolSnapshot?

    fun clearOfflineBackup()
}
