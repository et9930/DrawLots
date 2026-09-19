package com.drawlots.app.room

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 二维码解码核心（纯 JVM）：用 zxing 生成二维码 → 转成灰度 → 再解回来。
 *
 * 相机 Y 平面与相册图片都走这两个入口，所以这条测试覆盖了扫码的关键路径；
 * 相机取景与权限流程只能真机验证。
 */
class QrDecodingTest {

    private val content = "drawlots://v1?t=lan&h=192.168.1.5&p=47001&c=K7M2QX&r=周五抽签"

    private fun matrix(size: Int = 240) = MultiFormatWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8"),
    )

    private fun luminance(width: Int, height: Int, rowStride: Int = width): Pair<ByteArray, com.google.zxing.common.BitMatrix> {
        val matrix = matrix()
        val data = ByteArray(rowStride * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val black = x < matrix.width && y < matrix.height && matrix.get(x, y)
                data[y * rowStride + x] = if (black) 0 else 255.toByte()
            }
        }
        return data to matrix
    }

    @Test
    fun decodesLuminanceWithoutPadding() {
        val (data, matrix) = luminance(width = 240, height = 240)

        assertEquals(content, QrDecoding.decodeLuminance(data, matrix.width, matrix.height))
    }

    @Test
    fun decodesLuminanceWithCameraRowStride() {
        // 相机常见：每行末尾有填充，行距大于宽度
        val stride = 256
        val (data, matrix) = luminance(width = 240, height = 240, rowStride = stride)

        assertEquals(content, QrDecoding.decodeLuminance(data, matrix.width, matrix.height, rowStride = stride))
    }

    @Test
    fun decodesArgbPixels() {
        val matrix = matrix()
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }

        assertEquals(content, QrDecoding.decodePixels(pixels, width, height))
    }

    @Test
    fun returnsNullForBlankOrGarbageInput() {
        val blank = ByteArray(200 * 200) { 255.toByte() }
        assertNull(QrDecoding.decodeLuminance(blank, 200, 200))
        assertNull(QrDecoding.decodePixels(IntArray(200 * 200) { 0xFFFFFFFF.toInt() }, 200, 200))
        assertNull("尺寸非法", QrDecoding.decodeLuminance(blank, 0, 0))
        assertNull("缓冲区太小", QrDecoding.decodeLuminance(ByteArray(10), 200, 200))
        assertNull("像素太少", QrDecoding.decodePixels(IntArray(10), 200, 200))
    }

    @Test
    fun decodedInviteIsAcceptedByTheRoomCodeParser() {
        val (data, matrix) = luminance(width = 240, height = 240)
        val text = QrDecoding.decodeLuminance(data, matrix.width, matrix.height)

        val invite = RoomCode.decodeInvite(text)

        assertEquals(RoomTarget.Lan(host = "192.168.1.5", port = 47001), invite?.target)
        assertEquals("K7M2QX", invite?.roomCode)
        assertEquals("周五抽签", invite?.roomName)
    }
}
