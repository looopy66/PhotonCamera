package com.hinnka.mycamera.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 变焦档位选择器组件
 * 显示可用的变焦档位（如 0.5x, 1x, 2x）供用户选择
 * 通过变焦来切换广角/主摄/长焦
 */
@Composable
fun ZoomStepSelector(
    zoomSteps: List<Float>,
    currentZoom: Float,
    onZoomSelected: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    // 如果没有多个档位，不显示
    if (zoomSteps.size <= 1) return
    
    Row(
        modifier = modifier
            .background(
                Color.Black.copy(alpha = 0.5f),
                RoundedCornerShape(24.dp)
            )
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        zoomSteps.forEach { step ->
            ZoomStepButton(
                step = step,
                isSelected = isZoomStepSelected(currentZoom, step, zoomSteps),
                onClick = { onZoomSelected(step) }
            )
        }
    }
}

/**
 * 判断当前变焦是否选中了某个档位
 */
private fun isZoomStepSelected(currentZoom: Float, step: Float, allSteps: List<Float>): Boolean {
    // 找到最接近当前变焦的档位
    val closestStep = allSteps.minByOrNull { kotlin.math.abs(it - currentZoom) } ?: return false
    return kotlin.math.abs(step - closestStep) < 0.01f
}

/**
 * 单个变焦档位按钮
 */
@Composable
private fun ZoomStepButton(
    step: Float,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) Color.White else Color.Transparent,
        label = "zoomStepBg"
    )
    
    val textColor by animateColorAsState(
        targetValue = if (isSelected) Color.Black else Color.White,
        label = "zoomStepText"
    )
    
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.1f else 1f,
        animationSpec = spring(dampingRatio = 0.6f),
        label = "zoomStepScale"
    )
    
    Box(
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(20.dp))
            .background(backgroundColor)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = formatZoomStep(step),
            color = textColor,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

/**
 * 格式化变焦档位显示
 */
private fun formatZoomStep(step: Float): String {
    return when {
        step < 1f -> ".${(step * 10).toInt()}x"  // 0.5x -> ".5x"
        step == step.toInt().toFloat() -> "${step.toInt()}x"  // 2.0 -> "2x"
        else -> "${String.format("%.1f", step)}x"  // 1.5 -> "1.5x"
    }
}

// 保留旧的 LensSelector 作为别名以保持兼容
@Composable
fun LensSelector(
    backCameras: List<com.hinnka.mycamera.camera.CameraInfo>,
    currentLensType: com.hinnka.mycamera.camera.LensType,
    onLensSelected: (com.hinnka.mycamera.camera.LensType) -> Unit,
    modifier: Modifier = Modifier
) {
    // 此函数保留用于兼容，但不再显示
    // 请使用 ZoomStepSelector 替代
}
