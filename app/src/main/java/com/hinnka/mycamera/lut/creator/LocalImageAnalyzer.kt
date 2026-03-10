package com.hinnka.mycamera.lut.creator

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

object LocalImageAnalyzer {

    /**
     * Analyzes the given images using K-Means clustering in Oklab space
     * to extract the 8 most representative Target Colors.
     * It then estimates their corresponding Source Colors using Inverse Transformation
     * (Grey World + Contrast Normalization + Saturation Correction).
     */
    suspend fun analyzeImages(bitmaps: List<Bitmap>): LutRecipe = withContext(Dispatchers.Default) {
        val maxDim = 256
        var totalPixels = 0

        for (bitmap in bitmaps) {
            var w = bitmap.width
            var h = bitmap.height
            if (w > maxDim || h > maxDim) {
                val scale = maxDim.toFloat() / max(w, h)
                w = (w * scale).toInt()
                h = (h * scale).toInt()
            }
            totalPixels += w * h
        }

        if (totalPixels == 0) return@withContext LutRecipe()

        // We use an interleaved array for memory efficiency: [L, a, b, L, a, b...]
        val oklabPixels = FloatArray(totalPixels * 3)
        var idx = 0

        var sumL = 0.0
        var sumA = 0.0
        var sumB = 0.0
        var sumC = 0.0
        var minL = 1.0f
        var maxL = 0.0f

        for (bitmap in bitmaps) {
            var w = bitmap.width
            var h = bitmap.height
            if (w > maxDim || h > maxDim) {
                val scale = maxDim.toFloat() / max(w, h)
                w = (w * scale).toInt()
                h = (h * scale).toInt()
            }

            val scaled = if (w != bitmap.width || h != bitmap.height) {
                Bitmap.createScaledBitmap(bitmap, w, h, true)
            } else {
                bitmap
            }

            val pixels = IntArray(scaled.width * scaled.height)
            scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)

            for (color in pixels) {
                val r = Color.red(color) / 255f
                val g = Color.green(color) / 255f
                val b = Color.blue(color) / 255f

                val lr = OklchConverter.srgbToLinear(r)
                val lg = OklchConverter.srgbToLinear(g)
                val lb = OklchConverter.srgbToLinear(b)
                val oklab = OklchConverter.linearSrgbToOklab(lr, lg, lb)

                val L = oklab[0]
                val a = oklab[1]
                val b_v = oklab[2]

                oklabPixels[idx * 3] = L
                oklabPixels[idx * 3 + 1] = a
                oklabPixels[idx * 3 + 2] = b_v
                idx++

                sumL += L
                sumA += a
                sumB += b_v
                val c = Math.sqrt((a * a + b_v * b_v).toDouble()).toFloat()
                sumC += c

                if (L < minL) minL = L
                if (L > maxL) maxL = L
            }

            if (scaled != bitmap) {
                scaled.recycle()
            }
        }

        // --- 1. Global Image Statistics ---
        val avgL = (sumL / totalPixels).toFloat()
        val avgA = (sumA / totalPixels).toFloat()
        val avgB = (sumB / totalPixels).toFloat()
        val avgC = (sumC / totalPixels).toFloat()

        // Dampen the global average subtraction so the white balance correction isn't too aggressive.
        val dampenVal = 0.5f // Blend factor (0.0 = no correction, 1.0 = full correction)

        // Target neutral chroma
        val targetNeutralC = 0.12f
        val satCorr = (targetNeutralC / max(0.01f, avgC)).coerceIn(0.7f, 1.5f) // Narrowed range
        val blendedSatCorr = 1.0f + (satCorr - 1.0f) * dampenVal

        val lumaRange = max(0.01f, maxL - minL)

        // --- 2. K-means Clustering (K=8) ---
        val k = 8
        val centroids = Array(k) { FloatArray(3) }

        // Initialize centroids uniformly from the data
        val step = totalPixels / k
        for (i in 0 until k) {
            val pIdx = min(i * step, totalPixels - 1)
            centroids[i][0] = oklabPixels[pIdx * 3]
            centroids[i][1] = oklabPixels[pIdx * 3 + 1]
            centroids[i][2] = oklabPixels[pIdx * 3 + 2]
        }

        val assignments = IntArray(totalPixels)
        val maxIters = 20
        var centroidsChanged = true
        var iters = 0

        while (centroidsChanged && iters < maxIters) {
            centroidsChanged = false
            iters++

            // Assign points to nearest centroid
            for (i in 0 until totalPixels) {
                val L = oklabPixels[i * 3]
                val a = oklabPixels[i * 3 + 1]
                val b_v = oklabPixels[i * 3 + 2]

                var bestDist = Float.MAX_VALUE
                var bestCluster = 0
                for (c in 0 until k) {
                    val cL = centroids[c][0]
                    val ca = centroids[c][1]
                    val cb = centroids[c][2]
                    val dL = L - cL
                    val da = a - ca
                    val db = b_v - cb
                    // Euclidean distance in Oklab is perceptually uniform
                    val dist = dL * dL + da * da + db * db
                    if (dist < bestDist) {
                        bestDist = dist
                        bestCluster = c
                    }
                }
                assignments[i] = bestCluster
            }

            // Update centroids
            val clusterCounts = IntArray(k)
            val clusterSums = Array(k) { DoubleArray(3) }

            for (i in 0 until totalPixels) {
                val cluster = assignments[i]
                clusterCounts[cluster]++
                clusterSums[cluster][0] += oklabPixels[i * 3].toDouble()
                clusterSums[cluster][1] += oklabPixels[i * 3 + 1].toDouble()
                clusterSums[cluster][2] += oklabPixels[i * 3 + 2].toDouble()
            }

            for (c in 0 until k) {
                if (clusterCounts[c] > 0) {
                    val newL = (clusterSums[c][0] / clusterCounts[c]).toFloat()
                    val newa = (clusterSums[c][1] / clusterCounts[c]).toFloat()
                    val newb = (clusterSums[c][2] / clusterCounts[c]).toFloat()

                    if (Math.abs(newL - centroids[c][0]) > 0.001f ||
                        Math.abs(newa - centroids[c][1]) > 0.001f ||
                        Math.abs(newb - centroids[c][2]) > 0.001f
                    ) {
                        centroidsChanged = true
                    }
                    centroids[c][0] = newL
                    centroids[c][1] = newa
                    centroids[c][2] = newb
                }
            }
        }

        // --- 3. Map Target Colors to Source Colors ---
        val controlPoints = mutableListOf<ControlPoint>()

        for (c in 0 until k) {
            val tL = centroids[c][0]
            val ta = centroids[c][1]
            val tb = centroids[c][2]

            // Inverse Transform:
            // 1. Contrast Normalization (damped)
            val fullNormL = ((tL - minL) / lumaRange).coerceIn(0f, 1f)
            val sL = tL * (1f - dampenVal) + fullNormL * dampenVal

            // 2. Grey World white balance removal (damped)
            var sa = ta - avgA * dampenVal
            var sb = tb - avgB * dampenVal

            // 3. Saturation correction (damped)
            sa *= blendedSatCorr
            sb *= blendedSatCorr

            // Convert back to Linear RGB, then sRGB for Control Points
            val sourceRgb = oklabToSrgb(sL, sa, sb)
            val targetRgb = oklabToSrgb(tL, ta, tb)

            controlPoints.add(
                ControlPoint(
                    sourceR = sourceRgb[0], sourceG = sourceRgb[1], sourceB = sourceRgb[2],
                    targetR = targetRgb[0], targetG = targetRgb[1], targetB = targetRgb[2]
                )
            )
        }

        // --- 4. Anchors (Black, White, Mid-Grey) ---
        // Anchors should map strictly identity or very close to identity to prevent heavy color casts on neutrals
        controlPoints.add(ControlPoint(0f, 0f, 0f, 0f, 0f, 0f))
        controlPoints.add(ControlPoint(1f, 1f, 1f, 1f, 1f, 1f))

        // Target Mid-Grey - Add a very slight curve to grey if needed, but safer to keep mostly neutral
        val greyTarget = oklabToSrgb((minL + maxL) / 2f, avgA * 0.2f, avgB * 0.2f)
        val sGrey = oklabToSrgb(0.5f, 0f, 0f)
        controlPoints.add(
            ControlPoint(sGrey[0], sGrey[1], sGrey[2], greyTarget[0], greyTarget[1], greyTarget[2])
        )

        LutRecipe(controlPoints)
    }

    private fun oklabToSrgb(L: Float, a: Float, b: Float): FloatArray {
        val linear = OklchConverter.oklabToLinearSrgb(L, a, b)
        val r = OklchConverter.linearToSrgb(linear[0].coerceIn(0f, 1f))
        val g = OklchConverter.linearToSrgb(linear[1].coerceIn(0f, 1f))
        val b_srgb = OklchConverter.linearToSrgb(linear[2].coerceIn(0f, 1f))
        return floatArrayOf(r, g, b_srgb)
    }
}
