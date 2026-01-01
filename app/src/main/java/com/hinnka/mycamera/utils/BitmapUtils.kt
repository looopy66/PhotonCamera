package com.hinnka.mycamera.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.InputStream
import android.util.Log

/**
 * Bitmap 处理工具类
 */
object BitmapUtils {
    private const val TAG = "BitmapUtils"

    /**
     * 归一化 JPEG 字节流的方向
     * 
     * 读取 EXIF 方向，如果不是 NORMAL，则物理旋转像素并重新编码为 JPEG，
     * 确保返回的字节流物理上是正的。
     */
    fun normalizeJpegOrientation(bytes: ByteArray, orientation: Int): ByteArray {
        if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) {
            return bytes
        }

        return try {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
            val rotated = rotateBitmapIfNeeded(bitmap, orientation)
            val outputStream = java.io.ByteArrayOutputStream()
            rotated.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
            val result = outputStream.toByteArray()
            if (rotated != bitmap) {
                rotated.recycle()
            }
            bitmap.recycle()
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to normalize JPEG orientation", e)
            bytes
        }
    }

    /**
     * 根据 EXIF 方向旋转 Bitmap
     */
    fun rotateBitmapIfNeeded(bitmap: Bitmap, orientation: Int): Bitmap {
        if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) {
            return bitmap
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

        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) {
                bitmap.recycle()
            }
            rotated
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate bitmap", e)
            bitmap
        }
    }
}
