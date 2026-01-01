package com.hinnka.mycamera.gallery

import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.hinnka.mycamera.camera.CaptureInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * EXIF 元数据写入器
 * 
 * 使用 ExifInterface 将拍摄信息写入 JPEG 文件
 */
object ExifWriter {
    
    private const val TAG = "ExifWriter"
    
    // EXIF 日期时间格式
    private val exifDateFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
    
    /**
     * 将拍摄信息写入 JPEG 文件的 EXIF
     * 
     * @param file JPEG 文件
     * @param captureInfo 拍摄信息
     */
    fun writeExif(file: File, captureInfo: CaptureInfo) {
        try {
            val exif = ExifInterface(file)
            
            // ========== 设备信息 ==========
            exif.setAttribute(ExifInterface.TAG_MAKE, captureInfo.make)
            exif.setAttribute(ExifInterface.TAG_MODEL, captureInfo.model)
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, captureInfo.software)
            
            // ========== 日期时间 ==========
            val dateTime = exifDateFormat.format(Date(captureInfo.captureTime))
            exif.setAttribute(ExifInterface.TAG_DATETIME, dateTime)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateTime)
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateTime)
            
            // ========== 图像方向 ==========
            // 由于我们在 Camera2 拍照时已经设置了 JPEG_ORIENTATION，
            // 硬件返回的图片字节流已经旋转到正确方向，
            // 所以 EXIF 中的方向标志应设为 NORMAL (1)，避免查看器进行二次旋转。
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            
            // ========== 图像尺寸 ==========
            if (captureInfo.imageWidth > 0) {
                exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, captureInfo.imageWidth.toString())
                exif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, captureInfo.imageWidth.toString())
            }
            if (captureInfo.imageHeight > 0) {
                exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, captureInfo.imageHeight.toString())
                exif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, captureInfo.imageHeight.toString())
            }
            
            // ========== 曝光参数 ==========
            captureInfo.exposureTime?.let { exposureNs ->
                // 曝光时间格式：分子/分母（秒）
                val exposureSec = exposureNs / 1_000_000_000.0
                if (exposureSec >= 1.0) {
                    // 1秒或更长
                    exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, exposureSec.toString())
                } else {
                    // 小于1秒，使用分数格式
                    val denominator = (1.0 / exposureSec).toLong()
                    exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "1/$denominator")
                }
            }
            
            captureInfo.iso?.let { iso ->
                exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, iso.toString())
                // 也设置 ISO_SPEED 用于兼容性
                exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, iso.toString())
            }
            
            captureInfo.aperture?.let { fNumber ->
                // 光圈值格式：分子/分母
                exif.setAttribute(ExifInterface.TAG_F_NUMBER, formatRational(fNumber))
                exif.setAttribute(ExifInterface.TAG_APERTURE_VALUE, formatApexAperture(fNumber))
            }
            
            captureInfo.focalLength?.let { fl ->
                // 焦距格式：分子/分母 (mm)
                exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, formatRational(fl))
            }
            
            captureInfo.focalLength35mm?.let { fl35 ->
                exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, fl35.toString())
            }
            
            // ========== 白平衡 ==========
            captureInfo.whiteBalance?.let { wb ->
                // 0 = Auto, 1 = Manual
                val whiteBalanceValue = if (wb == 0) 0 else 1
                exif.setAttribute(ExifInterface.TAG_WHITE_BALANCE, whiteBalanceValue.toString())
            }
            
            // ========== 闪光灯 ==========
            captureInfo.flashState?.let { flash ->
                exif.setAttribute(ExifInterface.TAG_FLASH, flash.toString())
            }
            
            // ========== GPS 信息 ==========
            captureInfo.latitude?.let { lat ->
                captureInfo.longitude?.let { lng ->
                    exif.setLatLong(lat, lng)
                }
            }
            captureInfo.altitude?.let { alt ->
                exif.setAltitude(alt)
            }
            
            // ========== 其他标准标签 ==========
            exif.setAttribute(ExifInterface.TAG_COLOR_SPACE, "1") // sRGB
            exif.setAttribute(ExifInterface.TAG_EXIF_VERSION, "0230") // EXIF 2.3
            
            // 保存
            exif.saveAttributes()
            
            Log.d(TAG, "EXIF written to ${file.name}: ISO=${captureInfo.iso}, " +
                    "Exposure=${captureInfo.formatExposureTime()}, " +
                    "Aperture=${captureInfo.formatAperture()}, " +
                    "Focal=${captureInfo.formatFocalLength()}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write EXIF to ${file.name}", e)
        }
    }
    
    /**
     * 格式化为 EXIF 有理数格式（分子/分母）
     */
    private fun formatRational(value: Float): String {
        // 将浮点数转换为分数
        // 例如：1.8 -> 18/10, 2.0 -> 2/1
        val multiplier = 10
        val numerator = (value * multiplier).toInt()
        val denominator = multiplier
        
        // 简化分数
        val gcd = gcd(numerator, denominator)
        return "${numerator / gcd}/${denominator / gcd}"
    }
    
    /**
     * 格式化为 APEX 光圈值
     * APEX Av = 2 * log2(FNumber)
     */
    private fun formatApexAperture(fNumber: Float): String {
        val apex = 2.0 * (kotlin.math.ln(fNumber.toDouble()) / kotlin.math.ln(2.0))
        return formatRational(apex.toFloat())
    }
    
    /**
     * 计算最大公约数
     */
    private fun gcd(a: Int, b: Int): Int {
        var x = abs(a)
        var y = abs(b)
        while (y != 0) {
            val temp = y
            y = x % y
            x = temp
        }
        return if (x == 0) 1 else x
    }
}
