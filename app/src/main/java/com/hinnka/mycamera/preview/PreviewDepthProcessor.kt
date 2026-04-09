package com.hinnka.mycamera.preview

import android.content.Context
import android.graphics.Bitmap
import com.hinnka.mycamera.ml.DepthEstimator
import com.hinnka.mycamera.utils.PLog
import com.hinnka.mycamera.utils.StartupTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Processor for intercepting the real-time preview stream,
 * converting frames to Bitmaps, and feeding them to the DepthEstimator.
 */
class PreviewDepthProcessor(private val context: Context) {
    companion object {
        private const val TAG = "PreviewDepthProcessor"
    }

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var depthEstimator: DepthEstimator? = null
    private var isPrewarming = false
    private var isProcessing = false

    // Expose the latest depth map as a StateFlow for the UI or GL pipeline
    private val _latestDepthMap = MutableStateFlow<Bitmap?>(null)
    val latestDepthMap = _latestDepthMap.asStateFlow()

    fun prewarm() {
        if (depthEstimator != null || isPrewarming) {
            return
        }
        isPrewarming = true
        scope.launch {
            try {
                StartupTrace.mark("PreviewDepthProcessor.prewarm start")
                depthEstimator = DepthEstimator(context)
                StartupTrace.mark("PreviewDepthProcessor.prewarm end")
            } catch (e: Exception) {
                PLog.e(TAG, "Failed to prewarm depth estimator", e)
            } finally {
                isPrewarming = false
            }
        }
    }

    /**
     * Process a bitmap from the GL preview stream.
     * Drops frames if the previous frame is still processing.
     */
    fun processBitmap(bitmap: Bitmap) {
        if (isProcessing) {
            return
        }

        val estimator = depthEstimator
        if (estimator == null) {
            prewarm()
            return
        }

        isProcessing = true
        
        // Process on background thread
        scope.launch {
            try {
                val depthMap = estimator.estimateDepth(bitmap)
                _latestDepthMap.value = depthMap
            } catch (e: Exception) {
                PLog.e(TAG, "Error processing preview bitmap for depth", e)
            } finally {
                isProcessing = false
            }
        }
    }

    fun release() {
        depthEstimator?.close()
        depthEstimator = null
    }
}
