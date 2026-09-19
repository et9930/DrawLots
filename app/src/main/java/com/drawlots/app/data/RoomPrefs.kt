package com.drawlots.app.data

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

/**
 * 联机相关的本机设置（与签池状态分开存，避免和 PoolStore 的职责混在一起）：
 * - 我的名称：联机时显示给别人，也用于抽签署名
 * - 本机 deviceId：只生成一次，用于房主区分成员身份
 */
class RoomPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun myName(defaultName: String): String =
        prefs.getString(KEY_MY_NAME, null)?.takeIf { it.isNotBlank() } ?: defaultName

    fun setMyName(name: String) {
        prefs.edit { putString(KEY_MY_NAME, name.trim().take(MAX_NAME_LENGTH)) }
    }

    fun deviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString().replace("-", "").take(16)
        prefs.edit { putString(KEY_DEVICE_ID, created) }
        return created
    }

    private companion object {
        const val PREFS_NAME = "draw_lots_room"
        const val KEY_MY_NAME = "my_name"
        const val KEY_DEVICE_ID = "device_id"
        const val MAX_NAME_LENGTH = 12
    }
}
