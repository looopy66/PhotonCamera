package com.hinnka.mycamera.ui.camera

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import com.hinnka.mycamera.camera.AspectRatio
import com.hinnka.mycamera.ui.components.FocusIndicator

/**
 * 相机预览组件
 */
@Composable
fun CameraPreview(
    aspectRatio: AspectRatio,
    previewSize: Size,
    focusPoint: Pair<Float, Float>?,
    isFocusing: Boolean,
    focusSuccess: Boolean?,
    onSurfaceReady: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onTap: (Float, Float, Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {

    // 计算预览区域尺寸，保持目标比例
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        val containerWidth = constraints.maxWidth.toFloat()
        val containerHeight = constraints.maxHeight.toFloat()
        
        // 目标显示比例
        val targetRatio = aspectRatio.getValue(false)
        
        // 计算裁切后的显示区域大小
        val displayWidth: Float
        val displayHeight: Float

        if (containerWidth / containerHeight > targetRatio) {
            // 容器更宽，以高度为基准
            displayHeight = containerHeight
            displayWidth = displayHeight * targetRatio
        } else {
            // 容器更高，以宽度为基准
            displayWidth = containerWidth
            displayHeight = displayWidth / targetRatio
        }
        
        var viewWidth by remember { mutableIntStateOf(0) }
        var viewHeight by remember { mutableIntStateOf(0) }

        var previewSizeState by remember { mutableStateOf(previewSize) }
        
        Box(
            modifier = Modifier
                .width(with(LocalDensity.current) { displayWidth.toDp() })
                .height(with(LocalDensity.current) { displayHeight.toDp() })
                .clipToBounds()  // 裁切超出部分
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        onTap(offset.x, offset.y, viewWidth, viewHeight)
                    }
                }
        ) {
            // TextureView 用于相机预览
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(
                                surfaceTexture: SurfaceTexture,
                                width: Int,
                                height: Int
                            ) {
                                viewWidth = width
                                viewHeight = height
                                // 设置固定的预览尺寸
                                surfaceTexture.setDefaultBufferSize(previewSizeState.width, previewSizeState.height)
                                // 应用 centerCrop 变换矩阵
                                applyCenterCropTransform(this@apply, previewSizeState, width, height)
                                // 通知相机 Surface 已准备好
                                onSurfaceReady(Surface(surfaceTexture))
                            }
                            
                            override fun onSurfaceTextureSizeChanged(
                                surfaceTexture: SurfaceTexture,
                                width: Int,
                                height: Int
                            ) {
                                viewWidth = width
                                viewHeight = height
                                surfaceTexture.setDefaultBufferSize(previewSizeState.width, previewSizeState.height)
                                // 只更新变换矩阵，不重新配置相机
                                applyCenterCropTransform(this@apply, previewSizeState, width, height)
                            }
                            
                            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                                onSurfaceDestroyed()
                                return true
                            }
                            
                            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
                                // 不需要处理
                            }
                        }
                    }
                },
                update = { textureView ->
                    // 当显示区域尺寸变化时（比例切换），只更新变换矩阵
                    if (textureView.width > 0 && textureView.height > 0) {
                        previewSizeState = previewSize
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            
            // 对焦指示器
            FocusIndicator(
                position = focusPoint,
                isFocusing = isFocusing,
                focusSuccess = focusSuccess,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * 应用 centerCrop 变换矩阵
 * 当预览尺寸与显示区域比例不匹配时，放大画面并裁切超出部分
 */
private fun applyCenterCropTransform(
    textureView: TextureView,
    previewSize: Size,
    viewWidth: Int,
    viewHeight: Int
) {
    if (viewWidth == 0 || viewHeight == 0) return
    
    // 预览尺寸是横向的（如 1920x1080），但显示区域是竖向的
    // 相机预览会自动旋转，所以这里需要交换预览的宽高来计算比例
    val previewWidth = previewSize.height.toFloat()
    val previewHeight = previewSize.width.toFloat()
    
    val viewRatio = viewWidth.toFloat() / viewHeight
    val previewRatio = previewWidth / previewHeight
    
    val matrix = Matrix()
    
    if (previewRatio != viewRatio) {
        val scaleX: Float
        val scaleY: Float
        
        if (viewRatio > previewRatio) {
            // 显示区域更宽，需要基于宽度缩放，裁切上下
            scaleX = 1f
            scaleY = viewRatio / previewRatio
        } else {
            // 显示区域更高，需要基于高度缩放，裁切左右
            scaleX = previewRatio / viewRatio
            scaleY = 1f
        }
        
        // 以中心点进行缩放
        val pivotX = viewWidth / 2f
        val pivotY = viewHeight / 2f
        matrix.setScale(scaleX, scaleY, pivotX, pivotY)
    }
    
    textureView.setTransform(matrix)
}

