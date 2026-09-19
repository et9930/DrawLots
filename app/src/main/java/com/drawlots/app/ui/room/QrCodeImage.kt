package com.drawlots.app.ui.room

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter

/**
 * 把邀请内容画成二维码。
 *
 * 用 zxing core 自己生成位图（不引 AppCompat / Play 服务）；生成失败时显示原文，不崩。
 */
@Composable
fun QrCodeImage(
    content: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val sizePx = with(density) { size.roundToPx() }.coerceAtLeast(64)
    val bitmap = remember(content, sizePx) { generate(content, sizePx) }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(androidx.compose.ui.graphics.Color.White)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "房间二维码",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = content,
                style = MaterialTheme.typography.bodySmall,
                color = androidx.compose.ui.graphics.Color.Black,
            )
        }
    }
}

private fun generate(content: String, sizePx: Int): ImageBitmap? = runCatching {
    val hints = mapOf(
        EncodeHintType.MARGIN to 1,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val pixels = IntArray(sizePx * sizePx) { index ->
        if (matrix.get(index % sizePx, index / sizePx)) AndroidColor.BLACK else AndroidColor.WHITE
    }
    Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888).asImageBitmap()
}.getOrNull()
