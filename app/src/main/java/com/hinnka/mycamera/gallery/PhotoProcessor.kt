package com.hinnka.mycamera.gallery

import android.content.Context
import android.graphics.*
import android.net.Uri
import com.hinnka.mycamera.frame.ExifMetadata
import com.hinnka.mycamera.frame.FrameManager
import com.hinnka.mycamera.frame.FrameRenderer
import com.hinnka.mycamera.lut.LutImageProcessor
import com.hinnka.mycamera.lut.LutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 照片处理器
 * 
 * 集中管理照片的 LUT、旋转、亮度和边框应用逻辑
 */
class PhotoProcessor(
    private val lutManager: LutManager,
    private val lutImageProcessor: LutImageProcessor,
    private val frameManager: FrameManager,
    private val frameRenderer: FrameRenderer
) {
    /**
     * 处理照片并应用所有元数据中的效果
     * 
     * @param context 上下文
     * @param input 输入 Bitmap
     * @param metadata 照片元数据
     * @param uri 照片 URI（用于提取 EXIF）
     * @return 处理后的 Bitmap
     */
    suspend fun process(
        context: Context,
        input: Bitmap,
        metadata: PhotoMetadata,
        uri: Uri? = null,
        exifMetadata: ExifMetadata? = null
    ): Bitmap = withContext(Dispatchers.Default) {
        var result = input
        
        // 1. 应用 LUT
        if (metadata.lutId != null && metadata.lutIntensity > 0f) {
            val lutConfig = lutManager.loadLut(metadata.lutId)
            if (lutConfig != null) {
                val lutResult = lutImageProcessor.applyLut(result, lutConfig, metadata.lutIntensity)
                result = lutResult
            }
        }
        
        // 2. 应用旋转和亮度
        if (metadata.rotation != 0f || metadata.brightness != 1f) {
            val editedResult = applyEdits(result, metadata.rotation, metadata.brightness)
            if (editedResult != result && result != input) {
                result.recycle()
            }
            result = editedResult
        }
        
        // 3. 应用边框水印
        if (metadata.frameId != null) {
            val template = frameManager.loadTemplate(metadata.frameId)
            if (template != null) {
                val finalExifMetadata = exifMetadata ?: if (uri != null) {
                    ExifMetadata.fromUri(context, uri)
                } else {
                    ExifMetadata.createDefault(result.width, result.height)
                }
                val framedResult = frameRenderer.render(
                    result,
                    template,
                    finalExifMetadata,
                    metadata.showAppBranding
                )
                if (framedResult != result && result != input) {
                    result.recycle()
                }
                result = framedResult
            }
        }
        
        result
    }

    /**
     * 应用旋转和亮度调整
     */
    private fun applyEdits(bitmap: Bitmap, rotation: Float, brightness: Float): Bitmap {
        val matrix = Matrix()
        
        // 旋转
        if (rotation != 0f) {
            matrix.postRotate(rotation)
        }
        
        var result = if (rotation != 0f) {
            try {
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } catch (e: Exception) {
                bitmap
            }
        } else {
            bitmap
        }
        
        // 亮度调整
        if (brightness != 1f) {
            try {
                val adjustedBitmap = Bitmap.createBitmap(result.width, result.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(adjustedBitmap)
                val paint = Paint()
                val colorMatrix = ColorMatrix().apply {
                    setScale(brightness, brightness, brightness, 1f)
                }
                paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
                canvas.drawBitmap(result, 0f, 0f, paint)
                if (result != bitmap) {
                    result.recycle()
                }
                result = adjustedBitmap
            } catch (e: Exception) {
                // Ignore and keep result as is
            }
        }
        
        return result
    }
}
