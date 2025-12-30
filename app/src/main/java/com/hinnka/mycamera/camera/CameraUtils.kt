package com.hinnka.mycamera.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 相机工具类
 */
object CameraUtils {
    
    /**
     * 获取所有可用相机信息
     */
    fun getAvailableCameras(context: Context): List<CameraInfo> {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameras = mutableListOf<CameraInfo>()
        
        for (cameraId in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: continue
            
            // 获取物理相机 ID 列表 (Android 9+)
            val physicalCameraIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                characteristics.physicalCameraIds.toList()
            } else {
                emptyList()
            }
            
            // 确定镜头类型
            val lensType = when {
                lensFacing == CameraCharacteristics.LENS_FACING_FRONT -> LensType.FRONT
                else -> LensType.BACK_MAIN // 默认为主摄
            }
            
            // ISO 范围
            val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            
            // 曝光时间范围
            val exposureTimeRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            
            // 曝光补偿范围
            val exposureCompensationRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                ?: android.util.Range(0, 0)
            
            // 曝光补偿步长
            val exposureCompensationStep = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
            
            // 最大变焦
            val maxZoom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper ?: 1f
            } else {
                characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            }
            
            // 传感器方向
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            
            // 活动区域大小
            val activeArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            
            cameras.add(
                CameraInfo(
                    cameraId = cameraId,
                    lensFacing = lensFacing,
                    lensType = lensType,
                    physicalCameraIds = physicalCameraIds,
                    isoRange = isoRange,
                    exposureTimeRange = exposureTimeRange,
                    exposureCompensationRange = exposureCompensationRange,
                    exposureCompensationStep = exposureCompensationStep,
                    maxZoom = maxZoom,
                    sensorOrientation = sensorOrientation,
                    activeArraySize = activeArraySize
                )
            )
        }
        
        return cameras
    }
    
    /**
     * 获取默认后置相机 ID
     */
    fun getDefaultBackCameraId(cameras: List<CameraInfo>): String? {
        return cameras.find { 
            it.lensFacing == CameraCharacteristics.LENS_FACING_BACK 
        }?.cameraId
    }

    /**
     * 获取固定的预览尺寸（16:9）
     * 使用固定尺寸可以避免切换画面比例时频繁重新配置相机会话，
     * 不同的画面比例通过 UI 裁切实现
     */
    fun getFixedPreviewSize(
        context: Context,
        cameraId: String
    ): Size {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(1920, 1080)
        
        val previewSizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList()
        
        // 目标比例 16:9
        val targetRatio = 16f / 9f
        
        // 优先选择 1920x1080
        val preferred = previewSizes.find { it.width == 1920 && it.height == 1080 }
        if (preferred != null) return preferred
        
        // 筛选 16:9 比例的尺寸，选择最接近 1080p 的
        val ratio16by9Sizes = previewSizes.filter { size ->
            val ratio = size.width.toFloat() / size.height
            abs(ratio - targetRatio) < 0.01f
        }
        
        return ratio16by9Sizes
            .minByOrNull { abs(it.height - 1080) }
            ?: previewSizes.firstOrNull()
            ?: Size(1920, 1080)
    }
    
    /**
     * 获取最佳拍照尺寸
     */
    fun getOptimalCaptureSize(
        context: Context,
        cameraId: String,
        targetRatio: Float
    ): Size {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(4032, 3024)
        
        val captureSizes = map.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
        
        // 选择最接近目标比例且分辨率最高的尺寸
        return captureSizes
//            .filter { size ->
//                val ratio = size.width.toFloat() / size.height
//                abs(ratio - targetRatio) < 0.1f
//            }
            .maxByOrNull { it.width * it.height } 
            ?: captureSizes.firstOrNull() 
            ?: Size(4032, 3024)
    }
    
    /**
     * 计算 JPEG 图片的旋转角度
     */
    fun computeImageRotation(sensorOrientation: Int, deviceRotation: Int, isFrontCamera: Boolean): Int {
        return if (isFrontCamera) {
            (sensorOrientation + deviceRotation) % 360
        } else {
            (sensorOrientation - deviceRotation + 360) % 360
        }
    }
    
    /**
     * 计算裁剪区域用于变焦
     */
    fun calculateCropRegion(
        activeArraySize: Rect,
        zoomRatio: Float
    ): Rect {
        val centerX = activeArraySize.centerX()
        val centerY = activeArraySize.centerY()
        
        val newWidth = (activeArraySize.width() / zoomRatio).toInt()
        val newHeight = (activeArraySize.height() / zoomRatio).toInt()
        
        val left = centerX - newWidth / 2
        val top = centerY - newHeight / 2
        val right = centerX + newWidth / 2
        val bottom = centerY + newHeight / 2
        
        return Rect(
            max(0, left),
            max(0, top),
            min(activeArraySize.right, right),
            min(activeArraySize.bottom, bottom)
        )
    }
    
    /**
     * 将屏幕触摸点转换为传感器坐标
     */
    fun convertTouchToSensorCoordinates(
        touchX: Float,
        touchY: Float,
        viewWidth: Int,
        viewHeight: Int,
        sensorArraySize: Rect,
        sensorOrientation: Int,
        isFrontCamera: Boolean
    ): Rect {
        // 归一化触摸坐标
        val normalizedX = touchX / viewWidth
        val normalizedY = touchY / viewHeight
        
        // 根据传感器方向转换坐标
        val (sensorX, sensorY) = when (sensorOrientation) {
            90 -> Pair(normalizedY, 1f - normalizedX)
            180 -> Pair(1f - normalizedX, 1f - normalizedY)
            270 -> Pair(1f - normalizedY, normalizedX)
            else -> Pair(normalizedX, normalizedY)
        }
        
        // 前置摄像头需要水平翻转
        val finalX = if (isFrontCamera) 1f - sensorX else sensorX
        
        // 计算对焦区域（约 10% 的传感器面积）
        val areaSize = (min(sensorArraySize.width(), sensorArraySize.height()) * 0.1f).toInt()
        
        val centerX = (sensorArraySize.left + finalX * sensorArraySize.width()).toInt()
        val centerY = (sensorArraySize.top + sensorY * sensorArraySize.height()).toInt()
        
        return Rect(
            max(sensorArraySize.left, centerX - areaSize / 2),
            max(sensorArraySize.top, centerY - areaSize / 2),
            min(sensorArraySize.right, centerX + areaSize / 2),
            min(sensorArraySize.bottom, centerY + areaSize / 2)
        )
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
