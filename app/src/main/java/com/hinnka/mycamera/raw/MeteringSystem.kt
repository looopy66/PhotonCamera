package com.hinnka.mycamera.raw

import com.hinnka.mycamera.color.TransferCurve
import java.nio.FloatBuffer
import kotlin.math.pow

/**
 * 评价测光与场景分析系统
 */
object MeteringSystem {
    private const val TAG = "MeteringSystem"

    fun analyze(
        floatBuffer: FloatBuffer,
        width: Int,
        height: Int,
        metadata: RawMetadata?,
        centerWeight: Float = 0.5f
    ): Float {
        val pixelCount = width * height
        val allLumas = FloatArray(pixelCount)
        var validPixelCount = 0

        val gridRows = 12
        val gridCols = 12
        val zoneSum = DoubleArray(gridRows * gridCols)
        val zonePixCount = IntArray(gridRows * gridCols)
        val zoneMaxL = FloatArray(gridRows * gridCols)
        val zoneSkinCount = IntArray(gridRows * gridCols)

        var maxLuma = 0.0f

        floatBuffer.position(0)
        for (y in 0 until height) {
            val gy = (y.toFloat() / height * gridRows).toInt().coerceIn(0, gridRows - 1)
            for (x in 0 until width) {
                val gx = (x.toFloat() / width * gridCols).toInt().coerceIn(0, gridCols - 1)
                val zi = gy * gridCols + gx

                val r = floatBuffer.get()
                val g = floatBuffer.get()
                val b = floatBuffer.get()
                floatBuffer.get() // skip alpha

                val luma = r * 0.2126f + g * 0.7152f + b * 0.0722f
                if (luma.isNaN() || luma < 0f) continue

                if (luma > maxLuma) maxLuma = luma
                allLumas[validPixelCount++] = luma

                zoneSum[zi] += luma.toDouble()
                zonePixCount[zi]++
                if (luma > zoneMaxL[zi]) zoneMaxL[zi] = luma

                // 2. 肤色检测逻辑
                if (r > g && g > b && g > 0.001f) {
                    val rgRatio = r / g
                    val gbRatio = g / b
                    if (rgRatio in 1.1f..2.5f && gbRatio in 1.0f..3.0f) {
                        zoneSkinCount[zi]++
                    }
                }
            }
        }

        if (validPixelCount == 0) {
            return 1f
        }

        // ---------------------------------------------------------
        // 区域评价测光算法 (Evaluative Metering Algorithm)
        // ---------------------------------------------------------
        var totalWeight = 0.0
        var weightedSumLog = 0.0

        // 计算全局平均亮度，用于逆光检测对比
        var globalTotalLog = 0.0
        var globalTotalPixels = 0
        for (i in 0 until gridRows * gridCols) {
            globalTotalLog += zoneSum[i]
            globalTotalPixels += zonePixCount[i]
        }
        val globalAvgLog = if (globalTotalPixels > 0) globalTotalLog / globalTotalPixels else 0.0

        for (i in 0 until gridRows * gridCols) {
            if (zonePixCount[i] == 0) continue

            val gy = i / gridCols
            val gx = i % gridCols
            val pzx = (gx + 0.5f) / gridCols
            val pzy = (gy + 0.5f) / gridRows

            val zoneAvg = zoneSum[i] / zonePixCount[i]

            // 1. 基础权重：画面中心
            val distSqC = (pzx - 0.5f).let { it * it } + (pzy - 0.5f).let { it * it }

            val normalizedCenterWeight = (centerWeight.coerceIn(0f, 2f) / 2f).toDouble()
            val evaluativeWeight = 1.0
            val centerSigma = 0.22 - 0.19 * normalizedCenterWeight
            val centerBoost = 1.0 + 7.0 * normalizedCenterWeight
            val centerWeightedWeight = kotlin.math.exp(-distSqC / centerSigma) * centerBoost
            var weight = evaluativeWeight * (1.0 - normalizedCenterWeight) +
                centerWeightedWeight * normalizedCenterWeight

            // 2. 肤色密度补偿
            val skinDensity = zoneSkinCount[i].toDouble() / zonePixCount[i]
            if (skinDensity > 0.05) {
                weight *= (1.0 + skinDensity * 12.0)
            }

            // 3. 高光评价逻辑 (Sky/Highlight handling)
            // 中心权重越高，越主动排除中心附近的亮天空/背景，避免逆光场景继续压黑主体。
            val highlightThreshold = 0.75 - 0.5 * normalizedCenterWeight
            if (zoneAvg > highlightThreshold) {
                weight *= 0.15 / (1.0 + 2.0 * normalizedCenterWeight)
            }

            // 4. 逆光补偿 (Backlight compensation)
            // 如果对焦中心区域明显暗于全局平均，说明可能处于大面积强光背后的阴影中，大幅增加权重以拉亮主体
            if (zoneAvg < globalAvgLog - 0.05) {
                weight *= 3.0 + 9.0 * normalizedCenterWeight
            }

            // 5. 中央重点暗部优先
            // 高中心权重时，暗的中心区域应主导测光；否则大面积天空会把平均值抬高，肉眼上仍然欠曝。
            if (normalizedCenterWeight > 0.0 && distSqC < 0.12 && zoneAvg < TransferCurve.LINEAR.middleGray) {
                val darkness = 1.0 - (zoneAvg / TransferCurve.LINEAR.middleGray).coerceIn(0.0, 1.0)
                weight *= 1.0 + darkness * 12.0 * normalizedCenterWeight
            }

            weightedSumLog += zoneAvg * weight
            totalWeight += weight
        }

        // 计算加权平均亮度
        if (totalWeight <= 0.0) {
            return 1f
        }

        val avg = (weightedSumLog / totalWeight).toFloat()
        if (avg <= 0.000001f) {
            return 1f
        }

        val exposureBias = metadata?.exposureBias?.let { if (it > -10 && it < 10) it else 0f } ?: 0f
        val biasMultiplier = 2.0f.pow(exposureBias)

        val targetLumaIRE = TransferCurve.LINEAR.middleGray
        val gain = targetLumaIRE * biasMultiplier / avg
        return gain.coerceIn(0.25f, 16f)
    }

}
