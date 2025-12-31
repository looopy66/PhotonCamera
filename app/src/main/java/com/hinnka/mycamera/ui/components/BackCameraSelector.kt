package com.hinnka.mycamera.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import com.hinnka.mycamera.camera.CameraInfo
import com.hinnka.mycamera.camera.LensType

/**
 * 后置摄像头选择器
 * 显示可用的后置摄像头（广角、主摄、长焦）供用户切换
 */
@Composable
fun BackCameraSelector(
    backCameras: List<CameraInfo>,
    currentLensType: LensType,
    onLensSelected: (LensType) -> Unit,
    modifier: Modifier = Modifier
) {
    // 如果后置摄像头少于2个，不显示选择器
    if (backCameras.size < 2) return
    
    // 按镜头类型排序
    val sortedCameras = backCameras.sortedBy { camera ->
        when (camera.lensType) {
            LensType.BACK_ULTRA_WIDE -> 0
            LensType.BACK_MAIN -> 1
            LensType.BACK_TELEPHOTO -> 2
            else -> 3
        }
    }
    
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
        sortedCameras.forEach { camera ->
            LensButton(
                camera = camera,
                isSelected = camera.lensType == currentLensType,
                onClick = { onLensSelected(camera.lensType) }
            )
        }
    }
}

/**
 * 单个镜头按钮
 */
@Composable
private fun LensButton(
    camera: CameraInfo,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) Color.White else Color.Transparent,
        label = "lensBg"
    )
    
    val textColor by animateColorAsState(
        targetValue = if (isSelected) Color.Black else Color.White,
        label = "lensText"
    )
    
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.1f else 1f,
        animationSpec = spring(dampingRatio = 0.6f),
        label = "lensScale"
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
            text = getLensLabel(camera),
            color = textColor,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

/**
 * 获取镜头显示标签
 */
private fun getLensLabel(camera: CameraInfo): String {
    return when (camera.lensType) {
        LensType.BACK_ULTRA_WIDE -> {
            // 根据 intrinsicZoomRatio 显示
            val ratio = camera.intrinsicZoomRatio
            if (ratio > 0 && ratio < 1) {
                ".${(ratio * 10).toInt()}x"
            } else {
                ".5x"
            }
        }
        LensType.BACK_MAIN -> "1x"
        LensType.BACK_TELEPHOTO -> {
            // 根据 intrinsicZoomRatio 显示
            val ratio = camera.intrinsicZoomRatio
            if (ratio > 1) {
                "${ratio.toInt()}x"
            } else {
                "2x"
            }
        }
        LensType.FRONT -> "前置"
    }
}
