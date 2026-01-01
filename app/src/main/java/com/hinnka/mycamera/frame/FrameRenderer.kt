package com.hinnka.mycamera.frame

import android.content.Context
import android.graphics.*
import android.os.Build
import android.util.Log
import android.util.TypedValue
import androidx.core.graphics.drawable.toBitmap
import com.hinnka.mycamera.R
import java.text.SimpleDateFormat
import java.util.*

/**
 * EXIF 元数据
 * 
 * 从照片中提取的拍摄信息
 */
data class ExifMetadata(
    val deviceModel: String? = null,
    val brand: String? = null,
    val dateTaken: Long? = null,
    val location: String? = null,
    val iso: Int? = null,
    val shutterSpeed: String? = null,
    val focalLength: String? = null,
    val aperture: String? = null,
    val width: Int = 0,
    val height: Int = 0
) {
    val resolution: String
        get() = "${width}x${height}"
    
    companion object {
        /**
         * 从系统信息创建默认元数据
         */
        fun createDefault(width: Int, height: Int): ExifMetadata {
            return ExifMetadata(
                deviceModel = Build.MODEL,
                brand = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
                dateTaken = System.currentTimeMillis(),
                width = width,
                height = height
            )
        }
    }
}

/**
 * 边框渲染器
 * 
 * 使用 Android Canvas 渲染带边框水印的照片
 */
class FrameRenderer(private val context: Context) {
    
    companion object {
        private const val TAG = "FrameRenderer"
    }
    
    // 缓存的 Paint 对象
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }
    
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    
    /**
     * 渲染带边框的照片
     * 
     * @param originalBitmap 原始照片
     * @param template 边框模板
     * @param metadata EXIF 元数据
     * @param showAppBranding 是否显示 App 品牌
     * @return 带边框的照片
     */
    fun render(
        originalBitmap: Bitmap,
        template: FrameTemplate,
        metadata: ExifMetadata,
        showAppBranding: Boolean = true
    ): Bitmap {
        val layout = template.layout
        val frameHeight = dpToPx(layout.heightDp)
        val padding = dpToPx(layout.paddingDp)
        
        // 计算输出尺寸
        val outputWidth = originalBitmap.width
        val outputHeight = when (layout.position) {
            FramePosition.BOTTOM -> originalBitmap.height + frameHeight
            FramePosition.TOP -> originalBitmap.height + frameHeight
            FramePosition.BOTH -> originalBitmap.height + frameHeight * 2
        }
        
        // 创建输出 Bitmap
        val output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        
        // 绘制背景
        backgroundPaint.color = layout.backgroundColor
        canvas.drawRect(0f, 0f, outputWidth.toFloat(), outputHeight.toFloat(), backgroundPaint)
        
        // 绘制原图
        val photoTop = when (layout.position) {
            FramePosition.BOTTOM -> 0f
            FramePosition.TOP -> frameHeight.toFloat()
            FramePosition.BOTH -> frameHeight.toFloat()
        }
        canvas.drawBitmap(originalBitmap, 0f, photoTop, null)
        
        // 绘制边框内容
        when (layout.position) {
            FramePosition.BOTTOM -> {
                drawFrameContent(
                    canvas, template.elements, metadata, showAppBranding,
                    left = padding.toFloat(),
                    top = originalBitmap.height.toFloat(),
                    right = (outputWidth - padding).toFloat(),
                    bottom = outputHeight.toFloat(),
                    frameHeight = frameHeight
                )
            }
            FramePosition.TOP -> {
                drawFrameContent(
                    canvas, template.elements, metadata, showAppBranding,
                    left = padding.toFloat(),
                    top = 0f,
                    right = (outputWidth - padding).toFloat(),
                    bottom = frameHeight.toFloat(),
                    frameHeight = frameHeight
                )
            }
            FramePosition.BOTH -> {
                // 顶部
                drawFrameContent(
                    canvas, template.elements, metadata, showAppBranding,
                    left = padding.toFloat(),
                    top = 0f,
                    right = (outputWidth - padding).toFloat(),
                    bottom = frameHeight.toFloat(),
                    frameHeight = frameHeight
                )
                // 底部
                drawFrameContent(
                    canvas, template.elements, metadata, showAppBranding,
                    left = padding.toFloat(),
                    top = (originalBitmap.height + frameHeight).toFloat(),
                    right = (outputWidth - padding).toFloat(),
                    bottom = outputHeight.toFloat(),
                    frameHeight = frameHeight
                )
            }
        }
        
        return output
    }
    
    /**
     * 绘制边框内容
     */
    private fun drawFrameContent(
        canvas: Canvas,
        elements: List<FrameElement>,
        metadata: ExifMetadata,
        showAppBranding: Boolean,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        frameHeight: Int
    ) {
        // 将元素按对齐方式分组
        val startElements = elements.filter { getAlignment(it) == ElementAlignment.START }
        val centerElements = elements.filter { getAlignment(it) == ElementAlignment.CENTER }
        val endElements = elements.filter { getAlignment(it) == ElementAlignment.END }
        
        val centerY = top + frameHeight / 2f
        
        // 绘制左侧元素
        var currentX = left
        for (element in startElements) {
            currentX = drawElement(canvas, element, metadata, showAppBranding, currentX, centerY, true)
        }
        
        // 绘制右侧元素（从右向左）
        currentX = right
        for (element in endElements.reversed()) {
            currentX = drawElement(canvas, element, metadata, showAppBranding, currentX, centerY, false)
        }
        
        // 绘制中间元素
        val centerWidth = measureElementsWidth(centerElements, metadata, showAppBranding)
        currentX = left + (right - left - centerWidth) / 2f
        for (element in centerElements) {
            currentX = drawElement(canvas, element, metadata, showAppBranding, currentX, centerY, true)
        }
    }
    
    /**
     * 获取元素对齐方式
     */
    private fun getAlignment(element: FrameElement): ElementAlignment {
        return when (element) {
            is FrameElement.Text -> element.alignment
            is FrameElement.Logo -> element.alignment
            is FrameElement.Divider -> element.alignment
            is FrameElement.Spacer -> ElementAlignment.START
        }
    }
    
    /**
     * 测量元素组的总宽度
     */
    private fun measureElementsWidth(
        elements: List<FrameElement>,
        metadata: ExifMetadata,
        showAppBranding: Boolean
    ): Float {
        var totalWidth = 0f
        for (element in elements) {
            totalWidth += measureElementWidth(element, metadata, showAppBranding)
        }
        return totalWidth
    }
    
    /**
     * 测量单个元素宽度
     */
    private fun measureElementWidth(
        element: FrameElement,
        metadata: ExifMetadata,
        showAppBranding: Boolean
    ): Float {
        return when (element) {
            is FrameElement.Text -> {
                val text = getTextContent(element, metadata, showAppBranding) ?: return 0f
                textPaint.textSize = spToPx(element.fontSizeSp).toFloat()
                textPaint.typeface = getTypeface(element.fontWeight)
                textPaint.measureText(text) + dpToPx(8)
            }
            is FrameElement.Logo -> {
                dpToPx(element.sizeDp).toFloat() + dpToPx(8)
            }
            is FrameElement.Divider -> {
                if (element.orientation == DividerOrientation.VERTICAL) {
                    dpToPx(element.thicknessDp).toFloat() + dpToPx(element.marginDp * 2)
                } else {
                    0f
                }
            }
            is FrameElement.Spacer -> {
                dpToPx(element.widthDp).toFloat()
            }
        }
    }
    
    /**
     * 绘制单个元素
     * 
     * @return 下一个元素的 X 位置
     */
    private fun drawElement(
        canvas: Canvas,
        element: FrameElement,
        metadata: ExifMetadata,
        showAppBranding: Boolean,
        x: Float,
        centerY: Float,
        leftToRight: Boolean
    ): Float {
        return when (element) {
            is FrameElement.Text -> drawTextElement(canvas, element, metadata, showAppBranding, x, centerY, leftToRight)
            is FrameElement.Logo -> drawLogoElement(canvas, element, showAppBranding, x, centerY, leftToRight, metadata)
            is FrameElement.Divider -> drawDividerElement(canvas, element, x, centerY, leftToRight)
            is FrameElement.Spacer -> {
                val width = dpToPx(element.widthDp)
                if (leftToRight) x + width else x - width
            }
        }
    }
    
    /**
     * 绘制文本元素
     */
    private fun drawTextElement(
        canvas: Canvas,
        element: FrameElement.Text,
        metadata: ExifMetadata,
        showAppBranding: Boolean,
        x: Float,
        centerY: Float,
        leftToRight: Boolean
    ): Float {
        val text = getTextContent(element, metadata, showAppBranding) ?: return x
        
        textPaint.color = element.color
        textPaint.textSize = spToPx(element.fontSizeSp).toFloat()
        textPaint.typeface = getTypeface(element.fontWeight)
        
        val textWidth = textPaint.measureText(text)
        val textHeight = textPaint.descent() - textPaint.ascent()
        val textY = centerY - (textPaint.descent() + textPaint.ascent()) / 2
        
        val drawX = if (leftToRight) x else x - textWidth
        canvas.drawText(text, drawX, textY, textPaint)
        
        val spacing = dpToPx(8)
        return if (leftToRight) x + textWidth + spacing else x - textWidth - spacing
    }
    
    /**
     * 获取文本内容
     */
    private fun getTextContent(
        element: FrameElement.Text,
        metadata: ExifMetadata,
        showAppBranding: Boolean
    ): String? {
        val content = when (element.textType) {
            TextType.DEVICE_MODEL -> metadata.deviceModel
            TextType.BRAND -> metadata.brand
            TextType.DATE -> metadata.dateTaken?.let { 
                formatDate(it, element.format ?: "yyyy.MM.dd") 
            }
            TextType.TIME -> metadata.dateTaken?.let { 
                formatDate(it, element.format ?: "HH:mm") 
            }
            TextType.DATETIME -> metadata.dateTaken?.let { 
                formatDate(it, element.format ?: "yyyy.MM.dd HH:mm") 
            }
            TextType.LOCATION -> metadata.location
            TextType.ISO -> metadata.iso?.let { "ISO $it" }
            TextType.SHUTTER_SPEED -> metadata.shutterSpeed
            TextType.FOCAL_LENGTH -> metadata.focalLength
            TextType.APERTURE -> metadata.aperture
            TextType.RESOLUTION -> metadata.resolution
            TextType.CUSTOM -> element.format
            TextType.APP_NAME -> if (showAppBranding) context.getString(R.string.app_name) else null
        } ?: return null
        
        val prefix = element.prefix ?: ""
        val suffix = element.suffix ?: ""
        return "$prefix$content$suffix"
    }
    
    /**
     * 绘制 Logo 元素
     */
    private fun drawLogoElement(
        canvas: Canvas,
        element: FrameElement.Logo,
        showAppBranding: Boolean,
        x: Float,
        centerY: Float,
        leftToRight: Boolean,
        metadata: ExifMetadata? = null
    ): Float {
        // 如果是 App Logo 且不显示品牌，则跳过
        if (element.logoType == LogoType.APP && !showAppBranding) {
            return x
        }
        
        val size = dpToPx(element.sizeDp)
        
        // 获取对应的 drawable
        val drawableRes = when (element.logoType) {
            LogoType.APP -> R.mipmap.ic_launcher
            LogoType.BRAND -> getBrandLogoDrawable(metadata?.brand)
        }
        
        try {
            val drawable = context.getDrawable(drawableRes) ?: return x
            val bitmap = drawable.toBitmap(size, size)
            
            // 应用 tint 颜色
            val tintedBitmap = if (element.tint != null) {
                val tinted = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                val canvas2 = Canvas(tinted)
                val paint = Paint().apply {
                    colorFilter = PorterDuffColorFilter(element.tint, PorterDuff.Mode.SRC_IN)
                }
                canvas2.drawBitmap(bitmap, 0f, 0f, paint)
                tinted
            } else {
                bitmap
            }
            
            val drawX = if (leftToRight) x else x - size
            val drawY = centerY - size / 2f
            
            canvas.drawBitmap(tintedBitmap, drawX, drawY, null)
            
            val spacing = dpToPx(8)
            return if (leftToRight) x + size + spacing else x - size - spacing
        } catch (e: Exception) {
            Log.e(TAG, "Failed to draw logo", e)
            return x
        }
    }
    
    /**
     * 根据品牌名获取对应的 Logo drawable
     * 
     * 注意：需要在 res/drawable 目录下添加对应的品牌 Logo 文件
     * 如 ic_brand_samsung.xml, ic_brand_xiaomi.xml 等
     * 未找到对应资源时使用通用图标
     */
    private fun getBrandLogoDrawable(brand: String?): Int {
        if (brand == null) return R.drawable.ic_brand_generic
        
        // 尝试获取品牌特定的 Logo
        val brandLower = brand.lowercase()
        val resourceName = when (brandLower) {
            "samsung" -> "ic_brand_samsung"
            "xiaomi", "redmi", "poco" -> "ic_brand_xiaomi"
            "huawei", "honor" -> "ic_brand_huawei"
            "oppo", "realme", "oneplus" -> "ic_brand_oppo"
            "vivo", "iqoo" -> "ic_brand_vivo"
            "google", "pixel" -> "ic_brand_google"
            "sony" -> "ic_brand_sony"
            "apple" -> "ic_brand_apple"
            "motorola", "moto" -> "ic_brand_motorola"
            "nokia" -> "ic_brand_nokia"
            "asus", "rog" -> "ic_brand_asus"
            "lg" -> "ic_brand_lg"
            "lenovo" -> "ic_brand_lenovo"
            "zte", "nubia" -> "ic_brand_zte"
            "meizu" -> "ic_brand_meizu"
            else -> null
        }
        
        if (resourceName != null) {
            // 尝试获取特定品牌资源
            val resId = context.resources.getIdentifier(
                resourceName, "drawable", context.packageName
            )
            if (resId != 0) return resId
        }
        
        // 使用通用品牌图标作为后备
        return R.drawable.ic_brand_generic
    }
    
    /**
     * 绘制分隔线元素
     */
    private fun drawDividerElement(
        canvas: Canvas,
        element: FrameElement.Divider,
        x: Float,
        centerY: Float,
        leftToRight: Boolean
    ): Float {
        linePaint.color = element.color
        linePaint.strokeWidth = dpToPx(element.thicknessDp).toFloat()
        
        val length = dpToPx(element.lengthDp)
        val margin = dpToPx(element.marginDp)
        
        val drawX = if (leftToRight) x + margin else x - margin
        
        if (element.orientation == DividerOrientation.VERTICAL) {
            canvas.drawLine(
                drawX, centerY - length / 2f,
                drawX, centerY + length / 2f,
                linePaint
            )
            return if (leftToRight) drawX + margin else drawX - margin
        } else {
            // 水平线（通常不常用）
            canvas.drawLine(
                drawX - length / 2f, centerY,
                drawX + length / 2f, centerY,
                linePaint
            )
            return x
        }
    }
    
    /**
     * 生成预览缩略图
     */
    fun renderPreview(
        originalBitmap: Bitmap,
        template: FrameTemplate,
        targetWidth: Int = 200
    ): Bitmap {
        // 缩放原图
        val scale = targetWidth.toFloat() / originalBitmap.width
        val scaledWidth = targetWidth
        val scaledHeight = (originalBitmap.height * scale).toInt()
        val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, scaledWidth, scaledHeight, true)
        
        // 渲染边框
        val metadata = ExifMetadata.createDefault(scaledWidth, scaledHeight)
        return render(scaledBitmap, template, metadata, showAppBranding = true)
    }
    
    // 工具方法
    
    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
    
    private fun spToPx(sp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            sp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
    
    private fun formatDate(timestamp: Long, format: String): String {
        return try {
            SimpleDateFormat(format, Locale.getDefault()).format(Date(timestamp))
        } catch (e: Exception) {
            ""
        }
    }
    
    private fun getTypeface(weight: FontWeight): Typeface {
        return when (weight) {
            FontWeight.NORMAL -> Typeface.DEFAULT
            FontWeight.MEDIUM -> Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            FontWeight.BOLD -> Typeface.DEFAULT_BOLD
        }
    }
}
