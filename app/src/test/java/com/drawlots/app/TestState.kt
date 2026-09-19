package com.drawlots.app

import android.content.Context
import com.drawlots.app.domain.PoolCodec
import com.drawlots.app.domain.PoolSnapshot

/**
 * 测试辅助：Robolectric 在同一个 sandbox 里会复用数据和 SharedPreferences 缓存，
 * 不同测试方法之间会互相污染（App 里的 save() 用的是异步 apply()，更容易读到旧数据）。
 * 所以每个测试在启动 Activity 之前都用 commit() 同步重置一次状态。
 */
object TestState {

    private const val PREFS_NAME = "draw_lots_state"
    private const val KEY_STATE = "state_json"

    fun reset(context: Context, snapshot: PoolSnapshot? = null) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        if (snapshot != null) {
            prefs.edit().putString(KEY_STATE, PoolCodec.encode(snapshot)).commit()
        }
    }
}
