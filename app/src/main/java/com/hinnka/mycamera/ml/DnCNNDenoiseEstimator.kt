package com.hinnka.mycamera.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.hinnka.mycamera.utils.PLog
import com.hinnka.mycamera.utils.StartupTrace
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DnCNNDenoiseEstimator(
    context: Context,
    private val modelAssetName: String = MODEL_DNCNN
) {
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var nnApiDelegate: NnApiDelegate? = null
    private var isInitialized = false
    private var inputWidth = 256
    private var inputHeight = 256
    private var outputWidth = 256
    private var outputHeight = 256
    private var inputChannelsFirst = false

    init {
        try {
            val modelFile = StartupTrace.measure("DnCNNDenoiseEstimator.loadMappedFile") {
                FileUtil.loadMappedFile(context, modelAssetName)
            }

            val gpuOptions = Interpreter.Options()
            val compatList = StartupTrace.measure("DnCNNDenoiseEstimator.CompatibilityList()") {
                CompatibilityList()
            }
            gpuDelegate = StartupTrace.measure("DnCNNDenoiseEstimator.GpuDelegate()") {
                if (compatList.isDelegateSupportedOnThisDevice) {
                    val delegateOptions = compatList.bestOptionsForThisDevice
                    GpuDelegate(delegateOptions)
                } else {
                    GpuDelegate()
                }
            }
            StartupTrace.measure("DnCNNDenoiseEstimator.gpuOptions.addDelegate") {
                gpuOptions.addDelegate(gpuDelegate)
            }
            try {
                interpreter = StartupTrace.measure("DnCNNDenoiseEstimator.Interpreter(GPU)") {
                    Interpreter(modelFile, gpuOptions)
                }
                isInitialized = true
                PLog.d(TAG, "Using GPU Delegate for DnCNN Denoise: $modelAssetName")
            } catch (e: Exception) {
                PLog.w(TAG, "Failed to initialize GPU delegate, falling back to CPU", e)
                gpuDelegate?.close()
                gpuDelegate = null
            }

            if (!isInitialized) {
                val nnApiOptions = Interpreter.Options()
                nnApiDelegate = StartupTrace.measure("DnCNNDenoiseEstimator.NnApiDelegate()") {
                    NnApiDelegate()
                }
                StartupTrace.measure("DnCNNDenoiseEstimator.nnApiOptions.addDelegate") {
                    nnApiOptions.addDelegate(nnApiDelegate)
                }
                try {
                    interpreter = StartupTrace.measure("DnCNNDenoiseEstimator.Interpreter(NNAPI)") {
                        Interpreter(modelFile, nnApiOptions)
                    }
                    isInitialized = true
                    PLog.d(TAG, "Using NNAPI (NPU) for DnCNN Denoise: $modelAssetName")
                } catch (e: Exception) {
                    PLog.w(TAG, "Failed to initialize NNAPI delegate, falling back to GPU", e)
                    nnApiDelegate?.close()
                    nnApiDelegate = null
                }
            }

            // Fallback to CPU
            if (!isInitialized) {
                val cpuOptions = Interpreter.Options()
                cpuOptions.setNumThreads(4)
                interpreter = StartupTrace.measure("DnCNNDenoiseEstimator.Interpreter(CPU)") {
                    Interpreter(modelFile, cpuOptions)
                }
                isInitialized = true
                PLog.d(TAG, "Using CPU for DnCNN Denoise: $modelAssetName")
            }

            interpreter?.let {
                updateTensorDimensions(it)
            }
        } catch (e: Exception) {
            PLog.e(TAG, "Error initializing DnCNNDenoiseEstimator: $modelAssetName", e)
        }
    }

    private fun updateTensorDimensions(interpreter: Interpreter) {
        val inputShape = interpreter.getInputTensor(0).shape()
        val outputShape = interpreter.getOutputTensor(0).shape()

        // typically [1, 1, H, W] or [1, H, W, 1]
        inputChannelsFirst = inputShape.size == 4 && inputShape[1] == 1
        if (inputShape.size == 4 && inputShape[3] == 1) {
            inputHeight = inputShape[1]
            inputWidth = inputShape[2]
        } else if (inputChannelsFirst) {
            inputHeight = inputShape[2]
            inputWidth = inputShape[3]
        } else if (inputShape.size == 4 && inputShape[3] == 3) {
            inputHeight = inputShape[1]
            inputWidth = inputShape[2]
        } else {
            PLog.w(TAG, "Unexpected input shape: ${inputShape.contentToString()}")
        }

        val outputDims = outputShape.filter { it > 1 }
        if (outputDims.size >= 2) {
            outputHeight = outputDims[outputDims.size - 2]
            outputWidth = outputDims[outputDims.size - 1]
        }

        PLog.d(
            TAG,
            "DnCNN model ready: asset=$modelAssetName input=${inputWidth}x$inputHeight output=${outputWidth}x$outputHeight inputLayout=${if (inputChannelsFirst) "NCHW" else "NHWC"} inputType=${interpreter.getInputTensor(0).dataType()} outputType=${interpreter.getOutputTensor(0).dataType()} inputShape=${inputShape.contentToString()} outputShape=${outputShape.contentToString()}"
        )
    }

    /**
     * Denoises a single-channel (Grayscale) Luma component.
     * Expects inputBitmap to be resized or we will resize it internally.
     */
    fun denoise(inputBitmap: Bitmap): Bitmap? {
        if (!isInitialized || interpreter == null) {
            PLog.e(TAG, "DnCNNDenoiseEstimator is not initialized")
            return null
        }

        try {
            val inputTensor = interpreter!!.getInputTensor(0)
            val outputTensor = interpreter!!.getOutputTensor(0)
            val inputDataType = inputTensor.dataType()
            val outputDataType = outputTensor.dataType()

            val resized = if (inputBitmap.width == inputWidth && inputBitmap.height == inputHeight) {
                inputBitmap
            } else {
                Bitmap.createScaledBitmap(inputBitmap, inputWidth, inputHeight, true)
            }

            val pixels = IntArray(inputWidth * inputHeight)
            resized.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
            if (resized !== inputBitmap) {
                resized.recycle()
            }

            val isRgb = inputTensor.shape().last() == 3 || (inputChannelsFirst && inputTensor.shape()[1] == 3)
            val channels = if (isRgb) 3 else 1
            val buffer = ByteBuffer.allocateDirect(inputWidth * inputHeight * channels * 4).order(ByteOrder.nativeOrder())

            // Input normalization: typically DnCNN takes [0, 1] range float input.
            for (pixel in pixels) {
                if (isRgb) {
                    val r = Color.red(pixel) / 255.0f
                    val g = Color.green(pixel) / 255.0f
                    val b = Color.blue(pixel) / 255.0f
                    buffer.putFloat(r)
                    buffer.putFloat(g)
                    buffer.putFloat(b)
                } else {
                    // Convert to grayscale/luma
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    val luma = (0.299f * r + 0.587f * g + 0.114f * b) / 255.0f
                    buffer.putFloat(luma)
                }
            }
            buffer.rewind()

            val outputBuffer = TensorBuffer.createFixedSize(outputTensor.shape(), outputDataType)

            StartupTrace.measure("DnCNNDenoiseEstimator.Interpreter.run") {
                interpreter?.run(buffer, outputBuffer.buffer)
            }

            val floatArray = outputBuffer.floatArray
            val resultBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
            val resultPixels = IntArray(outputWidth * outputHeight)

            for (i in resultPixels.indices) {
                if (isRgb) {
                    val r = (floatArray[i * 3] * 255.0f).toInt().coerceIn(0, 255)
                    val g = (floatArray[i * 3 + 1] * 255.0f).toInt().coerceIn(0, 255)
                    val b = (floatArray[i * 3 + 2] * 255.0f).toInt().coerceIn(0, 255)
                    resultPixels[i] = Color.rgb(r, g, b)
                } else {
                    val pixel = pixels[i]
                    val origR = Color.red(pixel).toFloat()
                    val origG = Color.green(pixel).toFloat()
                    val origB = Color.blue(pixel).toFloat()

                    // Convert original to YCbCr
                    val cb = -0.1687f * origR - 0.3313f * origG + 0.5f * origB + 128f
                    val cr = 0.5f * origR - 0.4187f * origG - 0.0813f * origB + 128f

                    // Use new denoised Y
                    val newY = floatArray[i] * 255.0f

                    // Convert back to RGB
                    val r = (newY + 1.402f * (cr - 128f)).toInt().coerceIn(0, 255)
                    val g = (newY - 0.344136f * (cb - 128f) - 0.714136f * (cr - 128f)).toInt().coerceIn(0, 255)
                    val b = (newY + 1.772f * (cb - 128f)).toInt().coerceIn(0, 255)

                    resultPixels[i] = Color.rgb(r, g, b)
                }
            }
            resultBitmap.setPixels(resultPixels, 0, outputWidth, 0, 0, outputWidth, outputHeight)
            return resultBitmap

        } catch (e: Exception) {
            PLog.e(TAG, "Error during DnCNN denoise", e)
            return null
        }
    }

    /**
     * Denoises the full image by splitting it into patches, processing them separately,
     * and stitching them back together. This preserves the original resolution.
     */
    fun denoisePatchwise(inputBitmap: Bitmap, onProgress: ((Float) -> Unit)? = null): Bitmap? {
        if (!isInitialized || interpreter == null) {
            PLog.e(TAG, "DnCNNDenoiseEstimator is not initialized")
            return null
        }

        val width = inputBitmap.width
        val height = inputBitmap.height
        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(resultBitmap)
        val paint = android.graphics.Paint()

        val overlap = 24
        val strideX = inputWidth - 2 * overlap
        val strideY = inputHeight - 2 * overlap

        val totalSteps = ((height + strideY - 1) / strideY) * ((width + strideX - 1) / strideX)
        var currentStep = 0

        for (dstY in 0 until height step strideY) {
            for (dstX in 0 until width step strideX) {
                val validW = Math.min(width - dstX, strideX)
                val validH = Math.min(height - dstY, strideY)

                val cx = dstX + validW / 2
                val cy = dstY + validH / 2

                var startX = cx - inputWidth / 2
                var startY = cy - inputHeight / 2

                // clamp to image bounds
                if (startX < 0) startX = 0
                if (startY < 0) startY = 0
                if (startX + inputWidth > width) startX = Math.max(0, width - inputWidth)
                if (startY + inputHeight > height) startY = Math.max(0, height - inputHeight)

                // Create a patch of exactly inputWidth x inputHeight (with padding if image is smaller)
                val patchBitmap = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
                val patchCanvas = android.graphics.Canvas(patchBitmap)
                val cropW = Math.min(width - startX, inputWidth)
                val cropH = Math.min(height - startY, inputHeight)
                
                val srcRect = android.graphics.Rect(startX, startY, startX + cropW, startY + cropH)
                val dstRect = android.graphics.Rect(0, 0, cropW, cropH)
                patchCanvas.drawBitmap(inputBitmap, srcRect, dstRect, null)

                val denoisedPatch = denoise(patchBitmap)
                patchBitmap.recycle()

                if (denoisedPatch != null) {
                    val patchSrcRect = android.graphics.Rect(dstX - startX, dstY - startY, dstX - startX + validW, dstY - startY + validH)
                    val patchDstRect = android.graphics.Rect(dstX, dstY, dstX + validW, dstY + validH)
                    canvas.drawBitmap(denoisedPatch, patchSrcRect, patchDstRect, paint)
                    denoisedPatch.recycle()
                }

                currentStep++
                onProgress?.invoke(currentStep.toFloat() / totalSteps)
            }
        }

        return resultBitmap
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        gpuDelegate?.close()
        gpuDelegate = null
        nnApiDelegate?.close()
        nnApiDelegate = null
        isInitialized = false
    }

    companion object {
        private const val TAG = "DnCNNDenoiseEstimator"
        const val MODEL_DNCNN = "dncnn.tflite"
    }
}
