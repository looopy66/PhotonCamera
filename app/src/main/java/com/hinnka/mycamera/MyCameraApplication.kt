package com.hinnka.mycamera

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.hinnka.mycamera.gallery.TiffDecoder
import com.hinnka.mycamera.utils.BuglyHelper

class MyCameraApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()

        BuglyHelper.init(this)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                // 注册TIFF Decoder
                add(TiffDecoder.Companion.Factory())
            }
            .build()
    }
}