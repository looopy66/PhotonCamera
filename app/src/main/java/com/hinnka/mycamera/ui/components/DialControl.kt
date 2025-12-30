package com.hinnka.mycamera.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 模拟物理旋钮的控件
 */
@Composable
fun DialControl(
    value: Float,
    onValueChange: (Float) -> Unit,
    minValue: Float,
    maxValue: Float,
    steps: Int = 20,
    label: String,
    valueFormatter: (Float) -> String = { it.toString() },
    modifier: Modifier = Modifier
) {
    var rotationAngle by remember { mutableFloatStateOf(0f) }
    var lastAngle by remember { mutableFloatStateOf(0f) }
    
    // 将值映射到角度 (-135° 到 135°)
    val valueRange = maxValue - minValue
    val normalizedValue = (value - minValue) / valueRange
    val targetAngle = -135f + normalizedValue * 270f
    
    // 动画
    val animatedAngle by animateFloatAsState(
        targetValue = targetAngle,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f),
        label = "dialRotation"
    )
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        // 标签
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
        
        Spacer(modifier = Modifier.height(4.dp))
        
        // 旋钮
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(80.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val centerX = size.width / 2f
                            val centerY = size.height / 2f
                            lastAngle = atan2(
                                offset.y - centerY,
                                offset.x - centerX
                            ) * (180f / PI.toFloat())
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val centerX = size.width / 2f
                            val centerY = size.height / 2f
                            val currentAngle = atan2(
                                change.position.y - centerY,
                                change.position.x - centerX
                            ) * (180f / PI.toFloat())
                            
                            var delta = currentAngle - lastAngle
                            // 处理角度跳跃
                            if (delta > 180) delta -= 360
                            if (delta < -180) delta += 360
                            
                            rotationAngle += delta
                            lastAngle = currentAngle
                            
                            // 将旋转角度映射回值
                            val newNormalizedValue = ((rotationAngle + 135f) / 270f).coerceIn(0f, 1f)
                            val newValue = minValue + newNormalizedValue * valueRange
                            onValueChange(newValue)
                        }
                    )
                }
        ) {
            // 刻度盘
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2, size.height / 2)
                val outerRadius = size.minDimension / 2 - 4.dp.toPx()
                val innerRadius = outerRadius - 8.dp.toPx()
                
                // 绘制刻度
                for (i in 0..steps) {
                    val angle = -135f + (i.toFloat() / steps) * 270f
                    val radian = angle * PI.toFloat() / 180f
                    
                    val isMainTick = i % 5 == 0
                    val tickLength = if (isMainTick) 6.dp.toPx() else 3.dp.toPx()
                    val tickWidth = if (isMainTick) 2f else 1f
                    val tickAlpha = if (isMainTick) 0.8f else 0.4f
                    
                    val startRadius = outerRadius - tickLength
                    val startX = center.x + cos(radian) * startRadius
                    val startY = center.y + sin(radian) * startRadius
                    val endX = center.x + cos(radian) * outerRadius
                    val endY = center.y + sin(radian) * outerRadius
                    
                    drawLine(
                        color = Color.White.copy(alpha = tickAlpha),
                        start = Offset(startX, startY),
                        end = Offset(endX, endY),
                        strokeWidth = tickWidth,
                        cap = StrokeCap.Round
                    )
                }
                
                // 绘制指针
                rotate(animatedAngle) {
                    val pointerLength = innerRadius - 10.dp.toPx()
                    drawLine(
                        color = Color(0xFFFF6B35),
                        start = center,
                        end = Offset(center.x, center.y - pointerLength),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    
                    // 中心点
                    drawCircle(
                        color = Color.White,
                        radius = 4.dp.toPx(),
                        center = center
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        // 当前值
        Text(
            text = valueFormatter(value),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 对焦指示器
 */
@Composable
fun FocusIndicator(
    position: Pair<Float, Float>?,
    isFocusing: Boolean,
    focusSuccess: Boolean?,
    modifier: Modifier = Modifier
) {
    if (position == null) return
    
    val infiniteTransition = rememberInfiniteTransition(label = "focusAnimation")
    
    // 聚焦动画
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isFocusing) 0.8f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(300),
            repeatMode = RepeatMode.Reverse
        ),
        label = "focusScale"
    )
    
    // 透明度动画
    val alpha by animateFloatAsState(
        targetValue = when {
            isFocusing -> 1f
            focusSuccess == true -> 0.8f
            focusSuccess == false -> 0.5f
            else -> 0f
        },
        animationSpec = tween(300),
        label = "focusAlpha"
    )
    
    // 颜色
    val color = when {
        isFocusing -> Color.White
        focusSuccess == true -> Color.Green
        else -> Color.Red
    }
    
    // 自动隐藏
    LaunchedEffect(focusSuccess) {
        if (focusSuccess != null) {
            kotlinx.coroutines.delay(1500)
            // 这里应该通知父组件清除对焦点，但为简化暂时不处理
        }
    }
    
    Canvas(
        modifier = modifier.fillMaxSize()
    ) {
        val x = position.first * size.width
        val y = position.second * size.height
        val boxSize = 60.dp.toPx() * scale
        val halfSize = boxSize / 2
        val cornerLength = 15.dp.toPx()
        val strokeWidth = 2.dp.toPx()
        
        val drawColor = color.copy(alpha = alpha)
        
        // 左上角
        drawLine(
            color = drawColor,
            start = Offset(x - halfSize, y - halfSize),
            end = Offset(x - halfSize + cornerLength, y - halfSize),
            strokeWidth = strokeWidth
        )
        drawLine(
            color = drawColor,
            start = Offset(x - halfSize, y - halfSize),
            end = Offset(x - halfSize, y - halfSize + cornerLength),
            strokeWidth = strokeWidth
        )
        
        // 右上角
        drawLine(
            color = drawColor,
            start = Offset(x + halfSize, y - halfSize),
            end = Offset(x + halfSize - cornerLength, y - halfSize),
            strokeWidth = strokeWidth
        )
        drawLine(
            color = drawColor,
            start = Offset(x + halfSize, y - halfSize),
            end = Offset(x + halfSize, y - halfSize + cornerLength),
            strokeWidth = strokeWidth
        )
        
        // 左下角
        drawLine(
            color = drawColor,
            start = Offset(x - halfSize, y + halfSize),
            end = Offset(x - halfSize + cornerLength, y + halfSize),
            strokeWidth = strokeWidth
        )
        drawLine(
            color = drawColor,
            start = Offset(x - halfSize, y + halfSize),
            end = Offset(x - halfSize, y + halfSize - cornerLength),
            strokeWidth = strokeWidth
        )
        
        // 右下角
        drawLine(
            color = drawColor,
            start = Offset(x + halfSize, y + halfSize),
            end = Offset(x + halfSize - cornerLength, y + halfSize),
            strokeWidth = strokeWidth
        )
        drawLine(
            color = drawColor,
            start = Offset(x + halfSize, y + halfSize),
            end = Offset(x + halfSize, y + halfSize - cornerLength),
            strokeWidth = strokeWidth
        )
    }
}
