package com.hinnka.mycamera.lut.creator

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import androidx.core.graphics.scale

object LocalImageAnalyzer {

    /**
     * Analyzes the given images using K-Means clustering in Oklab space
     * to extract the 8 most representative Target Colors.
     * It then estimates their corresponding Source Colors using Inverse Transformation
     * (Grey World + Contrast Normalization + Saturation Correction).
     */
    suspend fun analyzeImages(bitmaps: List<Bitmap>): LutRecipe = withContext(Dispatchers.Default) {
        // Existing implementation remains as fallback or for local-only extraction
        internalAnalyzeImages(bitmaps)
    }

    suspend fun analyzeSourceTargetImages(source: Bitmap, target: Bitmap): LutRecipe = withContext(Dispatchers.Default) {
        val maxDim = 256
        val sw = if (source.width > maxDim || source.height > maxDim) {
            val scale = maxDim.toFloat() / max(source.width, source.height)
            (source.width * scale).toInt()
        } else source.width
        val sh = if (source.width > maxDim || source.height > maxDim) {
            val scale = maxDim.toFloat() / max(source.width, source.height)
            (source.height * scale).toInt()
        } else source.height

        val scaledSource = source.scale(sw, sh)
        val scaledTarget = target.scale(sw, sh)

        val pixelsS = IntArray(sw * sh)
        val pixelsT = IntArray(sw * sh)
        scaledSource.getPixels(pixelsS, 0, sw, 0, 0, sw, sh)
        scaledTarget.getPixels(pixelsT, 0, sw, 0, 0, sw, sh)

        val totalPixels = sw * sh
        if (totalPixels == 0) return@withContext LutRecipe()

        val oklabSource = FloatArray(totalPixels * 3)
        for (i in 0 until totalPixels) {
            val c = pixelsS[i]
            val r = Color.red(c) / 255f
            val g = Color.green(c) / 255f
            val b = Color.blue(c) / 255f
            val oklab = OklchConverter.linearSrgbToOklab(
                OklchConverter.srgbToLinear(r),
                OklchConverter.srgbToLinear(g),
                OklchConverter.srgbToLinear(b)
            )
            oklabSource[i * 3] = oklab[0]
            oklabSource[i * 3 + 1] = oklab[1]
            oklabSource[i * 3 + 2] = oklab[2]
        }

        // K-Means on Source to find significant colors
        val k = 12
        val centroids = Array(k) { FloatArray(3) }
        val step = totalPixels / k
        for (i in 0 until k) {
            val pIdx = min(i * step, totalPixels - 1)
            centroids[i][0] = oklabSource[pIdx * 3]
            centroids[i][1] = oklabSource[pIdx * 3 + 1]
            centroids[i][2] = oklabSource[pIdx * 3 + 2]
        }

        val assignments = IntArray(totalPixels)
        repeat(15) { // 15 iterations of K-means
            for (i in 0 until totalPixels) {
                var bestDist = Float.MAX_VALUE
                var bestC = 0
                for (c in 0 until k) {
                    val dL = oklabSource[i * 3] - centroids[c][0]
                    val da = oklabSource[i * 3 + 1] - centroids[c][1]
                    val db = oklabSource[i * 3 + 2] - centroids[c][2]
                    val dist = dL * dL + da * da + db * db
                    if (dist < bestDist) {
                        bestDist = dist
                        bestC = c
                    }
                }
                assignments[i] = bestC
            }
            val sums = Array(k) { DoubleArray(3) }
            val counts = IntArray(k)
            for (i in 0 until totalPixels) {
                val c = assignments[i]
                sums[c][0] += oklabSource[i * 3].toDouble()
                sums[c][1] += oklabSource[i * 3 + 1].toDouble()
                sums[c][2] += oklabSource[i * 3 + 2].toDouble()
                counts[c]++
            }
            for (c in 0 until k) {
                if (counts[c] > 0) {
                    centroids[c][0] = (sums[c][0] / counts[c]).toFloat()
                    centroids[c][1] = (sums[c][1] / counts[c]).toFloat()
                    centroids[c][2] = (sums[c][2] / counts[c]).toFloat()
                }
            }
        }

        val controlPoints = mutableListOf<ControlPoint>()
        // For each cluster, find the average color in TARGET
        for (c in 0 until k) {
            var sumTR = 0.0
            var sumTG = 0.0
            var sumTB = 0.0
            var count = 0
            for (i in 0 until totalPixels) {
                if (assignments[i] == c) {
                    val colorT = pixelsT[i]
                    sumTR += Color.red(colorT) / 255f
                    sumTG += Color.green(colorT) / 255f
                    sumTB += Color.blue(colorT) / 255f
                    count++
                }
            }

            if (count > 0) {
                val sourceRgb = oklabToSrgb(centroids[c][0], centroids[c][1], centroids[c][2])
                controlPoints.add(ControlPoint(
                    sourceR = sourceRgb[0], sourceG = sourceRgb[1], sourceB = sourceRgb[2],
                    targetR = (sumTR / count).toFloat(), targetG = (sumTG / count).toFloat(), targetB = (sumTB / count).toFloat()
                ))
            }
        }

        // Anchors
        controlPoints.add(ControlPoint(0f, 0f, 0f, 0f, 0f, 0f))
        controlPoints.add(ControlPoint(1f, 1f, 1f, 1f, 1f, 1f))
        controlPoints.add(ControlPoint(0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f))

        // Skin Tone Protection Anchor
        // Optimized for typical young Asian female skin tone to ensure natural results
        // Representing a healthy, light warm skin tone
        val asianSkinTone = floatArrayOf(0.82f, 0.68f, 0.62f) 
        controlPoints.add(ControlPoint(
            sourceR = asianSkinTone[0], sourceG = asianSkinTone[1], sourceB = asianSkinTone[2],
            targetR = asianSkinTone[0], targetG = asianSkinTone[1], targetB = asianSkinTone[2]
        ))

        scaledSource.recycle()
        scaledTarget.recycle()

        LutRecipe(controlPoints)
    }

    private suspend fun internalAnalyzeImages(bitmaps: List<Bitmap>): LutRecipe = withContext(Dispatchers.Default) {
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

                val l = oklab[0]
                val a = oklab[1]
                val bv = oklab[2]

                oklabPixels[idx * 3] = l
                oklabPixels[idx * 3 + 1] = a
                oklabPixels[idx * 3 + 2] = bv
                idx++

                sumL += l
                sumA += a
                sumB += bv
                val cStrLimit = Math.sqrt((a * a + bv * bv).toDouble()).toFloat()
                sumC += cStrLimit

                if (l < minL) minL = l
                if (l > maxL) maxL = l
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
                val l = oklabPixels[i * 3]
                val a = oklabPixels[i * 3 + 1]
                val bv = oklabPixels[i * 3 + 2]

                var bestDist = Float.MAX_VALUE
                var bestCluster = 0
                for (c in 0 until k) {
                    val cL = centroids[c][0]
                    val ca = centroids[c][1]
                    val cb = centroids[c][2]
                    val dL = l - cL
                    val da = a - ca
                    val db = bv - cb
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

    private fun oklabToSrgb(l: Float, a: Float, b: Float): FloatArray {
        val linear = OklchConverter.oklabToLinearSrgb(l, a, b)
        val r = OklchConverter.linearToSrgb(linear[0].coerceIn(0f, 1f))
        val g = OklchConverter.linearToSrgb(linear[1].coerceIn(0f, 1f))
        val bSrgb = OklchConverter.linearToSrgb(linear[2].coerceIn(0f, 1f))
        return floatArrayOf(r, g, bSrgb)
    }
}
