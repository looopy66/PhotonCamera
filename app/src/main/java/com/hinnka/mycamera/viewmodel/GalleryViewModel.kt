package com.hinnka.mycamera.viewmodel

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hinnka.mycamera.gallery.GalleryRepository
import com.hinnka.mycamera.gallery.PhotoData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 相册 ViewModel
 * 管理照片列表、选择状态和各种操作
 */
class GalleryViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "GalleryViewModel"
    }
    
    private val repository = GalleryRepository(application)
    
    // 照片列表
    private val _photos = MutableStateFlow<List<PhotoData>>(emptyList())
    val photos: StateFlow<List<PhotoData>> = _photos.asStateFlow()
    
    // 加载状态
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    // 多选模式
    var isSelectionMode by mutableStateOf(false)
        private set
    
    // 选中的照片
    val selectedPhotos = mutableStateListOf<PhotoData>()
    
    // 当前查看的照片索引
    var currentPhotoIndex by mutableStateOf(0)
        private set
    
    // 编辑状态
    var isEditing by mutableStateOf(false)
        private set
    
    // 编辑参数
    var editRotation by mutableFloatStateOf(0f)
        private set
    var editBrightness by mutableFloatStateOf(1f)
        private set
    
    // 最新照片（用于相机界面显示入口）
    private val _latestPhoto = MutableStateFlow<PhotoData?>(null)
    val latestPhoto: StateFlow<PhotoData?> = _latestPhoto.asStateFlow()
    
    init {
        loadPhotos()
    }
    
    /**
     * 加载照片列表
     */
    fun loadPhotos() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _photos.value = repository.getPhotosSync()
                _latestPhoto.value = _photos.value.firstOrNull()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load photos", e)
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * 刷新最新照片
     */
    fun refreshLatestPhoto() {
        viewModelScope.launch {
            _latestPhoto.value = repository.getLatestPhoto()
        }
    }
    
    /**
     * 进入多选模式
     */
    fun enterSelectionMode() {
        isSelectionMode = true
        selectedPhotos.clear()
    }
    
    /**
     * 退出多选模式
     */
    fun exitSelectionMode() {
        isSelectionMode = false
        selectedPhotos.clear()
    }
    
    /**
     * 切换照片选中状态
     */
    fun togglePhotoSelection(photo: PhotoData) {
        if (selectedPhotos.contains(photo)) {
            selectedPhotos.remove(photo)
        } else {
            selectedPhotos.add(photo)
        }
        
        // 如果没有选中的照片，退出多选模式
        if (selectedPhotos.isEmpty()) {
            exitSelectionMode()
        }
    }
    
    /**
     * 全选/取消全选
     */
    fun toggleSelectAll() {
        if (selectedPhotos.size == _photos.value.size) {
            selectedPhotos.clear()
        } else {
            selectedPhotos.clear()
            selectedPhotos.addAll(_photos.value)
        }
    }
    
    /**
     * 设置当前查看的照片
     */
    fun setCurrentPhoto(index: Int) {
        currentPhotoIndex = index.coerceIn(0, (_photos.value.size - 1).coerceAtLeast(0))
    }
    
    /**
     * 获取当前照片
     */
    fun getCurrentPhoto(): PhotoData? {
        return _photos.value.getOrNull(currentPhotoIndex)
    }
    
    /**
     * 删除单张照片
     */
    fun deletePhoto(photo: PhotoData, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val success = repository.deletePhoto(photo)
            if (success) {
                loadPhotos()
            }
            onComplete(success)
        }
    }
    
    /**
     * 删除当前照片
     */
    fun deleteCurrentPhoto(onComplete: (Boolean) -> Unit = {}) {
        getCurrentPhoto()?.let { photo ->
            deletePhoto(photo) { success ->
                if (success && currentPhotoIndex >= _photos.value.size) {
                    currentPhotoIndex = (_photos.value.size - 1).coerceAtLeast(0)
                }
                onComplete(success)
            }
        }
    }
    
    /**
     * 批量删除选中的照片
     */
    fun deleteSelectedPhotos(onComplete: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val toDelete = selectedPhotos.toList()
            val deletedCount = repository.deletePhotos(toDelete)
            exitSelectionMode()
            loadPhotos()
            onComplete(deletedCount)
        }
    }
    
    /**
     * 分享照片
     */
    fun sharePhoto(photo: PhotoData) {
        val context = getApplication<Application>()
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, photo.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
    
    /**
     * 批量分享选中的照片
     */
    fun shareSelectedPhotos() {
        if (selectedPhotos.isEmpty()) return
        
        val context = getApplication<Application>()
        val uris = ArrayList(selectedPhotos.map { it.uri })
        
        val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/jpeg"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
    
    /**
     * 进入编辑模式
     */
    fun enterEditMode() {
        isEditing = true
        editRotation = 0f
        editBrightness = 1f
    }
    
    /**
     * 退出编辑模式
     */
    fun exitEditMode() {
        isEditing = false
        editRotation = 0f
        editBrightness = 1f
    }
    
    /**
     * 旋转90度
     */
    fun rotate90() {
        editRotation = (editRotation + 90f) % 360f
    }
    
    /**
     * 设置亮度
     */
    fun setBrightness(value: Float) {
        editBrightness = value.coerceIn(0.5f, 2f)
    }
    
    /**
     * 保存编辑
     */
    fun saveEdit(photo: PhotoData, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                
                // 读取原图
                val inputStream = context.contentResolver.openInputStream(photo.uri)
                val originalBitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                
                if (originalBitmap == null) {
                    onComplete(false)
                    return@launch
                }
                
                // 应用编辑
                val editedBitmap = withContext(Dispatchers.Default) {
                    applyEdits(originalBitmap, editRotation, editBrightness)
                }
                
                // 保存到新文件
                val filename = "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}_EDIT.jpg"
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/PhotonCamera")
                }
                
                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                )
                
                uri?.let {
                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
                        editedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                    }
                }
                
                originalBitmap.recycle()
                editedBitmap.recycle()
                
                exitEditMode()
                loadPhotos()
                onComplete(true)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save edited photo", e)
                onComplete(false)
            }
        }
    }
    
    /**
     * 应用编辑效果
     */
    private fun applyEdits(bitmap: Bitmap, rotation: Float, brightness: Float): Bitmap {
        val matrix = Matrix()
        
        // 旋转
        if (rotation != 0f) {
            matrix.postRotate(rotation)
        }
        
        var result = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        
        // 亮度调整
        if (brightness != 1f) {
            val adjustedBitmap = Bitmap.createBitmap(result.width, result.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(adjustedBitmap)
            val paint = android.graphics.Paint()
            val colorMatrix = android.graphics.ColorMatrix().apply {
                setScale(brightness, brightness, brightness, 1f)
            }
            paint.colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
            canvas.drawBitmap(result, 0f, 0f, paint)
            if (result != bitmap) {
                result.recycle()
            }
            result = adjustedBitmap
        }
        
        return result
    }
    
    /**
     * 导出照片到公共目录
     */
    fun exportPhoto(photo: PhotoData, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                
                // 读取照片
                val inputStream = context.contentResolver.openInputStream(photo.uri)
                val bytes = inputStream?.readBytes()
                inputStream?.close()
                
                if (bytes == null) {
                    onComplete(false)
                    return@launch
                }
                
                // 保存到 Pictures 目录
                val filename = "PhotonCamera_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                }
                
                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                )
                
                uri?.let {
                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write(bytes)
                    }
                }
                
                onComplete(uri != null)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to export photo", e)
                onComplete(false)
            }
        }
    }
}
