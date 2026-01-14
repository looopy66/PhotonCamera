package com.hinnka.mycamera.frame

import android.content.Context
import android.util.Log
import android.util.LruCache
import com.hinnka.mycamera.utils.PLog

/**
 * 边框管理器
 * 
 * 负责边框模板的加载、缓存和管理
 */
class FrameManager(private val context: Context) {
    
    companion object {
        private const val TAG = "FrameManager"
        private const val CACHE_SIZE = 5
    }
    
    // 模板缓存
    private val templateCache = LruCache<String, FrameTemplate>(CACHE_SIZE)
    
    // 可用边框列表
    private var availableFrames: List<FrameInfo> = emptyList()
    
    /**
     * 初始化，扫描可用的边框模板
     */
    fun initialize() {
        availableFrames = FrameTemplateParser.listAvailableFrames(context)
        PLog.d(TAG, "Found ${availableFrames.size} frame templates")
    }
    
    /**
     * 获取可用的边框列表
     */
    fun getAvailableFrames(): List<FrameInfo> = availableFrames
    
    /**
     * 通过 ID 获取边框信息
     */
    fun getFrameInfo(id: String): FrameInfo? {
        return availableFrames.find { it.id == id }
    }
    
    /**
     * 加载边框模板
     * 
     * @param id 边框 ID
     * @return 边框模板，如果加载失败返回 null
     */
    fun loadTemplate(id: String): FrameTemplate? {
        // 先从缓存查找
        templateCache.get(id)?.let {
            PLog.d(TAG, "Frame template loaded from cache: $id")
            return it
        }
        
        // 查找边框信息
        val frameInfo = getFrameInfo(id) ?: run {
            PLog.e(TAG, "Frame not found: $id")
            return null
        }
        
        // 从文件加载
        return try {
            val template = FrameTemplateParser.parseFromAssets(context, frameInfo.id)
            
            if (template != null) {
                templateCache.put(id, template)
                PLog.d(TAG, "Frame template loaded: $id")
            }
            template
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to load frame template: $id", e)
            null
        }
    }
    
    /**
     * 预加载边框模板
     */
    fun preloadTemplate(id: String) {
        if (templateCache.get(id) != null) {
            return
        }
        
        Thread {
            loadTemplate(id)
        }.start()
    }
    
    /**
     * 清除缓存中的特定模板
     */
    fun evictTemplate(id: String) {
        templateCache.remove(id)
    }
    
    /**
     * 清除所有缓存
     */
    fun clearCache() {
        templateCache.evictAll()
        PLog.d(TAG, "Frame template cache cleared")
    }
    
    /**
     * 获取缓存状态信息
     */
    fun getCacheInfo(): String {
        return "Frame Cache: ${templateCache.size()}/$CACHE_SIZE, hits=${templateCache.hitCount()}, misses=${templateCache.missCount()}"
    }
}
