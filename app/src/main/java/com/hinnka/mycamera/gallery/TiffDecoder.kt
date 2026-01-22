package com.hinnka.mycamera.gallery

import coil.ImageLoader
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.decode.ImageSource
import coil.fetch.SourceResult
import coil.request.Options
import com.hinnka.mycamera.utils.PLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.BufferedSource

/**
 * Coil Decoder for TIFF files
 *
 * 支持读取16-bit TIFF文件并转换为8-bit Bitmap用于显示
 */
class TiffDecoder(
    private val source: ImageSource,
    private val options: Options
) : Decoder {

    companion object {
        private const val TAG = "TiffDecoder"

        class Factory : Decoder.Factory {
            override fun create(
                result: SourceResult,
                options: Options,
                imageLoader: ImageLoader
            ): Decoder? {
                // 检查是否是TIFF文件
                if (!isTiff(result.source.source())) return null
                return TiffDecoder(result.source, options)
            }

            private fun isTiff(source: BufferedSource): Boolean {
                return try {
                    source.peek().use { peek ->
                        // 读取TIFF文件的魔术数字
                        val magic = peek.readShort()
                        // Little Endian: 0x4949 ("II")
                        // Big Endian: 0x4D4D ("MM")
                        magic == 0x4949.toShort() || magic == 0x4D4D.toShort()
                    }
                } catch (e: Exception) {
                    false
                }
            }
        }
    }

    override suspend fun decode(): DecodeResult = withContext(Dispatchers.IO) {
        try {
            // 将ImageSource转换为File
            val file = source.file().toFile()

            // 使用PhotoManager的loadTiffImage读取TIFF
            val context = options.context

            val tiffData = PhotoManager.loadTiffImage(file)
            if (tiffData != null) {
                val bitmap = tiffData.toBitmap()
                if (bitmap != null) {
                    return@withContext DecodeResult(
                        drawable = android.graphics.drawable.BitmapDrawable(context.resources, bitmap),
                        isSampled = false
                    )
                }
            }
            throw IllegalStateException("Failed to decode TIFF file")
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to decode TIFF", e)
            throw e
        }
    }
}
