package com.hinnka.mycamera.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.hinnka.mycamera.BuildConfig
import com.hinnka.mycamera.utils.PLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class AppUpdateRelease(
    val versionName: String?,
    val downloadUrl: String,
    val fileName: String
)

object AppUpdateManager {
    private const val TAG = "AppUpdateManager"
    private const val VERSION_CHECK_URL = "https://camera-api.hinnka.com/api/version/check"
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun checkForUpdate(currentVersion: String = BuildConfig.VERSION_NAME): AppUpdateRelease? =
        withContext(Dispatchers.IO) {
            val encodedVersion = URLEncoder.encode(currentVersion, "UTF-8")
            val request = Request.Builder()
                .url("$VERSION_CHECK_URL?current_version=$encodedVersion")
                .get()
                .build()

            runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        PLog.w(TAG, "Version check failed: code=${response.code}")
                        return@withContext null
                    }

                    val body = response.body?.string().orEmpty()
                    val root = JSONObject(body)
                    if (!root.optBoolean("has_update", false)) {
                        PLog.d(TAG, "No update available: current=$currentVersion")
                        return@withContext null
                    }

                    val asset = root.optJSONObject("asset")
                    val assetName = asset?.optString("name").orEmpty()
                    val downloadUrl = asset?.optString("download_url").orEmpty()
                    val isApkAsset = assetName.endsWith(".apk", ignoreCase = true) ||
                        downloadUrl.substringBefore("?").endsWith(".apk", ignoreCase = true)
                    if (downloadUrl.isBlank() || !isApkAsset) {
                        PLog.w(TAG, "Update found but APK asset is missing")
                        return@withContext null
                    }
                    val versionName = root.optString("version_name").takeIf { it.isNotBlank() }
                        ?: root.optString("tag_name").takeIf { it.isNotBlank() }

                    AppUpdateRelease(
                        versionName = versionName,
                        downloadUrl = downloadUrl,
                        fileName = assetName.takeIf { it.endsWith(".apk", ignoreCase = true) }
                            ?: "PhotonCamera-${versionName ?: currentVersion}-update.apk"
                    )
                }
            }.onFailure { error ->
                PLog.e(TAG, "Version check error", error)
            }.getOrNull()
        }

    suspend fun downloadApk(context: Context, release: AppUpdateRelease): File =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(release.downloadUrl)
                    .get()
                    .build()
                val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
                val apkFile = File(updateDir, release.fileName.sanitizeApkFileName())

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Download failed: code=${response.code}")
                    }

                    response.body?.byteStream()?.use { input ->
                        apkFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw IllegalStateException("Download body is empty")
                }

                PLog.d(TAG, "APK downloaded: ${apkFile.absolutePath}, size=${apkFile.length()}")
                apkFile
            }.onFailure { error ->
                PLog.e(TAG, "APK download error", error)
            }.getOrThrow()
        }

    fun startInstall(context: Context, apkFile: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val permissionIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return runCatching {
                context.startActivity(permissionIntent)
                false
            }.onFailure { error ->
                PLog.e(TAG, "Failed to open unknown app sources settings", error)
            }.getOrDefault(false)
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        return try {
            context.startActivity(installIntent)
            true
        } catch (error: ActivityNotFoundException) {
            PLog.e(TAG, "No installer activity found", error)
            false
        } catch (error: SecurityException) {
            PLog.e(TAG, "Installer launch denied", error)
            false
        }
    }

    private fun String.sanitizeApkFileName(): String {
        val safeName = replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (safeName.endsWith(".apk", ignoreCase = true)) safeName else "$safeName.apk"
    }
}
