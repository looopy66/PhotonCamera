package com.hinnka.mycamera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/**
 * CameraX 核心控制器
 */
class CameraController(private val context: Context) {
    
    companion object {
        private const val TAG = "CameraController"
    }
    
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var lifecycleOwner: LifecycleOwner? = null
    
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    
    private val _state = MutableStateFlow(CameraState())
    val state: StateFlow<CameraState> = _state.asStateFlow()
    
    // 设备旋转角度（由 ViewModel 更新）
    private var deviceRotation: Int = 0
    
    // Surface 提供者回调
    private var currentSurfaceProvider: Preview.SurfaceProvider? = null
    
    // 图片拍摄回调
    var onImageCaptured: ((ByteArray) -> Unit)? = null
    
    /**
     * 初始化相机
     */
    fun initialize() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            updateAvailableCameras()
        }, ContextCompat.getMainExecutor(context))
    }
    
    /**
     * 更新可用相机列表
     * 使用 Camera2 CameraManager 直接枚举所有相机（包括物理摄像头）
     */
    @SuppressLint("RestrictedApi")
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun updateAvailableCameras() {
        val provider = cameraProvider ?: return
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        
        val cameras = mutableListOf<CameraInfo>()
        val backCameras = mutableListOf<CameraInfo>()
        
        try {
            val cameraIds = cameraManager.cameraIdList
            Log.d(TAG, "CameraManager found ${cameraIds.size} camera IDs: ${cameraIds.toList()}")
            
            for (cameraId in cameraIds) {
                try {
                    val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                    val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: continue
                    
                    // 检查是否是逻辑多摄相机
                    val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    val isLogicalMultiCamera = capabilities?.contains(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
                    ) == true
                    
                    // 获取物理摄像头 ID（Android 9+）
                    val physicalCameraIds = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P && isLogicalMultiCamera) {
                        characteristics.physicalCameraIds.toList()
                    } else {
                        emptyList()
                    }
                    
                    Log.d(TAG, "Camera $cameraId: facing=$lensFacing, logical=$isLogicalMultiCamera, physicalIds=$physicalCameraIds")
                    
                    if (isLogicalMultiCamera && physicalCameraIds.isNotEmpty()) {
                        // 对于逻辑多摄相机，枚举其物理摄像头
                        for (physicalId in physicalCameraIds) {
                            try {
                                val physicalCharacteristics = cameraManager.getCameraCharacteristics(physicalId)
                                val info = createCameraInfoFromCharacteristics(
                                    physicalId,
                                    physicalCharacteristics,
                                    lensFacing,
                                    cameraId // 记录逻辑相机 ID
                                )
                                if (info != null) {
                                    if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                                        backCameras.add(info)
                                    } else if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                                        cameras.add(info.copy(lensType = LensType.FRONT))
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to get physical camera $physicalId characteristics", e)
                            }
                        }
                    } else {
                        // 非逻辑多摄相机，直接添加
                        val info = createCameraInfoFromCharacteristics(
                            cameraId,
                            characteristics,
                            lensFacing,
                            null
                        )
                        if (info != null) {
                            if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                                backCameras.add(info)
                            } else if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                                cameras.add(info.copy(lensType = LensType.FRONT))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get camera $cameraId characteristics", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enumerate cameras", e)
            // 回退到 CameraX 方式
            updateAvailableCamerasLegacy()
            return
        }
        
        // 根据焦距对后置摄像头分类
        val classifiedBackCameras = classifyBackCameras(backCameras)
        cameras.addAll(classifiedBackCameras)
        
        // 如果没有通过物理摄像头找到多个，尝试使用 CameraX 方式
        if (cameras.isEmpty()) {
            Log.w(TAG, "No cameras found via CameraManager, falling back to CameraX")
            updateAvailableCamerasLegacy()
            return
        }
        
        Log.d(TAG, "Found ${cameras.size} cameras (${backCameras.size} back cameras):")
        cameras.forEach { cam ->
            Log.d(TAG, "  - ${cam.cameraId}: ${cam.lensType}, focal=${cam.focalLength}mm, 35mm=${cam.focalLength35mmEquivalent}mm")
        }
        
        val defaultCameraId = cameras.firstOrNull { it.lensType == LensType.BACK_MAIN }?.cameraId 
            ?: cameras.firstOrNull { it.lensFacing == CameraCharacteristics.LENS_FACING_BACK }?.cameraId
            ?: cameras.firstOrNull()?.cameraId ?: ""
        
        val defaultCamera = cameras.find { it.cameraId == defaultCameraId }
        
        _state.value = _state.value.copy(
            availableCameras = cameras,
            currentCameraId = defaultCameraId,
            currentLensType = defaultCamera?.lensType ?: LensType.BACK_MAIN
        )
    }
    
    /**
     * 从 CameraCharacteristics 创建 CameraInfo
     */
    private fun createCameraInfoFromCharacteristics(
        cameraId: String,
        characteristics: android.hardware.camera2.CameraCharacteristics,
        lensFacing: Int,
        logicalCameraId: String?
    ): CameraInfo? {
        return try {
            // 获取焦距信息
            val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val focalLength = focalLengths?.firstOrNull() ?: 0f
            
            // 计算 35mm 等效焦距
            val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focalLength35mm = if (sensorSize != null && focalLength > 0) {
                focalLength * (36f / sensorSize.width)
            } else {
                0f
            }
            
            // ISO 范围
            val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            
            // 曝光时间范围
            val exposureTimeRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            
            // 曝光补偿范围
            val exposureCompensationRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                ?: Range(0, 0)
            
            // 曝光补偿步长
            val exposureCompensationStep = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
            
            // 最大数字变焦
            val maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            
            // 传感器方向
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            
            // 活动区域大小
            val activeArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            
            Log.d(TAG, "Camera $cameraId: focal=$focalLength, 35mm=$focalLength35mm, sensor=${sensorSize?.width}x${sensorSize?.height}")
            
            CameraInfo(
                cameraId = if (logicalCameraId != null) "$logicalCameraId:$cameraId" else cameraId,
                lensFacing = lensFacing,
                lensType = LensType.BACK_MAIN, // 临时，后续分类
                physicalCameraIds = if (logicalCameraId != null) listOf(cameraId) else emptyList(),
                isoRange = isoRange,
                exposureTimeRange = exposureTimeRange,
                exposureCompensationRange = exposureCompensationRange,
                exposureCompensationStep = exposureCompensationStep,
                maxZoom = maxZoom,
                sensorOrientation = sensorOrientation,
                activeArraySize = activeArraySize,
                focalLength = focalLength,
                focalLength35mmEquivalent = focalLength35mm
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create camera info for $cameraId", e)
            null
        }
    }
    
    /**
     * 使用 CameraX 方式获取相机列表（回退方案）
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun updateAvailableCamerasLegacy() {
        val provider = cameraProvider ?: return
        val cameras = mutableListOf<CameraInfo>()
        val backCameras = mutableListOf<CameraInfo>()
        
        for (cameraInfo in provider.availableCameraInfos) {
            val camera2Info = Camera2CameraInfo.from(cameraInfo)
            val cameraId = camera2Info.cameraId
            val lensFacing = camera2Info.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
                ?: continue
            
            val focalLengths = camera2Info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val focalLength = focalLengths?.firstOrNull() ?: 0f
            
            val sensorSize = camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focalLength35mm = if (sensorSize != null && focalLength > 0) {
                focalLength * (36f / sensorSize.width)
            } else {
                0f
            }
            
            val info = createCameraInfoFromCamera2(cameraInfo, camera2Info, LensType.BACK_MAIN, focalLength, focalLength35mm)
            
            if (info != null) {
                if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                    cameras.add(info.copy(lensType = LensType.FRONT))
                } else if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                    backCameras.add(info)
                }
            }
        }
        
        val classifiedBackCameras = classifyBackCameras(backCameras)
        cameras.addAll(classifiedBackCameras)
        
        Log.d(TAG, "Legacy: Found ${cameras.size} cameras")
        
        val defaultCameraId = cameras.firstOrNull { it.lensType == LensType.BACK_MAIN }?.cameraId 
            ?: cameras.firstOrNull()?.cameraId ?: ""
        val defaultCamera = cameras.find { it.cameraId == defaultCameraId }
        
        _state.value = _state.value.copy(
            availableCameras = cameras,
            currentCameraId = defaultCameraId,
            currentLensType = defaultCamera?.lensType ?: LensType.BACK_MAIN
        )
    }
    
    /**
     * 根据焦距对后置摄像头进行分类
     */
    private fun classifyBackCameras(cameras: List<CameraInfo>): List<CameraInfo> {
        if (cameras.isEmpty()) return emptyList()
        if (cameras.size == 1) {
            return listOf(cameras.first().copy(lensType = LensType.BACK_MAIN))
        }
        
        // 按 35mm 等效焦距排序
        val sorted = cameras.sortedBy { it.focalLength35mmEquivalent }
        
        return sorted.mapIndexed { index, camera ->
            val lensType = when {
                // 如果只有两个摄像头
                sorted.size == 2 -> {
                    if (index == 0) LensType.BACK_ULTRA_WIDE else LensType.BACK_MAIN
                }
                // 三个或更多摄像头
                else -> {
                    when (index) {
                        0 -> LensType.BACK_ULTRA_WIDE
                        sorted.size - 1 -> LensType.BACK_TELEPHOTO
                        else -> LensType.BACK_MAIN
                    }
                }
            }
            camera.copy(lensType = lensType)
        }
    }
    
    /**
     * 从 Camera2 信息创建 CameraInfo
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun createCameraInfoFromCamera2(
        cameraInfo: androidx.camera.core.CameraInfo,
        camera2Info: Camera2CameraInfo,
        lensType: LensType,
        focalLength: Float,
        focalLength35mm: Float
    ): CameraInfo? {
        return try {
            val cameraId = camera2Info.cameraId
            val lensFacing = camera2Info.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
                ?: CameraCharacteristics.LENS_FACING_BACK
            
            // ISO 范围
            val isoRange = camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            
            // 曝光时间范围
            val exposureTimeRange = camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            
            // 曝光补偿范围
            val exposureCompensationRange = cameraInfo.exposureState.exposureCompensationRange
            
            // 曝光补偿步长
            val exposureCompensationStep = cameraInfo.exposureState.exposureCompensationStep.toFloat()
            
            // 最大变焦
            val maxZoom = cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
            
            // 传感器方向
            val sensorOrientation = camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            
            // 活动区域大小
            val activeArraySize = camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            
            CameraInfo(
                cameraId = cameraId,
                lensFacing = lensFacing,
                lensType = lensType,
                physicalCameraIds = emptyList(),
                isoRange = isoRange,
                exposureTimeRange = exposureTimeRange,
                exposureCompensationRange = Range(exposureCompensationRange.lower, exposureCompensationRange.upper),
                exposureCompensationStep = exposureCompensationStep,
                maxZoom = maxZoom,
                sensorOrientation = sensorOrientation,
                activeArraySize = activeArraySize,
                focalLength = focalLength,
                focalLength35mmEquivalent = focalLength35mm
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create camera info from Camera2", e)
            null
        }
    }

    
    /**
     * 绑定相机到生命周期
     * 使用当前 cameraId 选择特定的摄像头
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun bindCamera(lifecycleOwner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider) {
        this.lifecycleOwner = lifecycleOwner
        this.currentSurfaceProvider = surfaceProvider
        
        val provider = cameraProvider ?: run {
            Log.e(TAG, "CameraProvider is null")
            return
        }
        
        try {
            // 解绑之前的用例
            provider.unbindAll()
            
            val currentCameraId = _state.value.currentCameraId
            val currentLensType = _state.value.currentLensType
            
            // 解析 cameraId，可能是 "逻辑相机ID:物理相机ID" 格式
            val (logicalCameraId, physicalCameraId) = if (currentCameraId.contains(":")) {
                val parts = currentCameraId.split(":")
                Pair(parts[0], parts[1])
            } else {
                Pair(currentCameraId, null)
            }
            
            Log.d(TAG, "Binding camera: logical=$logicalCameraId, physical=$physicalCameraId, type=$currentLensType")
            
            // 根据逻辑相机 ID 创建选择器
            val cameraSelector = CameraSelector.Builder()
                .addCameraFilter { cameraInfoList ->
                    cameraInfoList.filter { cameraInfo ->
                        val camera2Info = Camera2CameraInfo.from(cameraInfo)
                        camera2Info.cameraId == logicalCameraId
                    }
                }
                .build()
            
            // 创建预览用例
            val previewBuilder = Preview.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                android.util.Size(1920, 1080),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER
                            )
                        )
                        .build()
                )
            
            // 如果有物理相机 ID，使用 Camera2Interop 设置
            if (physicalCameraId != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                androidx.camera.camera2.interop.Camera2Interop.Extender(previewBuilder)
                    .setPhysicalCameraId(physicalCameraId)
            }
            
            preview = previewBuilder.build().also {
                it.surfaceProvider = surfaceProvider
            }
            
            // 创建拍照用例
            val imageCaptureBuilder = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(95)
            
            // 如果有物理相机 ID，拍照也使用该物理相机
            if (physicalCameraId != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                androidx.camera.camera2.interop.Camera2Interop.Extender(imageCaptureBuilder)
                    .setPhysicalCameraId(physicalCameraId)
            }
            
            imageCapture = imageCaptureBuilder.build()
            
            // 绑定用例到生命周期
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )
            
            // 更新相机信息
            updateCameraInfoFromBoundCamera()
            
            _state.value = _state.value.copy(isPreviewActive = true)
            Log.d(TAG, "Camera bound successfully: ${currentLensType}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind camera", e)
        }
    }
    
    /**
     * 从已绑定的相机更新信息
     */
    @SuppressLint("RestrictedApi")
    private fun updateCameraInfoFromBoundCamera() {
        val cam = camera ?: return
        val cameraInfo = cam.cameraInfo

        // 观察变焦状态
        cameraInfo.zoomState.observeForever { zoomState ->
            val currentState = _state.value
            val currentCameraId = currentState.currentCameraId
            
            val minZoom = zoomState.minZoomRatio
            val maxZoom = zoomState.maxZoomRatio
            
            // 计算变焦档位
            val zoomSteps = calculateZoomSteps(minZoom, maxZoom)
            
            Log.d(TAG, "Camera zoom range: $minZoom - $maxZoom, steps: $zoomSteps")
            
            val updatedCameras = currentState.availableCameras.map { info ->
                if (info.cameraId == currentCameraId) {
                    info.copy(
                        maxZoom = maxZoom,
                        minZoom = minZoom,
                        zoomSteps = zoomSteps
                    )
                } else {
                    info
                }
            }
            _state.value = currentState.copy(availableCameras = updatedCameras)
        }
    }
    
    /**
     * 计算变焦档位
     * 根据 minZoom 和 maxZoom 生成常用的变焦档位（如 0.5x, 1x, 2x, 3x 等）
     */
    private fun calculateZoomSteps(minZoom: Float, maxZoom: Float): List<Float> {
        val steps = mutableListOf<Float>()
        
        // 广角 (0.5x, 0.6x)
        if (minZoom <= 0.5f) steps.add(0.5f)
        else if (minZoom <= 0.6f) steps.add(0.6f)
        
        // 主摄 (1x)
        steps.add(1f)
        
        // 长焦档位
        if (maxZoom >= 2f) steps.add(2f)
        if (maxZoom >= 3f) steps.add(3f)
        if (maxZoom >= 5f) steps.add(5f)
        if (maxZoom >= 10f) steps.add(10f)
        
        return steps.filter { it >= minZoom && it <= maxZoom }
    }
    
    /**
     * 打开相机（兼容旧接口，通过 SurfaceTexture 创建 SurfaceProvider）
     */
    fun openCamera(surface: Surface) {
        Log.w(TAG, "openCamera(Surface) is deprecated. Use bindCamera(LifecycleOwner, SurfaceProvider) instead.")
        // 此方法保留用于兼容，实际使用 bindCamera
    }
    
    /**
     * 设置曝光补偿
     */
    fun setExposureCompensation(value: Int) {
        val range = _state.value.getExposureCompensationRange()
        val clampedValue = value.coerceIn(range.lower, range.upper)
        _state.value = _state.value.copy(exposureCompensation = clampedValue)
        
        camera?.cameraControl?.setExposureCompensationIndex(clampedValue)
    }
    
    /**
     * 设置自动曝光模式
     */
    fun setAutoExposure(enabled: Boolean) {
        _state.value = _state.value.copy(isAutoExposure = enabled)
        updateManualExposure()
    }
    
    /**
     * 设置 ISO
     */
    fun setIso(value: Int) {
        val range = _state.value.getIsoRange()
        val clampedValue = value.coerceIn(range.lower, range.upper)
        _state.value = _state.value.copy(
            iso = clampedValue,
            isAutoExposure = false
        )
        updateManualExposure()
    }
    
    /**
     * 设置快门速度
     */
    fun setShutterSpeed(value: Long) {
        val range = _state.value.getShutterSpeedRange()
        val clampedValue = value.coerceIn(range.lower, range.upper)
        _state.value = _state.value.copy(
            shutterSpeed = clampedValue,
            isAutoExposure = false
        )
        updateManualExposure()
    }
    
    /**
     * 更新手动曝光设置（使用 Camera2 Interop）
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun updateManualExposure() {
        val cam = camera ?: return
        val camera2Control = Camera2CameraControl.from(cam.cameraControl)
        val state = _state.value
        
        val optionsBuilder = CaptureRequestOptions.Builder()
        
        if (!state.isAutoExposure) {
            // 手动曝光模式
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CameraMetadata.CONTROL_AE_MODE_OFF
            )
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.SENSOR_SENSITIVITY,
                state.iso
            )
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.SENSOR_EXPOSURE_TIME,
                state.shutterSpeed
            )
        } else {
            // 自动曝光模式
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CameraMetadata.CONTROL_AE_MODE_ON
            )
        }
        
        camera2Control.captureRequestOptions = optionsBuilder.build()
    }
    
    /**
     * 设置变焦倍数
     * 支持小于 1.0 的值（广角模式）
     */
    fun setZoomRatio(ratio: Float) {
        val minZoom = _state.value.getMinZoom()
        val maxZoom = _state.value.getMaxZoom()
        val clampedRatio = ratio.coerceIn(minZoom, maxZoom)
        _state.value = _state.value.copy(zoomRatio = clampedRatio)
        
        camera?.cameraControl?.setZoomRatio(clampedRatio)
        Log.d(TAG, "setZoomRatio: $ratio -> $clampedRatio (range: $minZoom - $maxZoom)")
    }
    
    /**
     * 设置到指定的变焦档位
     * @param step 变焦档位值（如 0.5, 1.0, 2.0）
     */
    fun setZoomStep(step: Float) {
        setZoomRatio(step)
    }
    
    /**
     * 获取变焦档位列表
     */
    fun getZoomSteps(): List<Float> {
        return _state.value.getZoomSteps()
    }
    
    /**
     * 设置画面比例
     */
    fun setAspectRatio(ratio: AspectRatio) {
        _state.value = _state.value.copy(aspectRatio = ratio)
    }
    
    /**
     * 设置设备旋转角度
     * @param degrees 旋转角度，例如 0, 90, 180, 270
     */
    fun setDeviceRotation(degrees: Int) {
        deviceRotation = degrees
        // CameraX 会自动处理旋转，但我们仍需保存用于拍照时的方向
        imageCapture?.targetRotation = when (degrees) {
            90 -> Surface.ROTATION_270
            180 -> Surface.ROTATION_180
            270 -> Surface.ROTATION_90
            else -> Surface.ROTATION_0
        }
    }
    
    /**
     * 设置 LUT 强度
     */
    fun setLutIntensity(intensity: Float) {
        _state.value = _state.value.copy(lutIntensity = intensity)
    }
    
    /**
     * 点击对焦
     */
    fun focusOnPoint(x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        val cam = camera ?: return
        
        _state.value = _state.value.copy(
            focusPoint = Pair(x / viewWidth, y / viewHeight),
            isFocusing = true,
            focusSuccess = null
        )
        
        // 使用 CameraX 的 MeteringPointFactory 和 FocusMeteringAction
        val factory = SurfaceOrientedMeteringPointFactory(viewWidth.toFloat(), viewHeight.toFloat())
        val point = factory.createPoint(x, y)
        
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        
        cam.cameraControl.startFocusAndMetering(action).addListener({
            try {
                val result = cam.cameraControl.startFocusAndMetering(action).get()
                _state.value = _state.value.copy(
                    isFocusing = false,
                    focusSuccess = result.isFocusSuccessful
                )
            } catch (e: Exception) {
                Log.e(TAG, "Focus failed", e)
                _state.value = _state.value.copy(isFocusing = false, focusSuccess = false)
            }
        }, ContextCompat.getMainExecutor(context))
    }
    
    /**
     * 切换摄像头（前后置切换）
     * 如果当前是前置，切换到主摄
     * 如果当前是后置，切换到前置
     */
    fun switchCamera() {
        val cameras = _state.value.availableCameras
        val currentLensType = _state.value.currentLensType
        
        val nextLensType = if (currentLensType == LensType.FRONT) {
            LensType.BACK_MAIN
        } else {
            LensType.FRONT
        }
        
        val nextCamera = cameras.find { it.lensType == nextLensType }
        
        nextCamera?.let { camera ->
            _state.value = _state.value.copy(
                currentCameraId = camera.cameraId,
                currentLensType = camera.lensType,
                zoomRatio = 1f // 重置变焦
            )
            
            // 重新绑定相机
            rebindCamera()
        }
    }
    
    /**
     * 切换到指定的镜头类型
     * @param lensType 目标镜头类型
     */
    fun switchToLens(lensType: LensType) {
        val cameras = _state.value.availableCameras
        val currentLensType = _state.value.currentLensType
        
        if (currentLensType == lensType) return
        
        val targetCamera = cameras.find { it.lensType == lensType }
        
        targetCamera?.let { camera ->
            Log.d(TAG, "Switching to lens: ${lensType}, cameraId: ${camera.cameraId}")
            _state.value = _state.value.copy(
                currentCameraId = camera.cameraId,
                currentLensType = camera.lensType,
                zoomRatio = 1f // 重置变焦
            )
            
            // 重新绑定相机
            rebindCamera()
        } ?: Log.w(TAG, "Camera with lens type $lensType not found")
    }
    
    /**
     * 切换到下一个后置摄像头
     * 按顺序循环切换：广角 -> 主摄 -> 长焦 -> 广角
     */
    fun switchBackCamera() {
        val cameras = _state.value.availableCameras
        val currentLensType = _state.value.currentLensType
        
        // 如果当前是前置，先切换到主摄
        if (currentLensType == LensType.FRONT) {
            switchToLens(LensType.BACK_MAIN)
            return
        }
        
        // 获取所有后置摄像头并按类型排序
        val backCameras = cameras.filter { it.lensFacing == CameraCharacteristics.LENS_FACING_BACK }
            .sortedBy { 
                when (it.lensType) {
                    LensType.BACK_ULTRA_WIDE -> 0
                    LensType.BACK_MAIN -> 1
                    LensType.BACK_TELEPHOTO -> 2
                    else -> 3
                }
            }
        
        if (backCameras.isEmpty()) return
        
        // 找到当前摄像头的索引
        val currentIndex = backCameras.indexOfFirst { it.lensType == currentLensType }
        val nextIndex = (currentIndex + 1) % backCameras.size
        val nextCamera = backCameras[nextIndex]
        
        Log.d(TAG, "Switching back camera from $currentLensType to ${nextCamera.lensType}")
        
        _state.value = _state.value.copy(
            currentCameraId = nextCamera.cameraId,
            currentLensType = nextCamera.lensType,
            zoomRatio = 1f
        )
        
        rebindCamera()
    }
    
    /**
     * 获取所有后置摄像头
     */
    fun getBackCameras(): List<CameraInfo> {
        return _state.value.availableCameras.filter { 
            it.lensFacing == CameraCharacteristics.LENS_FACING_BACK 
        }
    }
    
    /**
     * 重新绑定相机
     */
    private fun rebindCamera() {
        lifecycleOwner?.let { owner ->
            currentSurfaceProvider?.let { provider ->
                bindCamera(owner, provider)
            }
        }
    }
    
    /**
     * 拍照
     */
    fun capture() {
        val imageCapture = imageCapture ?: return
        
        _state.value = _state.value.copy(isCapturing = true)
        
        imageCapture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()
                    
                    _state.value = _state.value.copy(isCapturing = false)
                    onImageCaptured?.invoke(bytes)
                }
                
                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Capture failed", exception)
                    _state.value = _state.value.copy(isCapturing = false)
                }
            }
        )
    }
    
    /**
     * 检查相机状态并在必要时恢复
     * 用于从后台切换回 App 时调用
     */
    fun checkAndRecoverCamera() {
        // CameraX 自动处理生命周期，一般不需要手动恢复
        // 但如果预览不活跃，尝试重新绑定
        if (!_state.value.isPreviewActive) {
            Log.d(TAG, "Preview not active, attempting to recover...")
            lifecycleOwner?.let { owner ->
                currentSurfaceProvider?.let { provider ->
                    bindCamera(owner, provider)
                }
            }
        }
    }
    
    /**
     * 关闭相机
     */
    fun closeCamera() {
        try {
            cameraProvider?.unbindAll()
            camera = null
            preview = null
            imageCapture = null
            _state.value = _state.value.copy(isPreviewActive = false)
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        }
    }
    
    /**
     * 释放资源
     */
    fun release() {
        closeCamera()
        cameraExecutor.shutdown()
    }
}
