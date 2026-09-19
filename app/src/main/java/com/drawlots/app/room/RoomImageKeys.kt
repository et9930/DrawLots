package com.drawlots.app.room

import java.security.MessageDigest

/**
 * 图片的 `imageKey`：取房主侧图片绝对路径的 SHA-256 前 32 个十六进制字符。
 *
 * 两端必须用同一个规则：房主按 key 找文件，成员按 key 请求与缓存
 * （`filesDir/room-images/<imageKey>.jpg`）。路径在房主设备上导入后不再变化，所以是稳定的。
 */
object RoomImageKeys {

    fun keyFor(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
        return buildString(32) {
            for (byte in digest) {
                append(HEX[(byte.toInt() shr 4) and 0x0F])
                append(HEX[byte.toInt() and 0x0F])
                if (length >= 32) break
            }
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
