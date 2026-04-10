package com.hinnka.mycamera.raw

import kotlin.math.log10
import kotlin.math.pow

/**
 * Log 曲线配置
 * 
 * 公式:
 * if (x >= cut1) y = c * log10(a * x + b) + d
 * else y = e * x + f
 */
enum class LogCurve(
    val a: Float,
    val b: Float,
    val c: Float,
    val d: Float,
    val e: Float,
    val f: Float,
    val cut1: Float, // Linear 边界 (Reflection)
    val cut2: Float, // Log 边界 (IRE)
    val middleGray: Float, // 中性灰
    val maxLinear: Float, // 最大反射率
    val type: Int = 0 // 0=Linear-Log, 1=Quadratic-Log
) {
    LINEAR(
        1.0f, 0.0f, 1.0f, 0.0f, 1.0f, 0.0f,
        1000000.0f, 1000000.0f, 0.18f, 1.0f, 0
    ),
    FLOG2(
        // Fujifilm F-Log2 params
        5.555556f, 0.064829f, 0.245281f, 0.384316f, 8.799461f, 0.092864f,
        0.000889f, 0.100686685f, 0.391f, 58.28f, 0
    ),
    LOGC4(
        // ARRI LogC4 params (transformed to log10 structure)
        2231.8263f, 64.0f, 0.21524584f, -0.29590839f, 8.80302f, 0.158957f,
        -0.018057f, 0.0f, 0.2784f, 469.8f, 0
    ),
    APPLE_LOG(
        // Apple Log params (transformed to log10 structure)
        1.0f, 0.00964052f, 0.2840422f, 0.69336945f, 47.28711236f, -0.05641088f,
        0.01f, 0.20855365f, 0.49f, 12.03f, 1
    ),
    ACES_CCT(
        // ACEScct params (transformed to log10 structure)
        // y = (log2(x) + 9.72) / 17.52 = (log10(x)/log10(2) + 9.72) / 17.52
        //   = log10(x) * (1 / (17.52 * log10(2))) + (9.72 / 17.52)
        // c = 1 / (17.52 * 0.301029995) = 0.18955931
        1.0f, 0.0f, 0.18955931f, 0.5547945f, 10.540237f, 0.072905536f,
        0.0078125f, 0.15525114f, 0.4136f, 222.8609f, 0
    ),
    SRGB(
        // standard sRGB params (power-based upper)
        // Upper: 1.055 * x^(1/2.4) - 0.055, Toe: 12.92 * x
        1.055f, -0.055f, 0.41666667f, 0.0f, 12.92f, 0.0f,
        0.0031308f, 0.04045f, 0.46135f, 1.0f, 2
    ),
    SLOG3(
        // Sony S-Log3 params
        5.263158f, 0.052632f, 0.255621f, 0.410557f, 6.621944f, 0.092864f,
        0.01125f, 0.167361f, 0.410557f, 38.42f, 0
    ),
    VLOG(
        // Panasonic V-Log params
        1.0f, 0.00873f, 0.241514f, 0.598206f, 5.6f, 0.125f,
        0.01f, 0.181f, 0.4233f, 46.08f, 0
    );

    fun linearToLog(reflection: Float): Float {
        if (this == LINEAR) return reflection
        return if (reflection >= cut1) {
            if (type == 2) {
                // Power upper
                a * (reflection.toDouble()).pow(c.toDouble()).toFloat() + b
            } else {
                // Log upper
                c * log10(a * reflection.toDouble() + b).toFloat() + d
            }
        } else {
            if (type == 1) {
                // Quadratic toe
                e * (reflection - f).pow(2.0f)
            } else {
                // Linear toe
                e * reflection + f
            }
        }
    }

    fun logToLinear(logValue: Float): Float {
        if (this == LINEAR) return logValue
        return if (logValue >= cut2) {
            if (type == 2) {
                // Power upper inverse
                ((logValue - b) / a).toDouble().pow(1.0 / c.toDouble()).toFloat()
            } else {
                // Log upper inverse
                ((10.0.pow((logValue - d).toDouble() / c)) - b).toFloat() / a
            }
        } else {
            if (type == 1) {
                // Quadratic toe inverse
                (kotlin.math.sqrt(logValue.toDouble() / e.toDouble()) + f).toFloat()
            } else {
                // Linear toe inverse
                (logValue - f) / e
            }
        }
    }
}

val LogCurve.rawFolder: String?
    get() {
        return when (this) {
            LogCurve.FLOG2 -> "raw/flog2"
            LogCurve.LOGC4 -> "raw/arri"
            LogCurve.APPLE_LOG -> "raw/apple"
            LogCurve.ACES_CCT -> "raw/aces"
            LogCurve.SLOG3 -> "raw/slog3"
            LogCurve.VLOG -> "raw/vlog"
            LogCurve.SRGB -> "raw/srgb"
            else -> null
        }
    }