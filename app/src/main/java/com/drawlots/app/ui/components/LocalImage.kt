package com.drawlots.app.ui.components

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.drawlots.app.R
import com.drawlots.app.domain.Lot
import com.drawlots.app.domain.LotKind
import com.drawlots.app.media.ImageStore
import java.io.File

/**
 * 极简的本地图片加载器（按最长边采样解码 + LRU 内存缓存）。
 *
 * 有意不用图片加载库：图片都在本地，采样解码已经够用，也少一个依赖。
 */
private val bitmapCache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
}

/**
 * 联机时把「房主侧的图片路径」解析成本机可读的路径（必要时向房主拉取并缓存）。
 *
 * 只在成员模式下由 [androidx.compose.runtime.CompositionLocalProvider] 提供；
 * 单机/房主为 null，[LocalImage] 直接读本地文件。
 */
fun interface RemoteImageResolver {
    suspend fun resolve(imagePath: String): String?
}

val LocalRemoteImageResolver = staticCompositionLocalOf<RemoteImageResolver?> { null }

@Composable
fun LocalImage(
    path: String?,
    modifier: Modifier = Modifier,
    targetSizePx: Int = ImageStore.THUMB_DIM,
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = null,
    placeholderTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    if (path.isNullOrBlank()) {
        ImagePlaceholder(modifier, placeholderTint)
        return
    }

    val resolver = LocalRemoteImageResolver.current
    var resolvedPath by remember(path) { mutableStateOf(if (File(path).isFile) path else null) }

    LaunchedEffect(path, resolver) {
        if (resolvedPath == null && resolver != null) {
            resolvedPath = resolver.resolve(path)
        }
    }

    val localPath = resolvedPath
    val key = "$localPath@$targetSizePx"
    val bitmap by produceState<ImageBitmap?>(initialValue = bitmapCache.get(key), key1 = key) {
        if (value == null && localPath != null) {
            val decoded: Bitmap? = ImageStore.decodeSampled(localPath, targetSizePx)
            val image = decoded?.asImageBitmap()
            if (image != null) bitmapCache.put(key, image)
            value = image
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        } else {
            ImagePlaceholder(Modifier.fillMaxSize(), placeholderTint)
        }
    }
}

@Composable
fun ImagePlaceholder(modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Icon(
            painter = painterResource(R.drawable.ic_gallery),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** 签的方形缩略图：图片签显示图片，文字签显示首字（或序号徽标）。 */
@Composable
fun LotThumb(
    lot: Lot?,
    size: Dp,
    modifier: Modifier = Modifier,
    badge: String? = null,
    contentDescription: String? = null,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 4))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        val imagePath = lot?.imagePath
        if (lot?.kind == LotKind.IMAGE && !imagePath.isNullOrBlank()) {
            LocalImage(
                path = imagePath,
                modifier = Modifier.fillMaxSize(),
                targetSizePx = (size.value * 3).toInt(),
                contentDescription = contentDescription,
            )
        } else {
            Text(
                text = badge ?: lot?.title?.trim()?.take(1) ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}
