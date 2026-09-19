package com.drawlots.app.domain

/**
 * 启动时该用哪一份状态。
 *
 * 房间不跨进程存活：如果上次加入房间时留下了「离线备份」，说明那个房间已经不在了，
 * 启动就该恢复自己的签池，而不是继续显示房间副本。
 */
data class StartupChoice(
    val snapshot: PoolSnapshot,
    val restoredFromBackup: Boolean,
)

fun chooseStartupSnapshot(
    current: PoolSnapshot,
    offlineBackup: PoolSnapshot?,
): StartupChoice = if (offlineBackup != null) {
    StartupChoice(snapshot = offlineBackup, restoredFromBackup = true)
} else {
    StartupChoice(snapshot = current, restoredFromBackup = false)
}
