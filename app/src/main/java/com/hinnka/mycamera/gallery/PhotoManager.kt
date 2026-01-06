package com.hinnka.mycamera.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.util.Log
import com.hinnka.mycamera.camera.CaptureInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 照片管理器
 * 
 * 统一管理照片文件、元数据、缩略图等
 * 存储路径: context.filesDir/photos/<photoId>/
 */
object PhotoManager {
    private const val TAG = "PhotoManager"
    private const val PHOTOS_DIR = "photos"
    private const val PHOTO_FILE = "original.jpg"
    private const val METADATA_FILE = "metadata.json"
    private const val THUMBNAIL_FILE = "thumbnail.jpg"
    private const val PREVIEW_FILE = "preview_lut.jpg"

    private fun getPhotosBaseDir(context: Context): File {
        val dir = File(context.filesDir, PHOTOS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun getPhotoDir(context: Context, photoId: String): File {
        val dir = File(getPhotosBaseDir(context), photoId)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getPhotoFile(context: Context, photoId: String): File {
        return File(getPhotoDir(context, photoId), PHOTO_FILE)
    }

    fun getMetadataFile(context: Context, photoId: String): File {
        return File(getPhotoDir(context, photoId), METADATA_FILE)
    }

    fun getThumbnailFile(context: Context, photoId: String): File {
        return File(getPhotoDir(context, photoId), THUMBNAIL_FILE)
    }

    fun getPreviewFile(context: Context, photoId: String): File {
        return File(getPhotoDir(context, photoId), PREVIEW_FILE)
    }

    /**
     * 保存新拍摄的照片
     * 
     * @param context Context
     * @param bitmap 原始 Bitmap 图片
     * @param metadata 编辑元数据（LUT、边框等）
     * @param previewBitmap 预览图（用于生成缩略图）
     * @param captureInfo 拍摄信息（用于写入 EXIF）
     */
    suspend fun savePhoto(
        context: Context, 
        bitmap: Bitmap,
        metadata: PhotoMetadata,
        previewBitmap: Bitmap? = null,
        captureInfo: CaptureInfo? = null
    ): String? {
        return withContext(Dispatchers.IO) {
            try {
                val photoId = UUID.randomUUID().toString()
                val photoDir = getPhotoDir(context, photoId)
                
                // 预先准备所有文件路径
                val photoFile = File(photoDir, PHOTO_FILE)
                val metadataFile = File(photoDir, METADATA_FILE)
                val thumbnailFile = File(photoDir, THUMBNAIL_FILE)
                val previewFile = File(photoDir, PREVIEW_FILE)
                
                // 预先计算元数据（避免在协程中访问可能被回收的 bitmap）
                val metadataWithDimens = metadata.copy(width = bitmap.width, height = bitmap.height)
                val metadataJson = metadataWithDimens.toJson()
                
                // 并行执行所有 IO 操作
                coroutineScope {
                    // 任务 1: 保存原图 + 写入 EXIF
                    val saveOriginalJob = async {
                        FileOutputStream(photoFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                        }
                        // EXIF 必须在原图保存后写入
                        captureInfo?.let { info ->
                            ExifWriter.writeExif(photoFile, info)
                        }
                    }
                    
                    // 任务 2: 保存元数据 JSON
                    val saveMetadataJob = async {
                        metadataFile.writeText(metadataJson)
                    }
                    
                    // 任务 3: 生成并保存缩略图
                    val saveThumbnailJob = async {
                        if (previewBitmap != null) {
                            // 从预览图生成缩略图，更高效
                            saveThumbnail(previewBitmap, thumbnailFile)
                        } else {
                            // 没有预览图时，需要等原图保存完成后再生成
                            saveOriginalJob.await()
                            generateThumbnail(photoFile, thumbnailFile)
                        }
                    }
                    
                    // 任务 4: 保存 LUT 预览图
                    val savePreviewJob = async {
                        if (previewBitmap != null) {
                            FileOutputStream(previewFile).use { out ->
                                previewBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                            }
                        }
                    }
                    
                    // 等待所有任务完成
                    saveOriginalJob.await()
                    saveMetadataJob.await()
                    saveThumbnailJob.await()
                    savePreviewJob.await()
                }

                Log.d(TAG, "Photo saved: $photoId")
                photoId
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save photo", e)
                null
            }
        }
    }

    private fun saveThumbnail(source: Bitmap, targetFile: File) {
        try {
            val targetSize = 512
            val thumbnail = ThumbnailUtils.extractThumbnail(source, targetSize, targetSize)
            FileOutputStream(targetFile).use { out ->
                thumbnail.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            thumbnail.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save thumbnail from source", e)
        }
    }

    /**
     * 生成缩略图
     */
    private fun generateThumbnail(sourceFile: File, targetFile: File) {

        try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(sourceFile.absolutePath, options)
            
            // 计算缩放比例，缩略图大小 512x512
            val targetSize = 512
            options.inSampleSize = calculateInSampleSize(options, targetSize, targetSize)
            options.inJustDecodeBounds = false
            
            val bitmap = BitmapFactory.decodeFile(sourceFile.absolutePath, options)
            if (bitmap != null) {
                val thumbnail = ThumbnailUtils.extractThumbnail(bitmap, targetSize, targetSize)
                FileOutputStream(targetFile).use { out ->
                    thumbnail.compress(Bitmap.CompressFormat.JPEG, 80, out)
                }
                bitmap.recycle()
                thumbnail.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate thumbnail", e)
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /**
     * 获取所有照片 ID 列表（按时间降序）
     */
    fun getPhotoIds(context: Context): List<String> {
        val baseDir = getPhotosBaseDir(context)
        return baseDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.name }
            ?: emptyList()
    }

    /**
     * 删除照片及其所有相关文件
     */
    suspend fun deletePhoto(context: Context, photoId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val photoDir = getPhotoDir(context, photoId)
                if (photoDir.exists()) {
                    photoDir.deleteRecursively()
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete photo: $photoId", e)
                false
            }
        }
    }

    /**
     * 加载元数据
     */
    suspend fun loadMetadata(context: Context, photoId: String): PhotoMetadata? {
        return withContext(Dispatchers.IO) {
            try {
                val file = getMetadataFile(context, photoId)
                if (file.exists()) {
                    PhotoMetadata.fromJson(file.readText())
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load metadata for photo: $photoId", e)
                null
            }
        }
    }

    /**
     * 更新元数据
     */
    suspend fun saveMetadata(context: Context, photoId: String, metadata: PhotoMetadata): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val file = getMetadataFile(context, photoId)
                file.writeText(metadata.toJson())
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save metadata for photo: $photoId", e)
                false
            }
        }
    }
}
