package com.drawlots.app.data

import android.content.Context
import androidx.core.content.edit
import com.drawlots.app.domain.PoolCodec
import com.drawlots.app.domain.PoolSnapshot
import com.drawlots.app.domain.RoomBackupStore

/** 用 SharedPreferences 保存签池状态（数据量很小，不需要数据库）。 */
class PoolStore(context: Context) : RoomBackupStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): PoolSnapshot = PoolCodec.decode(prefs.getString(KEY_STATE, null))

    fun save(snapshot: PoolSnapshot) {
        prefs.edit { putString(KEY_STATE, PoolCodec.encode(snapshot)) }
    }

    /**
     * 加入房间前把自己的离线状态存一份。
     * 用「键是否存在」区分「没有备份」和「备份是空签池」。
     */
    override fun saveOfflineBackup(snapshot: PoolSnapshot) {
        prefs.edit { putString(KEY_OFFLINE_BACKUP, PoolCodec.encode(snapshot)) }
    }

    override fun loadOfflineBackup(): PoolSnapshot? {
        if (!prefs.contains(KEY_OFFLINE_BACKUP)) return null
        return PoolCodec.decode(prefs.getString(KEY_OFFLINE_BACKUP, null))
    }

    override fun clearOfflineBackup() {
        prefs.edit { remove(KEY_OFFLINE_BACKUP) }
    }

    private companion object {
        const val PREFS_NAME = "draw_lots_state"
        const val KEY_STATE = "state_json"
        const val KEY_OFFLINE_BACKUP = "offline_backup"
    }
}
