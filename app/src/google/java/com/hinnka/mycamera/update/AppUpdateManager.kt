package com.hinnka.mycamera.update

import android.content.Context
import java.io.File

data class AppUpdateRelease(
    val versionName: String?,
    val downloadUrl: String,
    val fileName: String
)

object AppUpdateManager {
    suspend fun checkForUpdate(currentVersion: String = ""): AppUpdateRelease? = null

    suspend fun downloadApk(context: Context, release: AppUpdateRelease): File {
        throw UnsupportedOperationException("App updates are disabled for the Google flavor.")
    }

    fun startInstall(context: Context, apkFile: File): Boolean = false
}
