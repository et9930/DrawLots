package com.drawlots.app.media

import com.drawlots.app.domain.DrawSession
import com.drawlots.app.room.RoomImageKeys
import com.drawlots.app.room.RoomImageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 房主侧发图：按 `imageKey` 在当前签池里找到对应的图片文件读出来。
 *
 * key 是路径的哈希，所以不需要额外维护注册表——签池本身就是这份映射的 owner。
 */
class LotImageSource(private val session: DrawSession) : RoomImageSource {

    override suspend fun read(imageKey: String): ByteArray? = withContext(Dispatchers.IO) {
        val path = session.lots
            .mapNotNull { it.imagePath }
            .firstOrNull { RoomImageKeys.keyFor(it) == imageKey }
            ?: return@withContext null
        val file = File(path)
        if (!file.isFile) return@withContext null
        runCatching { file.readBytes() }.getOrNull()
    }
}
