package com.drawlots.app.media

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 成员侧的房间图片缓存：`filesDir/room-images/<imageKey>.jpg`。
 *
 * 与 `files/lots`（房主/单机自己的图片签）分开存放，退出房间不删除，方便再次进入同一房间复用。
 * 超过上限按最后访问时间淘汰。
 */
class RoomImageCache(context: Context) {

    private val dir = File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    fun cachedPath(imageKey: String): String? {
        val file = File(dir, "$imageKey.jpg")
        if (!file.isFile || file.length() == 0L) return null
        file.setLastModified(System.currentTimeMillis())
        return file.absolutePath
    }

    suspend fun store(imageKey: String, bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        try {
            val file = File(dir, "$imageKey.jpg")
            file.writeBytes(bytes)
            pruneIfNeeded()
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun pruneIfNeeded() {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_BYTES) return
        for (file in files) {
            if (total <= MAX_BYTES) break
            total -= file.length()
            file.delete()
        }
    }

    private companion object {
        const val DIR = "room-images"
        const val MAX_BYTES = 50L * 1024 * 1024
    }
}
