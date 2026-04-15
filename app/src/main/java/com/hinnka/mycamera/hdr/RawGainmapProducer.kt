package com.hinnka.mycamera.hdr

import android.graphics.Bitmap
import android.graphics.Gainmap
import android.os.Build
import java.nio.ByteBuffer
import kotlin.math.ln
import kotlin.math.pow

class RawGainmapProducer : GainmapProducer {

    override suspend fun build(source: GainmapSourceSet): GainmapResult? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        if (source.sourceKind != SourceKind.RAW) return null

        val hdrReference = source.hdrReference?.bitmap ?: return null
        val sdrBase = source.sdrBase
        if (sdrBase.width <= 0 || sdrBase.height <= 0) return null

        val alignedHdr = if (hdrReference.width != sdrBase.width || hdrReference.height != sdrBase.height) {
            Bitmap.createScaledBitmap(hdrReference, sdrBase.width, sdrBase.height, true)
        } else {
            hdrReference
        }

        val fullHdrRatio = source.displayHdrSdrRatio.takeIf { it > 1f } ?: DEFAULT_FULL_HDR_RATIO
        val gainmapBitmap = createGainmapBitmap(sdrBase, alignedHdr, fullHdrRatio) ?: return null
        val gainmap = Gainmap(gainmapBitmap).apply {
            setRatioMin(MIN_GAIN_RATIO, MIN_GAIN_RATIO, MIN_GAIN_RATIO)
            setRatioMax(MAX_GAIN_RATIO, MAX_GAIN_RATIO, MAX_GAIN_RATIO)
            setGamma(1.0f, 1.0f, 1.0f)
            setEpsilonSdr(EPSILON, EPSILON, EPSILON)
            setEpsilonHdr(EPSILON, EPSILON, EPSILON)
            setMinDisplayRatioForHdrTransition(1.0f)
            setDisplayRatioForFullHdr(fullHdrRatio)
        }

        return GainmapResult(
            gainmap = gainmap,
            sourceKind = source.sourceKind,
            confidence = source.confidence
        )
    }

    private fun createGainmapBitmap(sdrBase: Bitmap, hdrReference: Bitmap, fullHdrRatio: Float): Bitmap? {
        val displayMapper = HlgDisplayMapper(fullHdrRatio)
        val width = (sdrBase.width / DOWNSAMPLE).coerceAtLeast(1)
        val height = (sdrBase.height / DOWNSAMPLE).coerceAtLeast(1)
        val contents = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val pixels = ByteArray(width * height)

        val logRatioSpan = ln(MAX_GAIN_RATIO / MIN_GAIN_RATIO)

        var index = 0
        for (y in 0 until height) {
            val srcY = ((y + 0.5f) * sdrBase.height / height).toInt().coerceIn(0, sdrBase.height - 1)
            for (x in 0 until width) {
                val srcX = ((x + 0.5f) * sdrBase.width / width).toInt().coerceIn(0, sdrBase.width - 1)

                val sdrLuma = sampleSdrLuma(sdrBase, srcX, srcY)
                val hdrSceneLuma = sampleHdrLuma(hdrReference, srcX, srcY)
                val hdrDisplayLuma = displayMapper.mapSceneLinearToDisplayLuma(hdrSceneLuma)

                val rawRatio = (hdrDisplayLuma / (sdrLuma + EPSILON)).coerceIn(MIN_GAIN_RATIO, MAX_GAIN_RATIO)
                val hdrEligibility = smoothstep(HDR_ELIGIBILITY_START, HDR_ELIGIBILITY_END, hdrSceneLuma)
                val deltaMask = smoothstep(DELTA_START, DELTA_END, hdrDisplayLuma - sdrLuma)
                val highlightMask = smoothstep(HIGHLIGHT_START, HIGHLIGHT_END, sdrLuma)
                val selectiveWeight = (hdrEligibility * deltaMask * highlightMask).pow(WEIGHT_POWER)
                val targetRatio = (1.0f + (rawRatio - 1.0f) * selectiveWeight)
                    .coerceIn(MIN_GAIN_RATIO, MAX_GAIN_RATIO)
                val encoded = ((ln(targetRatio / MIN_GAIN_RATIO) / logRatioSpan) * 255.0f)
                    .toInt()
                    .coerceIn(0, 255)

                pixels[index++] = encoded.toByte()
            }
        }

        val blurred = blurGainmap(pixels, width, height)
        contents.copyPixelsFromBuffer(ByteBuffer.wrap(blurred))
        return contents
    }

    private fun blurGainmap(pixels: ByteArray, width: Int, height: Int): ByteArray {
        val r = BLUR_RADIUS
        val temp = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0
                for (dx in -r..r) {
                    sum += pixels[y * width + (x + dx).coerceIn(0, width - 1)].toInt() and 0xFF
                }
                temp[y * width + x] = (sum / (2 * r + 1)).toByte()
            }
        }
        val result = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0
                for (dy in -r..r) {
                    sum += temp[(y + dy).coerceIn(0, height - 1) * width + x].toInt() and 0xFF
                }
                result[y * width + x] = (sum / (2 * r + 1)).toByte()
            }
        }
        return result
    }

    private fun sampleSdrLuma(bitmap: Bitmap, x: Int, y: Int): Float {
        val c = bitmap.getColor(x, y)
        val r = srgbToLinear(c.red())
        val g = srgbToLinear(c.green())
        val b = srgbToLinear(c.blue())
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    private fun sampleHdrLuma(bitmap: Bitmap, x: Int, y: Int): Float {
        val c = bitmap.getColor(x, y)
        val rgb = floatArrayOf(c.red(), c.green(), c.blue())
        return (0.2627f * rgb[0] + 0.6780f * rgb[1] + 0.0593f * rgb[2]).coerceAtLeast(0f)
    }

    private fun srgbToLinear(value: Float): Float {
        return if (value <= 0.04045f) {
            value / 12.92f
        } else {
            ((value + 0.055f) / 1.055f).pow(2.4f)
        }
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge0 == edge1) return if (x >= edge1) 1.0f else 0.0f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0f, 1.0f)
        return t * t * (3.0f - 2.0f * t)
    }

    companion object {
        private const val DOWNSAMPLE = 4
        private const val MIN_GAIN_RATIO = 1.0f
        private const val MAX_GAIN_RATIO = 4.5f
        private const val EPSILON = 1e-4f
        private const val HDR_ELIGIBILITY_START = 1.0f
        private const val HDR_ELIGIBILITY_END = 2.0f
        private const val DELTA_START = 0.02f
        private const val DELTA_END = 0.22f
        private const val HIGHLIGHT_START = 0.56f
        private const val HIGHLIGHT_END = 0.95f
        private const val WEIGHT_POWER = 1.15f
        private const val BLUR_RADIUS = 3
        private const val DEFAULT_FULL_HDR_RATIO = 1.35f
    }
}
