package com.hinnka.mycamera.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size

/**
 * 相机工具类
 */
object CameraUtils {

    /**
     * 获取固定的预览尺寸
     * 使用固定尺寸可以避免切换画面比例时频繁重新配置相机会话，
     * 不同的画面比例通过 UI 裁切实现
     */
    fun getFixedPreviewSize(
        context: Context,
        cameraId: String
    ): Size {
        val defaultSize = Size(1920, 1080)
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return defaultSize

        val previewSizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList()

        // 优先选择 1920x1080
        val preferred = previewSizes.find { it.width == defaultSize.width && it.height == defaultSize.height }
        if (preferred != null) return preferred

        return previewSizes.filter { it.height >= defaultSize.height }.minByOrNull { it.width } ?: defaultSize
    }
    
    /**
     * 格式化快门速度显示
     */
    fun formatShutterSpeed(exposureTimeNanos: Long): String {
        return when {
            exposureTimeNanos >= 1_000_000_000L -> {
                val seconds = exposureTimeNanos / 1_000_000_000.0
                String.format("%.1f\"", seconds)
            }
            else -> {
                val fraction = (1_000_000_000.0 / exposureTimeNanos).toInt()
                "1/$fraction"
            }
        }
    }
    
    /**
     * 格式化曝光补偿显示
     */
    fun formatExposureCompensation(value: Int, step: Float): String {
        val ev = value * step
        return when {
            ev > 0 -> "+${String.format("%.1f", ev)}"
            ev < 0 -> String.format("%.1f", ev)
            else -> "0"
        }
    }
}
