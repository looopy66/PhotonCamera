package com.hinnka.mycamera.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import com.hinnka.mycamera.R
import com.hinnka.mycamera.model.ColorRecipeParams
import com.hinnka.mycamera.model.RecipeParam

/**
 * 色彩配方控制面板
 *
 * 使用顶部 Tab 切换不同参数组
 */
@Composable
fun ColorRecipePanel(
    currentParams: ColorRecipeParams,
    onParamChange: (RecipeParam, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }

    val tabs = listOf(
        R.string.recipe_tab_light,
        R.string.recipe_tab_color,
        R.string.recipe_tab_texture,
    )
    val parameterGroups = listOf(
        listOf(
            RecipeParam.EXPOSURE,
            RecipeParam.CONTRAST,
            RecipeParam.HIGHLIGHTS,
            RecipeParam.SHADOWS,
        ),
        listOf(
            RecipeParam.SATURATION,
            RecipeParam.TEMPERATURE,
            RecipeParam.TINT,
            RecipeParam.COLOR
        ),
        listOf(
            RecipeParam.VIGNETTE,
            RecipeParam.FILM_GRAIN,
            RecipeParam.FADE,
            RecipeParam.BLEACH_BYPASS,
            RecipeParam.HALATION
        )
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(4.dp)
    ) {
        // 自定义 Tab 选择器 (Pill style)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.4f))
                .padding(4.dp)
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                tabs.forEachIndexed { index, title ->
                    val isSelected = selectedTabIndex == index
                    val backgroundColor by animateColorAsState(
                        if (isSelected) Color.White.copy(alpha = 0.2f) else Color.Transparent,
                        label = "tabBackground"
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(backgroundColor)
                            .clickable { selectedTabIndex = index },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(title),
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 当前选中的参数组
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Color.Black.copy(alpha = 0.3f),
                    RoundedCornerShape(8.dp)
                )
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            parameterGroups[selectedTabIndex].forEach { param ->
                key(param) {
                    ColorRecipeSlider(
                        param = param,
                        value = param.getValue(currentParams),
                        onValueChange = { newValue ->
                            onParamChange(param, newValue)
                        },
                        onDoubleTap = {
                            onParamChange(param, param.defaultValue)
                        }
                    )
                }
            }
        }
    }
}

/**
 * 色彩配方备注栏
 */
@Composable
fun ColorRecipeRemarksBar(
    remarks: String,
    onRemarksChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var isEditing by remember { mutableStateOf(false) }
    var text by remember(remarks) { mutableStateOf(remarks) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(isEditing) {
        if (isEditing) {
            focusRequester.requestFocus()
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f)) // 极简半透明白
            .border(
                width = 0.5.dp,
                color = if (isEditing) Color(0xFFFFD700).copy(alpha = 0.5f) else Color.White.copy(alpha = 0.1f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(enabled = !isEditing) { isEditing = true }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧提示图标
        Icon(
            imageVector = Icons.Default.Edit,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.3f),
            modifier = Modifier.size(14.dp)
        )

        Spacer(modifier = Modifier.width(10.dp))

        Box(modifier = Modifier.weight(1f)) {
            if (isEditing) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            onRemarksChange(text)
                            isEditing = false
                            focusManager.clearFocus()
                        }
                    ),
                    decorationBox = { innerTextField ->
                        if (text.isEmpty()) {
                            Text(
                                text = stringResource(R.string.recipe_placeholder_remarks),
                                color = Color.White.copy(alpha = 0.25f),
                                fontSize = 13.sp
                            )
                        }
                        innerTextField()
                    }
                )
            } else {
                Text(
                    text = if (remarks.isEmpty()) stringResource(R.string.recipe_placeholder_remarks) else remarks,
                    color = if (remarks.isEmpty()) Color.White.copy(alpha = 0.3f) else Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }

        // 状态切换按钮
        IconButton(
            onClick = {
                if (isEditing) {
                    onRemarksChange(text)
                    focusManager.clearFocus()
                }
                isEditing = !isEditing
            },
            modifier = Modifier.size(20.dp)
        ) {
            Icon(
                imageVector = if (isEditing) Icons.Default.Check else Icons.Default.Edit,
                contentDescription = null,
                tint = if (isEditing) Color(0xFFFFD700) else Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * 色彩配方参数滑块
 */
@Composable
fun ColorRecipeSlider(
    param: RecipeParam,
    value: Float,
    onValueChange: (Float) -> Unit,
    onDoubleTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(param.displayNameRes),
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f)
            )

            Text(
                text = formatParamValue(param, value),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
                modifier = Modifier.width(50.dp)
            )
        }

        CustomSliderThinThumb(
            value = value,
            onValueChange = onValueChange,
            onDoubleTap = onDoubleTap,
            valueRange = param.minValue..param.maxValue,
            thumbWidth = 3.dp,
            thumbHeight = 20.dp,
            trackHeight = 3.dp,
            activeTrackColor = getParamColor(param),
            inactiveTrackColor = Color.Gray.copy(alpha = 0.3f),
            thumbColor = Color.White,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * 格式化参数值显示
 */
private fun formatParamValue(param: RecipeParam, value: Float): String {
    return when (param) {
        RecipeParam.EXPOSURE -> String.format("%.1f EV", value)
        RecipeParam.CONTRAST,
        RecipeParam.SATURATION,
        RecipeParam.COLOR -> String.format("%.2f", value)

        RecipeParam.TEMPERATURE,
        RecipeParam.TINT,
        RecipeParam.HIGHLIGHTS,
        RecipeParam.SHADOWS,
        RecipeParam.VIGNETTE -> {
            if (value >= 0) {
                String.format("+%.2f", value)
            } else {
                String.format("%.2f", value)
            }
        }

        RecipeParam.FADE,
        RecipeParam.FILM_GRAIN,
        RecipeParam.BLEACH_BYPASS,
        RecipeParam.HALATION -> String.format("%.2f", value)

        RecipeParam.LUT_INTENSITY -> String.format("%.2f", value)
    }
}

/**
 * 获取参数对应的颜色（用于滑块）
 */
private fun getParamColor(param: RecipeParam): Color {
    return when (param) {
        RecipeParam.EXPOSURE -> Color(0xFFFFEB3B) // 黄色
        RecipeParam.CONTRAST -> Color(0xFF9C27B0) // 紫色
        RecipeParam.SATURATION -> Color(0xFFE91E63) // 粉色
        RecipeParam.TEMPERATURE -> Color(0xFFFF9800) // 橙色
        RecipeParam.TINT -> Color(0xFF4CAF50) // 绿色
        RecipeParam.FADE -> Color(0xFF607D8B) // 灰蓝色
        RecipeParam.COLOR -> Color(0xFF2196F3) // 蓝色
        RecipeParam.HIGHLIGHTS -> Color(0xFFF44336) // 红色
        RecipeParam.SHADOWS -> Color(0xFF3F51B5) // 深蓝色
        RecipeParam.FILM_GRAIN -> Color(0xFF9E9E9E) // 灰色
        RecipeParam.VIGNETTE -> Color(0xFF795548) // 棕色
        RecipeParam.BLEACH_BYPASS -> Color(0xFF00BCD4) // 青色
        RecipeParam.HALATION -> Color(0xFFFF7043) // 暖橙色（光晕）
        RecipeParam.LUT_INTENSITY -> Color(0xFF9E9E9E) // 灰色
    }
}
