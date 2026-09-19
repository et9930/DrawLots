package com.drawlots.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 图片签的本地图片管理。
 *
 * 用户从图库选择或拍照得到的图片会被压缩后**复制到 App 私有目录**（files/lots），
 * 这样即使原来的相册图片被删除、或者 Uri 权限过期，签池里的图片依然可用。
 * 拍照时的临时文件放在 cache/camera，由 FileProvider 提供给相机 App 写入。
 */
object ImageStore {

    /** 保存的图片最长边（像素）。 */
    const val MAX_DIM = 1600

    /** 显示用的缩略图最长边。 */
    const val THUMB_DIM = 480

    private const val JPEG_QUALITY = 88
    private const val LOTS_DIR = "lots"
    private const val CAMERA_DIR = "camera"

    fun lotsDir(context: Context): File =
        File(context.filesDir, LOTS_DIR).apply { if (!exists()) mkdirs() }

    private fun cameraDir(context: Context): File =
        File(context.cacheDir, CAMERA_DIR).apply { if (!exists()) mkdirs() }

    /** 相机拍照前创建一个临时文件。 */
    fun newCameraFile(context: Context): File =
        File(cameraDir(context), "cam_${System.currentTimeMillis()}.jpg")

    /** 把临时文件包装成可以交给相机 App 的 content:// Uri。 */
    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** 从图库（或任何 content Uri）导入一张图片，返回保存后的绝对路径。 */
    suspend fun importImage(context: Context, uri: Uri, maxDim: Int = MAX_DIM): String? =
        withContext(Dispatchers.IO) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@withContext null
                writeLotImage(context, bytes, maxDim)
            } catch (e: Exception) {
                null
            }
        }

    /** 从相机临时文件导入一张图片；无论成功失败都会删掉临时文件。 */
    suspend fun importFile(context: Context, file: File, maxDim: Int = MAX_DIM): String? =
        withContext(Dispatchers.IO) {
            try {
                if (!file.isFile || file.length() == 0L) return@withContext null
                writeLotImage(context, file.readBytes(), maxDim)
            } catch (e: Exception) {
                null
            } finally {
                file.delete()
            }
        }

    /** 按最长边 [maxDim] 采样解码本地图片（给 UI 用）。 */
    suspend fun decodeSampled(path: String, maxDim: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val file = File(path)
            if (!file.isFile) return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDim)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(path, options)
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Exception) {
            null
        }
    }

    fun delete(path: String?) {
        if (path.isNullOrBlank()) return
        try {
            File(path).delete()
        } catch (e: Exception) {
            // 删不掉就算了，不影响使用。
        }
    }

    private fun writeLotImage(context: Context, bytes: ByteArray, maxDim: Int): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDim)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        bitmap = applyExifOrientation(bytes, bitmap)
        bitmap = scaleDown(bitmap, maxDim)

        val target = File(lotsDir(context), "lot_${System.currentTimeMillis()}_${bitmap.hashCode()}.jpg")
        return try {
            FileOutputStream(target).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            target.absolutePath
        } catch (e: Exception) {
            target.delete()
            null
        } finally {
            bitmap.recycle()
        }
    }

    private fun sampleSize(width: Int, height: Int, maxDim: Int): Int {
        var sample = 1
        while (max(width, height) / (sample * 2) >= maxDim) sample *= 2
        return sample
    }

    private fun applyExifOrientation(bytes: ByteArray, bitmap: Bitmap): Bitmap {
        val orientation = try {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }

            else -> return bitmap
        }
        if (matrix.isIdentity) return bitmap
        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            rotated
        } catch (e: Exception) {
            bitmap
        }
    }

    private fun scaleDown(bitmap: Bitmap, maxDim: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / longest
        val width = (bitmap.width * ratio).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
        return try {
            val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
            if (scaled != bitmap) bitmap.recycle()
            scaled
        } catch (e: Exception) {
            bitmap
        }
    }
}
