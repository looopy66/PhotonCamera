package com.hinnka.mycamera.lut

import android.graphics.Bitmap
import coil.size.Size
import coil.transform.Transformation
import com.hinnka.mycamera.frame.ExifMetadata
import com.hinnka.mycamera.frame.FrameManager
import com.hinnka.mycamera.frame.FrameRenderer

/**
 * Coil 图像加载库的 LUT 转换器
 * 用于在加载照片时自动应用 LUT 效果
 */
class LutTransformation(
    private val lutId: String?,
    private val intensity: Float,
    private val lutManager: LutManager,
    private val lutImageProcessor: LutImageProcessor
) : Transformation {
    
    override val cacheKey: String = "lut_${lutId ?: "none"}_$intensity"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        // 如果没有 LUT 或者强度为 0，不进行转换
        if (lutId == null || intensity <= 0f) return input
        
        // 加载 LUT 配置
        val lutConfig = lutManager.loadLut(lutId) ?: return input
        
        // 应用 LUT
        return lutImageProcessor.applyLut(input, lutConfig, intensity)
    }
}

/**
 * Coil 图像加载库的照片转换器
 * 用于在加载照片时自动应用 LUT 和边框效果
 */
class PhotoTransformation(
    private val lutId: String?,
    private val lutIntensity: Float,
    private val lutManager: LutManager,
    private val lutImageProcessor: LutImageProcessor,
    private val frameId: String? = null,
    private val showAppBranding: Boolean = true,
    private val frameManager: FrameManager? = null,
    private val frameRenderer: FrameRenderer? = null
) : Transformation {
    
    override val cacheKey: String = "photo_${lutId ?: "none"}_${lutIntensity}_${frameId ?: "none"}_$showAppBranding"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        var result = input
        
        // 应用 LUT
        if (lutId != null && lutIntensity > 0f) {
            val lutConfig = lutManager.loadLut(lutId)
            if (lutConfig != null) {
                val lutResult = lutImageProcessor.applyLut(result, lutConfig, lutIntensity)
                if (lutResult != result && result != input) {
                    result.recycle()
                }
                result = lutResult
            }
        }
        
        // 应用边框
        if (frameId != null && frameManager != null && frameRenderer != null) {
            val template = frameManager.loadTemplate(frameId)
            if (template != null) {
                val exifMetadata = ExifMetadata.createDefault(result.width, result.height)
                val framedResult = frameRenderer.render(result, template, exifMetadata, showAppBranding)
                if (framedResult != result && result != input) {
                    result.recycle()
                }
                result = framedResult
            }
        }
        
        return result
    }
}

