package com.hinnka.mycamera.ui.gallery

import android.content.Context
import android.graphics.Bitmap
import android.util.AttributeSet
import android.widget.ImageView

class HdrZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ImageView(context, attrs) {

    private var currentBitmap: Bitmap? = null

    init {
        scaleType = ScaleType.FIT_CENTER
        adjustViewBounds = false
        isClickable = false
        isFocusable = false
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun setBitmap(bitmap: Bitmap?) {
        if (bitmap === currentBitmap) return
        currentBitmap = bitmap
        setImageBitmap(bitmap)
    }

    fun refreshForHdrMode() {
        postInvalidateOnAnimation()
    }

    fun rebindForHdrMode() {
        val bitmap = currentBitmap ?: return
        super.setImageBitmap(null)
        super.setImageBitmap(bitmap)
        postInvalidateOnAnimation()
    }
}
