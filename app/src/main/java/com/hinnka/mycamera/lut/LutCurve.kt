package com.hinnka.mycamera.lut

import kotlin.math.*

enum class LutCurve {
    SRGB,
    LINEAR,
    V_LOG,
    S_LOG3,
    F_LOG2,
    LOG_C,
    APPLE_LOG,
    HLG;

    fun fromLinear(l: Float): Float {
        val linear = l.coerceIn(0f, 1f)
        return when (this) {
            SRGB -> {
                if (linear <= 0.0031308f) 12.92f * linear
                else 1.055f * linear.pow(1f / 2.4f) - 0.055f
            }
            LINEAR -> linear
            V_LOG -> {
                if (linear < 0.01f) 5.6f * linear + 0.125f
                else 0.241514f * log10(linear + 0.00873f) + 0.598206f
            }
            S_LOG3 -> {
                if (linear < 0.01125f) (linear * (171.2102946929f - 95f) / 0.01125f + 95f) / 1023f
                else (420f + log10((linear + 0.01f) / (0.18f + 0.01f)) * 261.5f) / 1023f
            }
            F_LOG2 -> {
                // F-Log2 formula from Fujifilm
                if (linear < 0.00089f) 8.799461f * linear + 0.092864f
                else 0.245281f * log10(5.555556f * linear + 0.064829f) + 0.384316f
            }
            LOG_C -> {
                if (linear > 0.010591f) 0.247190f * log10(5.555556f * linear + 0.052272f) + 0.385537f
                else 5.367655f * linear + 0.092809f
            }
            APPLE_LOG -> {
                // Apple Log formula from Apple Log Profile White Paper (September 2023)
                // R0 = -0.05641088, Rt = 0.01
                // c = 47.28711236, beta = 0.00964052, gamma = 0.08550479, delta = 0.69336945
                if (linear >= 0.01f) {
                    0.08550479f * log2(linear + 0.00964052f) + 0.69336945f
                } else if (linear >= -0.05641088f) {
                    47.28711236f * (linear + 0.05641088f).pow(2f)
                } else {
                    0f
                }
            }
            HLG -> {
                val a = 0.17883277f
                val b = 1f - 4f * a
                val c = 0.5f - a * ln(4f * a)
                if (linear <= 1f / 12f) sqrt(3f * linear)
                else a * ln(12f * linear - b) + c
            }
        }
    }

    fun toLinear(v: Float): Float {
        val value = v.coerceIn(0f, 1f)
        return when (this) {
            SRGB -> {
                if (value <= 0.04045f) value / 12.92f
                else ((value + 0.055f) / 1.055f).pow(2.4f)
            }
            LINEAR -> value
            V_LOG -> {
                if (value < 0.181f) (value - 0.125f) / 5.6f
                else 10f.pow((value - 0.598206f) / 0.241514f) - 0.00873f
            }
            S_LOG3 -> {
                if (value < 171.2102946929f / 1023f) (value * 1023f - 95f) * 0.01125f / (171.2102946929f - 95f)
                else 10f.pow((value * 1023f - 420f) / 261.5f) * (0.18f + 0.01f) - 0.01f
            }
            F_LOG2 -> {
                if (value < 0.100686685370811f) (value - 0.092864f) / 8.799461f
                else (10f.pow((value - 0.384316f) / 0.245281f) - 0.064829f) / 5.555556f
            }
            LOG_C -> {
                if (value > 0.149658f) (10f.pow((value - 0.385537f) / 0.247190f) - 0.052272f) / 5.555556f
                else (value - 0.092809f) / 5.367655f
            }
            APPLE_LOG -> {
                // Pt = c * (Rt - R0)^2 = 47.28711236 * (0.01 + 0.05641088)^2 ≈ 0.2088
                val pt = 0.20883119f
                if (value >= 0.20883119f) {
                    2.0f.pow((value - 0.69336945f) / 0.08550479f) - 0.00964052f
                } else if (value > 0f) {
                    sqrt(value / 47.28711236f) - 0.05641088f
                } else {
                    -0.05641088f
                }
            }
            HLG -> {
                val a = 0.17883277f
                val b = 1f - 4f * a
                val c = 0.5f - a * ln(4f * a)
                if (value <= 0.5f) (value * value) / 3f
                else (exp((value - c) / a) + b) / 12f
            }
        }
    }
}
