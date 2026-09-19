package com.drawlots.app.room

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * 二维码解码（纯 Kotlin + zxing，不依赖 Android）。
 *
 * 抽出来是为了能单测：相机回来的 Y 平面与相册图片都走这里，
 * 只有相机/相册的取数据部分留在 Android 侧。
 */
object QrDecoding {

    private val hints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    )

    /**
     * 相机 Y 平面（灰度）直接解码。
     *
     * [rowStride] 常常大于 [width]（相机行填充），必须按 stride 建 LuminanceSource 才不会把图撕开。
     */
    fun decodeLuminance(
        data: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int = width,
    ): String? {
        if (width <= 0 || height <= 0) return null
        val stride = maxOf(rowStride, width)
        if (data.size < stride * height) return null
        return runCatching {
            val source = PlanarYUVLuminanceSource(data, stride, height, 0, 0, width, height, false)
            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints)?.text
        }.getOrNull()
    }

    /** 相册图片：ARGB 像素 → 灰度 → 解码（相册图没有行填充）。 */
    fun decodePixels(pixels: IntArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        val luminance = ByteArray(width * height)
        for (index in luminance.indices) {
            val color = pixels[index]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            luminance[index] = ((r * 306 + g * 601 + b * 117) shr 10).toByte()
        }
        return decodeLuminance(luminance, width, height)
    }
}
