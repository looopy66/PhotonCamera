package com.hinnka.mycamera.gallery

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * 相册数据仓库
 * 负责从 MediaStore 读取和管理 PhotonCamera 拍摄的照片
 */
class GalleryRepository(private val context: Context) {
    
    companion object {
        private const val TAG = "GalleryRepository"
        private const val PHOTON_CAMERA_PATH = "DCIM/PhotonCamera"
    }
    
    /**
     * 获取所有照片（按时间倒序）
     */
    fun getPhotos(): Flow<List<PhotoData>> = flow {
        emit(queryPhotos())
    }.flowOn(Dispatchers.IO)
    
    /**
     * 同步获取所有照片
     */
    suspend fun getPhotosSync(): List<PhotoData> = withContext(Dispatchers.IO) {
        queryPhotos()
    }
    
    /**
     * 获取最新的一张照片
     */
    suspend fun getLatestPhoto(): PhotoData? = withContext(Dispatchers.IO) {
        queryPhotos().firstOrNull()
    }
    
    /**
     * 删除单张照片
     */
    suspend fun deletePhoto(photo: PhotoData): Boolean = withContext(Dispatchers.IO) {
        try {
            val deletedRows = context.contentResolver.delete(photo.uri, null, null)
            deletedRows > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete photo", e)
            false
        }
    }
    
    /**
     * 批量删除照片
     */
    suspend fun deletePhotos(photos: List<PhotoData>): Int = withContext(Dispatchers.IO) {
        var deletedCount = 0
        photos.forEach { photo ->
            if (deletePhoto(photo)) {
                deletedCount++
            }
        }
        deletedCount
    }
    
    /**
     * 查询 PhotonCamera 拍摄的照片
     */
    private fun queryPhotos(): List<PhotoData> {
        val photos = mutableListOf<PhotoData>()
        
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.RELATIVE_PATH
        )
        
        // 只查询 DCIM/PhotonCamera 目录下的照片
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("$PHOTON_CAMERA_PATH%")
        
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        
        try {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val displayNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val dateTakenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val displayName = cursor.getString(displayNameColumn)
                    val dateAdded = cursor.getLong(dateAddedColumn)
                    val dateTaken = cursor.getLong(dateTakenColumn).takeIf { it > 0 }
                    val size = cursor.getLong(sizeColumn)
                    val width = cursor.getInt(widthColumn)
                    val height = cursor.getInt(heightColumn)
                    
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    
                    photos.add(
                        PhotoData(
                            id = id,
                            uri = contentUri,
                            displayName = displayName,
                            dateAdded = dateAdded,
                            dateTaken = dateTaken,
                            size = size,
                            width = width,
                            height = height
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query photos", e)
        }
        
        return photos
    }
}
