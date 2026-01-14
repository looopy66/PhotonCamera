package com.hinnka.mycamera.gallery

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.hinnka.mycamera.utils.PLog
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlin.text.toInt

/**
 * 照片元数据
 * 
 * 保存 LUT、边框水印、编辑信息和拍摄参数，用于非破坏性编辑和边框水印渲染
 */
data class PhotoMetadata(
    val version: Int = 3,
    // 编辑配置
    val lutId: String? = null,
    val lutIntensity: Float = 1f,
    val brightness: Float = 1f,
    val rotation: Float = 0f,
    // 边框水印配置
    val frameId: String? = null,
    val showAppBranding: Boolean = true,
    // 图片尺寸
    val width: Int = 0,
    val height: Int = 0,
    // 拍摄信息
    val deviceModel: String? = null,
    val brand: String? = null,
    val dateTaken: Long? = null,
    val location: String? = null,
    val iso: Int? = null,
    val shutterSpeed: String? = null,
    val focalLength: String? = null,
    val focalLength35mm: String? = null,
    val aperture: String? = null,
    val isImported: Boolean = false
) {
    /**
     * 分辨率字符串 (用于边框水印显示)
     */
    val resolution: String
        get() = "${width}x${height}"

    fun toJson(): String {
        return JSONObject().apply {
            put("version", version)
            put("lutId", lutId ?: JSONObject.NULL)
            put("lutIntensity", lutIntensity.toDouble())
            put("brightness", brightness.toDouble())
            put("rotation", rotation.toDouble())
            put("frameId", frameId ?: JSONObject.NULL)
            put("showAppBranding", showAppBranding)
            put("width", width)
            put("height", height)
            // 拍摄信息
            put("deviceModel", deviceModel ?: JSONObject.NULL)
            put("brand", brand ?: JSONObject.NULL)
            put("dateTaken", dateTaken ?: JSONObject.NULL)
            put("location", location ?: JSONObject.NULL)
            put("iso", iso ?: JSONObject.NULL)
            put("shutterSpeed", shutterSpeed ?: JSONObject.NULL)
            put("focalLength", focalLength ?: JSONObject.NULL)
            put("focalLength35mm", focalLength35mm ?: JSONObject.NULL)
            put("aperture", aperture ?: JSONObject.NULL)
            put("isImported", isImported)
        }.toString(2)
    }
    
    companion object {
        private const val TAG = "PhotoMetadata"
        
        fun fromJson(json: String): PhotoMetadata? {
            return try {
                val obj = JSONObject(json)
                PhotoMetadata(
                    version = obj.optInt("version", 1),
                    lutId = if (obj.isNull("lutId")) null else obj.optString("lutId"),
                    lutIntensity = obj.optDouble("lutIntensity", 1.0).toFloat(),
                    brightness = obj.optDouble("brightness", 1.0).toFloat(),
                    rotation = obj.optDouble("rotation", 0.0).toFloat(),
                    frameId = if (obj.isNull("frameId")) null else obj.optString("frameId"),
                    showAppBranding = obj.optBoolean("showAppBranding", true),
                    width = obj.optInt("width", 0),
                    height = obj.optInt("height", 0),
                    // 拍摄信息
                    deviceModel = if (obj.isNull("deviceModel")) null else obj.optString("deviceModel"),
                    brand = if (obj.isNull("brand")) null else obj.optString("brand"),
                    dateTaken = if (obj.isNull("dateTaken")) null else obj.optLong("dateTaken"),
                    location = if (obj.isNull("location")) null else obj.optString("location"),
                    iso = if (obj.isNull("iso")) null else obj.optInt("iso"),
                    shutterSpeed = if (obj.isNull("shutterSpeed")) null else obj.optString("shutterSpeed"),
                    focalLength = if (obj.isNull("focalLength")) null else obj.optString("focalLength"),
                    focalLength35mm = if (obj.isNull("focalLength35mm")) null else obj.optString("focalLength35mm"),
                    aperture = if (obj.isNull("aperture")) null else obj.optString("aperture"),
                    isImported = obj.optBoolean("isImported", false)
                )
            } catch (e: Exception) {
                PLog.e(TAG, "Failed to parse JSON", e)
                null
            }
        }
        
        /**
         * 从系统信息创建默认元数据
         */
        fun createDefault(width: Int, height: Int): PhotoMetadata {
            return PhotoMetadata(
                deviceModel = Build.MODEL,
                brand = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
                dateTaken = System.currentTimeMillis(),
                width = width,
                height = height
            )
        }

        /**
         * 从指定的 URI 加载 EXIF 元数据
         */
        fun fromUri(context: Context, uri: Uri): PhotoMetadata {
            return try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val exif = ExifInterface(inputStream)
                    
                    val model = exif.getAttribute(ExifInterface.TAG_MODEL)
                    val make = exif.getAttribute(ExifInterface.TAG_MAKE)
                    val dateStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                    
                    val iso = exif.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 0).takeIf { it > 0 }
                        ?: exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 }
                    
                    val shutterSpeed = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.let {
                        try {
                            val time = it.toDouble()
                            if (time >= 1.0) {
                                "${time.toInt()}\""
                            } else {
                                "1/${(1.0 / time).toInt()}"
                            }
                        } catch (e: Exception) {
                            it
                        }
                    }
                    
                    val aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 }?.let { "f/${String.format("%.1f", it)}" }
                    val focalLength = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).let {
                        "${it.toInt()}mm"
                    }
                    val focalLength35mm = exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)
                        .takeIf { it > 0 }?.let { "${it}mm" }

                    val width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                        .let { if (it == 0) exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0) else it }
                    val height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                        .let { if (it == 0) exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0) else it }

                    val dateTaken = dateStr?.let {
                        try {
                            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(it)?.time
                        } catch (e: Exception) {
                            null
                        }
                    }

                    PhotoMetadata(
                        deviceModel = model,
                        brand = make?.replaceFirstChar { it.uppercase() },
                        dateTaken = dateTaken,
                        iso = iso,
                        shutterSpeed = shutterSpeed,
                        focalLength = focalLength,
                        focalLength35mm = focalLength35mm,
                        aperture = aperture,
                        width = width,
                        height = height,
                        isImported = true
                    )
                } ?: createDefault(0, 0)
            } catch (e: Exception) {
                PLog.e(TAG, "Failed to load EXIF from $uri", e)
                createDefault(0, 0)
            }
        }
    }
}
