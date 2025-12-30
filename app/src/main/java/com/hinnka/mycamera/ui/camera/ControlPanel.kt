package com.hinnka.mycamera.ui.camera

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinnka.mycamera.R
import com.hinnka.mycamera.camera.AspectRatio
import com.hinnka.mycamera.camera.CameraState
import com.hinnka.mycamera.camera.CameraUtils
import com.hinnka.mycamera.ui.components.DialControl

/**
 * 控制面板组件
 */
@Composable
fun ControlPanel(
    state: CameraState,
    onExposureCompensationChange: (Int) -> Unit,
    onIsoChange: (Int) -> Unit,
    onShutterSpeedChange: (Long) -> Unit,
    onZoomChange: (Float) -> Unit,
    onAspectRatioChange: (AspectRatio) -> Unit,
    onAutoExposureToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAspectRatioSelector by remember { mutableStateOf(false) }
    var showExposureControls by remember { mutableStateOf(false) }
    
    val cameraInfo = state.getCurrentCameraInfo()
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 顶部信息栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 画面比例按钮
            TextButton(
                onClick = { showAspectRatioSelector = !showAspectRatioSelector },
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
            ) {
                Text(
                    text = state.aspectRatio.getDisplayName(),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            
            // 变焦指示
            Text(
                text = "${String.format("%.1f", state.zoomRatio)}x",
                color = Color.White,
                fontSize = 14.sp
            )
            
            // 曝光控制开关
            TextButton(
                onClick = { showExposureControls = !showExposureControls },
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
            ) {
                Text(
                    text = if (state.isAutoExposure) stringResource(R.string.auto) else "M",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        
        // 画面比例选择器
        AnimatedVisibility(
            visible = showAspectRatioSelector,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            AspectRatioSelector(
                selected = state.aspectRatio,
                onSelect = {
                    onAspectRatioChange(it)
                    showAspectRatioSelector = false
                }
            )
        }
        
        // 曝光控制
        AnimatedVisibility(
            visible = showExposureControls,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            ExposureControls(
                state = state,
                onExposureCompensationChange = onExposureCompensationChange,
                onIsoChange = onIsoChange,
                onShutterSpeedChange = onShutterSpeedChange,
                onAutoExposureToggle = onAutoExposureToggle
            )
        }
        
        // 变焦滑块
        ZoomSlider(
            value = state.zoomRatio,
            maxValue = state.getMaxZoom(),
            onValueChange = onZoomChange
        )
    }
}

/**
 * 画面比例选择器
 */
@Composable
fun AspectRatioSelector(
    selected: AspectRatio,
    onSelect: (AspectRatio) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        AspectRatio.entries.forEach { ratio ->
            val isSelected = ratio == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isSelected) Color(0xFFFF6B35) else Color.Transparent
                    )
                    .border(
                        width = 1.dp,
                        color = if (isSelected) Color.Transparent else Color.White.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable { onSelect(ratio) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = ratio.getDisplayName(),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

/**
 * 曝光控制组件
 */
@Composable
fun ExposureControls(
    state: CameraState,
    onExposureCompensationChange: (Int) -> Unit,
    onIsoChange: (Int) -> Unit,
    onShutterSpeedChange: (Long) -> Unit,
    onAutoExposureToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val cameraInfo = state.getCurrentCameraInfo()
    val evRange = state.getExposureCompensationRange()
    val isoRange = state.getIsoRange()
    val shutterRange = state.getShutterSpeedRange()
    val evStep = cameraInfo?.exposureCompensationStep ?: 1/3f
    
    Column(
        modifier = modifier.padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 自动/手动切换
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.auto),
                color = Color.White,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = !state.isAutoExposure,
                onCheckedChange = { onAutoExposureToggle(!it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFFFF6B35),
                    checkedTrackColor = Color(0xFFFF6B35).copy(alpha = 0.5f)
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "M",
                color = Color.White,
                fontSize = 12.sp
            )
        }
        
        // 旋钮控制区
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // 曝光补偿
            DialControl(
                value = state.exposureCompensation.toFloat(),
                onValueChange = { onExposureCompensationChange(it.toInt()) },
                minValue = evRange.lower.toFloat(),
                maxValue = evRange.upper.toFloat(),
                steps = (evRange.upper - evRange.lower),
                label = stringResource(R.string.exposure_compensation),
                valueFormatter = { 
                    CameraUtils.formatExposureCompensation(it.toInt(), evStep)
                }
            )
            
            // ISO（仅手动模式）
            if (!state.isAutoExposure) {
                DialControl(
                    value = state.iso.toFloat(),
                    onValueChange = { onIsoChange(it.toInt()) },
                    minValue = isoRange.lower.toFloat(),
                    maxValue = isoRange.upper.toFloat(),
                    steps = 20,
                    label = stringResource(R.string.iso),
                    valueFormatter = { it.toInt().toString() }
                )
                
                // 快门速度
                DialControl(
                    value = state.shutterSpeed.toFloat(),
                    onValueChange = { onShutterSpeedChange(it.toLong()) },
                    minValue = shutterRange.lower.toFloat(),
                    maxValue = shutterRange.upper.toFloat(),
                    steps = 20,
                    label = stringResource(R.string.shutter_speed),
                    valueFormatter = { 
                        CameraUtils.formatShutterSpeed(it.toLong())
                    }
                )
            }
        }
    }
}

/**
 * 变焦滑块
 */
@Composable
fun ZoomSlider(
    value: Float,
    maxValue: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.zoom),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 10.sp
            )
            Text(
                text = "${String.format("%.1f", value)}x",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 1f..maxValue,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFFF6B35),
                activeTrackColor = Color(0xFFFF6B35),
                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
