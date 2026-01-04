package com.hinnka.mycamera.utils

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import java.io.IOException

/**
 * 快门音效播放器
 * 
 * 负责播放拍照时的快门音效
 */
class ShutterSoundPlayer(private val context: Context) {
    
    companion object {
        private const val TAG = "ShutterSoundPlayer"
        private const val SHUTTER_SOUND_PATH = "shutter.mp3"
    }
    
    private var mediaPlayer: MediaPlayer? = null
    private var isInitialized = false
    
    init {
        initializePlayer()
    }
    
    /**
     * 初始化 MediaPlayer
     */
    private fun initializePlayer() {
        try {
            mediaPlayer = MediaPlayer().apply {
                val afd = context.assets.openFd(SHUTTER_SOUND_PATH)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                prepare()
                isInitialized = true
            }
            Log.d(TAG, "Shutter sound player initialized")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to initialize shutter sound player", e)
            isInitialized = false
        }
    }
    
    /**
     * 播放快门音效
     */
    fun play() {
        if (!isInitialized) {
            Log.w(TAG, "Player not initialized, skipping playback")
            return
        }
        
        try {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    // 如果正在播放，重新开始
                    player.seekTo(0)
                } else {
                    player.start()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play shutter sound", e)
            // 尝试重新初始化
            release()
            initializePlayer()
        }
    }
    
    /**
     * 释放资源
     */
    fun release() {
        try {
            mediaPlayer?.release()
            mediaPlayer = null
            isInitialized = false
            Log.d(TAG, "Shutter sound player released")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release shutter sound player", e)
        }
    }
}
