package com.hinnka.mycamera.livephoto

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.*
import android.view.Surface
import com.hinnka.mycamera.lut.LutConfig
import com.hinnka.mycamera.model.ColorRecipeParams
import com.hinnka.mycamera.utils.PLog
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * Live Photo 录制管理器 (重构版 - 解决 OOM)
 *
 * 采用“实时编码+样本缓冲”方案：
 * 1. 持续将预览帧发送到硬件编码器 (MediaCodec)。
 * 2. 缓冲编码后的压缩数据 (H.264 Samples) 而非原始像素。
 * 3. 内存占用从数百 MB 降低到约 2-5 MB。
 */
class LivePhotoRecorder(
    private val context: Context,
    private val bufferDurationMs: Long = 1500L,
    private val postCaptureDurationMs: Long = 1500L,
    private val frameRateHz: Int = 30
) {
    companion object {
        private const val TAG = "LivePhotoRecorder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val BITRATE = 8_000_000
        private const val I_FRAME_INTERVAL = 1
    }

    // 核心组件
    // 缓冲区大小增加一部分裕量 (如增加 2000ms)，以确保在裁剪时能找到前置关键帧
    private val circularRecorder = CircularVideoRecorder(bufferDurationMs + postCaptureDurationMs + 2000L)

    @Volatile
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null

    @Volatile
    private var encoderFormat: MediaFormat? = null

    // 渲染组件 (负责将纹理绘制到编码器 Surface)
    private var lutRenderer: HardwareLutVideoRenderer? = null

    // 状态控制
    @Volatile
    private var isRunning = false
    private var isCapturing = false
    private var snapshotTimestampUs: Long = 0

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var drainJob: Job? = null
    private val renderDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private var lastFrameTimestampUs: Long = 0
    private var lastSharedContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var pendingRelease = false

    /**
     * 更新实时编码器的配置（如 LUT 或色彩配方）
     */
    fun updateConfig(lutConfig: LutConfig?, params: ColorRecipeParams?) {
        lutRenderer?.updateConfig(lutConfig, params)
    }

    /**
     * 开始录制 (当实况照片模式开启时调用)
     */
    fun startRecording() {
        if (isRunning) return
        circularRecorder.startRecording()
        isRunning = true
        lastFrameTimestampUs = 0
        PLog.d(TAG, "Live Photo recording prepared")
    }

    /**
     * 停止录制 (当实况照片模式关闭或相机关闭时调用)
     */
    fun stopRecording() {
        isRunning = false
        circularRecorder.stopRecording()
        if (isCapturing) {
            PLog.d(TAG, "Stop requested while capture in progress, delaying encoder release")
            pendingRelease = true
        } else {
            release()
        }
        PLog.d(TAG, "Live Photo recording stopped")
    }

    /**
     * 处理预览帧 (GL 线程调用)
     */
    fun onPreviewFrame(
        textureId: Int,
        transformMatrix: FloatArray,
        width: Int,
        height: Int,
        timestampNs: Long,
        lutConfig: LutConfig?,
        params: ColorRecipeParams?,
        sharedContext: EGLContext,
        sharedDisplay: EGLDisplay
    ) {
        if (!isRunning) return

        lastFrameTimestampUs = timestampNs / 1000

        val matrix = transformMatrix.clone()
        scope.launch(renderDispatcher) {
            // 如果上下文发生了变化（比如 App 重入），必须释放旧编码器并重新创建
            if (encoder != null && lastSharedContext != EGL14.EGL_NO_CONTEXT && lastSharedContext != sharedContext) {
                PLog.w(TAG, "Shared EGL Context changed, forcing encoder re-init.")
                internalRelease()
            }

            // 延迟初始化编码器
            if (encoder == null && sharedContext != EGL14.EGL_NO_CONTEXT) {
                initEncoder(width, height, lutConfig, params, sharedContext, sharedDisplay)
                lastSharedContext = sharedContext
                pendingRelease = false
            }
            
            if (!isRunning) return@launch

            lutRenderer?.let { renderer ->
                renderer.renderFrame(textureId, matrix, timestampNs / 1000)
            }
        }
    }

    private fun initEncoder(
        width: Int,
        height: Int,
        lutConfig: LutConfig?,
        params: ColorRecipeParams?,
        sharedContext: EGLContext,
        sharedDisplay: EGLDisplay
    ) {
        try {
            var w = width
            var h = height

            // Ensure even dimensions
            if (w % 2 != 0) w--
            if (h % 2 != 0) h--

            val format = MediaFormat.createVideoFormat(MIME_TYPE, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRateHz)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            }

            encoder = MediaCodec.createEncoderByType(MIME_TYPE).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = createInputSurface()
                start()
            }

            lutRenderer = HardwareLutVideoRenderer(w, h, lutConfig, params).apply {
                initialize(inputSurface!!, sharedContext, sharedDisplay)
            }

            startDraining()
            PLog.d(TAG, "Encoder initialized: ${w}x${h}")
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to init encoder", e)
        }
    }

    /**
     * 触发快照 (拍照时调用)
     */
    fun snapshot(): List<CircularVideoRecorder.Sample> {
        if (!isRunning) return emptyList()

        // 使用最近一帧的时间戳作为基准，避免时钟源不一致问题
        snapshotTimestampUs = lastFrameTimestampUs
        if (snapshotTimestampUs == 0L) {
            snapshotTimestampUs = System.nanoTime() / 1000
        }

        isCapturing = true
        PLog.d(TAG, "Snapshot triggered at $snapshotTimestampUs")
        return circularRecorder.snapshot()
    }

    /**
     * 完成并生成视频 (拍照后延迟调用)
     * @param imageTimestampUs 可选的图像精确时间戳（纳秒/1000），若不传则使用 snapshot 时的预览时间戳
     */
    fun recordVideo(
        imageTimestampUs: Long? = null,
        onCaptured: (File, Long) -> Unit
    ) {
        scope.launch {
            try {
                // 等待后半段录制 + 额外 500ms 缓冲时间确保编码器完成工作
                delay(postCaptureDurationMs + 500L)

                val currentSamples = circularRecorder.snapshot()
                if (currentSamples.isEmpty()) {
                    PLog.e(TAG, "No samples in buffer")
                    isCapturing = false
                    return@launch
                }

                // 中心时间点
                val centerTs = imageTimestampUs ?: snapshotTimestampUs
                
                // 目标时间范围：[中心前 1.5s, 中心后 1.5s]
                var startTimeUs = centerTs - bufferDurationMs * 1000
                val endTimeUs = centerTs + postCaptureDurationMs * 1000

                // 安全检查：如果中心点完全超出了缓冲区（可能由于长曝光导致延迟太久被裁掉了）
                // 则取缓冲区中最新的部分
                val firstSampleTs = currentSamples.first().info.presentationTimeUs
                val lastSampleTs = currentSamples.last().info.presentationTimeUs
                
                if (centerTs < firstSampleTs || centerTs > lastSampleTs) {
                    PLog.w(TAG, "Center timestamp $centerTs out of buffer range [$firstSampleTs, $lastSampleTs]. Fallback to latest available.")
                    // 如果落后太多，取最后 3s
                    startTimeUs = lastSampleTs - (bufferDurationMs + postCaptureDurationMs) * 1000
                }

                PLog.d(TAG, "Filtering samples: center=$centerTs, range=[$startTimeUs, $endTimeUs]")

                // 策略：寻找 startTimeUs 之前的最后一个关键帧作为视频起点
                var startIndex = -1
                for (i in currentSamples.indices.reversed()) {
                    val sample = currentSamples[i]
                    if (sample.info.presentationTimeUs <= startTimeUs && 
                        (sample.info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                        startIndex = i
                        break
                    }
                }

                // 如果没找到 startTimeUs 之前的关键帧，则寻找整个缓冲区中的第一个关键帧
                if (startIndex == -1) {
                    startIndex = currentSamples.indexOfFirst { 
                        (it.info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 
                    }
                }

                if (startIndex == -1 || encoderFormat == null) {
                    PLog.e(TAG, "No keyframe or format to mux (format=${encoderFormat != null})")
                    onCaptured(File("error"), 0L) // Notify failure to prevent hang
                    return@launch
                }

                // 构建最终样本列表，直到 endTimeUs
                val muxSamples = mutableListOf<CircularVideoRecorder.Sample>()
                for (i in startIndex until currentSamples.size) {
                    val sample = currentSamples[i]
                    muxSamples.add(sample)
                    // 核心修复：确保至少收集了一些帧再根据 endTime 停止，避免 1 帧问题
                    if (sample.info.presentationTimeUs >= endTimeUs && muxSamples.size > 5) break
                }

                if (muxSamples.size < 2) {
                    PLog.e(TAG, "Too few samples for video: ${muxSamples.size}")
                    onCaptured(File("error"), 0L) // Notify failure
                    return@launch
                }

                PLog.d(TAG, "Selected ${muxSamples.size} samples (was ${currentSamples.size} total)")
                val first = muxSamples.first().info.presentationTimeUs
                val last = muxSamples.last().info.presentationTimeUs
                PLog.d(TAG, "Selected range: [$first, $last], duration: ${(last-first)/1000}ms")

                val videoFile = File(context.cacheDir, "livephoto_${System.currentTimeMillis()}.mp4")

                // 封装 MP4
                muxVideo(videoFile, muxSamples)

                // 计算拍照时刻在视频中的相对时间戳
                val startTs = muxSamples.first().info.presentationTimeUs
                val presentationTimestampUs = centerTs - startTs

                onCaptured(videoFile, presentationTimestampUs)

            } catch (e: Exception) {
                PLog.e(TAG, "Failed to finish Live Photo capture", e)
                onCaptured(File("error"), 0L)
            } finally {
                isCapturing = false
                if (pendingRelease) {
                    PLog.d(TAG, "Executing delayed encoder release")
                    release()
                    pendingRelease = false
                }
            }
        }
    }

    private fun startDraining() {
        drainJob = scope.launch {
            val bufferInfo = MediaCodec.BufferInfo()
            while (isRunning) {
                try {
                    val encoderRef = encoder ?: break
                    val outputBufferIndex = try {
                        encoderRef.dequeueOutputBuffer(bufferInfo, 10_000L)
                    } catch (e: IllegalStateException) {
                        // 编码器可能在 dequeue 时被 stop 了
                        PLog.d(TAG, "Encoder dequeue cancelled (encoder stopped)")
                        break
                    }

                    if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        synchronized(this@LivePhotoRecorder) {
                            encoderFormat = encoderRef.outputFormat
                        }
                        PLog.d(TAG, "Encoder format changed: $encoderFormat")
                    } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        //PLog.v(TAG, "Encoder dequeue timeout")
                    } else if (outputBufferIndex >= 0) {
                        val outputBuffer = encoderRef.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            circularRecorder.addSample(outputBuffer, bufferInfo)
                        }
                        encoderRef.releaseOutputBuffer(outputBufferIndex, false)
                    }
                } catch (e: Exception) {
                    PLog.e(TAG, "Error draining encoder", e)
                    break
                }
            }
        }
    }

    private fun muxVideo(outputFile: File, samples: List<CircularVideoRecorder.Sample>) {
        val format = synchronized(this) { encoderFormat }
        if (format == null) {
            PLog.e(TAG, "Cannot mux video: encoderFormat is null")
            return
        }
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val trackIndex = muxer.addTrack(format)
        muxer.start()

        val baseTimeUs = samples.first().info.presentationTimeUs
        samples.forEach { sample ->
            val info = MediaCodec.BufferInfo()
            info.set(0, sample.info.size, sample.info.presentationTimeUs - baseTimeUs, sample.info.flags)
            val buffer = ByteBuffer.wrap(sample.data)
            muxer.writeSampleData(trackIndex, buffer, info)
        }

        muxer.stop()
        muxer.release()
        PLog.d(TAG, "Muxed ${samples.size} samples to ${outputFile.name}")
    }

    fun release() {
        isRunning = false
        drainJob?.cancel()
        // 不要在这里立刻置空 encoderFormat，因为异步的 recordVideo 还需要它进行 mux
        scope.launch(renderDispatcher) {
            internalRelease()
        }
    }

    private fun internalRelease() {
        val encoderRef = encoder
        encoder = null
        encoderRef?.let {
            try {
                it.stop()
                it.release()
            } catch (e: Exception) {
                PLog.w(TAG, "Error releasing encoder: ${e.message}")
            }
        }
        inputSurface = null

        lutRenderer?.release()
        lutRenderer = null

        // 核心修复：不要在这里调用 circularRecorder.release()。
        // 因为 release() 是异步执行的，如果此时用户已经重新打开了相机（Session B），
        // 调用 circularRecorder.release() 会把已经开始的 Session B 也关掉（isRecording=false）。
        // 样本缓冲区的生命周期应由 startRecording / stopRecording 同步控制。

        lastSharedContext = EGL14.EGL_NO_CONTEXT
        PLog.d(TAG, "Live Photo hardware resources released")
    }

    // 简单透出状态
    fun isRecording(): Boolean = isRunning
    fun isCapturing(): Boolean = isCapturing
}
