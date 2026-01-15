package com.hinnka.mycamera.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinnka.mycamera.R
import com.hinnka.mycamera.lut.LutInfo
import com.hinnka.mycamera.ui.camera.LutEditBottomSheet
import kotlinx.coroutines.launch

/**
 * LUT 选择器组件
 *
 * 显示可用的 LUT 列表，支持选择和预览
 */
@Composable
fun LutSelector(
    availableLuts: List<LutInfo>,
    currentLutId: String?,
    lutPreviewBitmaps: Map<String, Bitmap> = emptyMap(),
    onLutSelected: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var showLutEditDialog by remember { mutableStateOf(false) }
    
    // 在组件首次加载时滚动到当前选中的 LUT
    LaunchedEffect(currentLutId) {
        currentLutId?.let { lutId ->
            val selectedIndex = availableLuts.indexOfFirst { it.id == lutId }
            if (selectedIndex >= 2) {
                coroutineScope.launch {
                    // 使用 animateScrollTo 进行平滑滚动，将选中项滚动到可视区域中心
                    scrollState.animateScrollToItem(selectedIndex - 2)
                }
            }
        }
    }

    // 全局 LUT 编辑底部弹窗
    if (showLutEditDialog && currentLutId != null) {
        LutEditBottomSheet(
            lutId = currentLutId,
            onDismiss = {
                showLutEditDialog = false
            }
        )
    }
    
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        state = scrollState
    ) {
        // LUT 列表
        items(availableLuts) { lut ->
            LutItem(
                name = lut.getName(),
                previewBitmap = lutPreviewBitmaps[lut.id],
                isSelected = currentLutId == lut.id,
                isVip = lut.isVip,
                isCustom = !lut.isBuiltIn,  // 添加自定义标识
                onClick = {
                    if (currentLutId == lut.id) {
                        showLutEditDialog = true
                    } else {
                        onLutSelected(lut.id)
                    }
                }
            )
        }
    }
}

/**
 * 单个 LUT 选项
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LutItem(
    name: String,
    previewBitmap: Bitmap?,
    isSelected: Boolean,
    isVip: Boolean,
    onClick: () -> Unit,
    isNone: Boolean = false,
    isCustom: Boolean = false,  // 添加自定义标识参数
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (isSelected) {
        Color.White.copy(alpha = 0.3f)
    } else {
        Color.Black.copy(alpha = 0.5f)
    }

    val borderColor = if (isSelected) {
        Color.White
    } else {
        Color.Gray.copy(alpha = 0.5f)
    }

    Column(
        modifier = modifier
            .width(60.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable { onClick() }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 预览区域
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(4.dp))
                .then(
                    if (isNone) {
                        Modifier.background(Color.DarkGray)
                    } else if (previewBitmap != null) {
                        // 显示真实预览图
                        Modifier
                    } else {
                        // 占位符：模拟滤镜预览的渐变色
                        Modifier.background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF4A148C),
                                    Color(0xFF00897B),
                                    Color(0xFFFF6F00)
                                )
                            )
                        )
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            // 显示预览图片
            if (!isNone && previewBitmap != null) {
                Image(
                    bitmap = previewBitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (isNone) {
                Icon(
                    imageVector = Icons.Default.FilterNone,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(24.dp)
                )
            }

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(R.string.edit),
                        tint = Color(0xFFD7E1F1),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            if (isVip) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .background(
                            color = Color(0xFFFFD700),
                            shape = RoundedCornerShape(bottomStart = 4.dp)
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.billing_vip_tag),
                        color = Color.Black,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 8.sp
                    )
                }
            }

            // 自定义标识
            if (isCustom) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .background(
                            color = Color(0xFF4CAF50),  // 绿色表示自定义
                            shape = RoundedCornerShape(bottomEnd = 4.dp)
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.custom_tag),
                        color = Color.White,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 8.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 名称
        Text(
            text = name,
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}


/**
 * LUT 控制面板
 *
 * 包含 LUT 选择器和强度滑块
 */
@Composable
fun LutControlPanel(
    availableLuts: List<LutInfo>,
    currentLutId: String?,
    lutPreviewBitmaps: Map<String, Bitmap> = emptyMap(),
    onLutSelected: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // LUT 选择器
        LutSelector(
            availableLuts = availableLuts,
            currentLutId = currentLutId,
            lutPreviewBitmaps = lutPreviewBitmaps,
            onLutSelected = onLutSelected
        )
    }
}
