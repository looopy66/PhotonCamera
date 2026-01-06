package com.hinnka.mycamera.utils

import android.os.Build

object DeviceUtil {
    val model: String
        get() {
            return SystemPropertiesUtil.get("ro.vivo.market.name")
                ?: SystemPropertiesUtil.get("ro.vendor.oplus.market.name")
                ?: SystemPropertiesUtil.get("ro.product.marketname")
                ?: SystemPropertiesUtil.get("ro.config.marketing_name")
                ?: SystemPropertiesUtil.get("ro.vendor.product.display")
                ?: SystemPropertiesUtil.get("ro.config.devicename")
                ?: SystemPropertiesUtil.get("ro.product.vendor.model")
                ?: Build.MODEL
        }
}