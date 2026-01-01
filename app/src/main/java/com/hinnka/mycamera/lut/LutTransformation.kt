package com.hinnka.mycamera.lut

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import coil.size.Size
import coil.transform.Transformation
import com.hinnka.mycamera.gallery.PhotoMetadata
import com.hinnka.mycamera.gallery.PhotoProcessor

/**
 * Coil 图像加载库的 LUT 转换器
 * 用于在加载照片时自动应用 LUT 效果
 */
class PhotoTransformation(
    private val context: Context,
    private val uri: Uri,
    private val metadata: PhotoMetadata,
    private val photoProcessor: PhotoProcessor
) : Transformation {
    
    override val cacheKey: String = "photo_${uri}_${metadata.toJson().hashCode()}"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        return photoProcessor.process(context, input, metadata, uri)
    }
}

