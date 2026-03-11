package com.hinnka.mycamera.lut.creator

import android.graphics.Bitmap
import android.util.Base64
import com.google.gson.Gson
import com.hinnka.mycamera.utils.PLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import androidx.core.graphics.scale

class OpenAIApiClient(
    private val apiKey: String,
    private val baseUrl: String
) {

    companion object {
        const val BUILT_IN_API_URL = "https://camera-api.hinnka.com/v1"
        const val BUILT_IN_API_KEY = "ix8wzecbrapNdJqSlumyhY31JuFiuh/fzHFsEJThYtg="
        const val BUILT_IN_IMAGE_MODEL = "gemini-3.1-flash-image-preview"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun getAvailableModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            // Remove /chat/completions style trailing slash if it exists and append /models
            val base = if (baseUrl.endsWith("/")) baseUrl.dropLast(1) else baseUrl
            val requestUrl = "$base/models"

            val request = Request.Builder()
                .url(requestUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("API request failed: ${response.code} ${response.body?.string()}"))
            }

            val bodyString = response.body?.string() ?: ""
            val jsonObject = JSONObject(bodyString)
            val dataArray = jsonObject.getJSONArray("data")

            val models = mutableListOf<String>()
            for (i in 0 until dataArray.length()) {
                val modelObj = dataArray.getJSONObject(i)
                val id = modelObj.optString("id")
                if (id.isNotEmpty()) {
                    models.add(id)
                }
            }

            // Standard OpenAI models include embedding models etc, sort them optionally or just return all text models
            Result.success(models)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun generateOriginalImage(
        bitmap: Bitmap,
        isBuiltIn: Boolean,
        model: String,
        customPrompt: String = ""
    ): Result<Bitmap> =
        withContext(Dispatchers.IO) {
            try {
                val base64Image = bitmapToBase64(bitmap)

                val jsonObject = JSONObject().apply {
                    val contents = JSONArray().apply {
                        val content = JSONObject().apply {
                            val parts = JSONArray().apply {
                                val textPart = JSONObject().apply {
                                    put(
                                        "text",
                                        "Restore this image to its original natural version. Remove all cinematic filters, LUTs, and color grading. Return a high-quality, realistic photo with natural colors and neutral white balance. $customPrompt"
                                    )
                                }
                                val imagePart = JSONObject().apply {
                                    put("inline_data", JSONObject().apply {
                                        put("mime_type", "image/jpeg")
                                        put("data", base64Image)
                                    })
                                }
                                put(textPart)
                                put(imagePart)
                            }
                            put("parts", parts)
                        }
                        put(content)
                    }
                    put("contents", contents)
                }

                val requestBody =
                    jsonObject.toString().toRequestBody("application/json".toMediaType())


                val request = if (isBuiltIn) {
                    Request.Builder()
                        .url("$BUILT_IN_API_URL/images/generations")
                        .addHeader("Authorization", "Bearer $apiKey")
                        .post(requestBody)
                        .build()
                } else {
                    Request.Builder()
                        .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                        .addHeader("x-goog-api-key", apiKey)
                        .post(requestBody)
                        .build()
                }

                val response = client.newCall(request).execute()
                PLog.d("OpenAIApiClient", "Response: ${response.code}")
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    return@withContext Result.failure(Exception("API failed: ${response.code}\n$errorBody"))
                }

                val responseBodyString = response.body?.string() ?: ""
                // PLog.d("OpenAIApiClient", "Response: ${responseBodyString.take(500)}...") // Only log first 500 chars to avoid memory issues
                val jsonResponse = JSONObject(responseBodyString)

                // Handle native Gemini image response format
                // The API actually uses "inlineData" with camelCase in response, while request uses snake_case "inline_data"
                val candidates = jsonResponse.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    return@withContext Result.failure(Exception("No candidates in response"))
                }

                val parts =
                    candidates.getJSONObject(0).getJSONObject("content").getJSONArray("parts")
                var b64Data: String? = null

                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    if (part.has("inlineData")) {
                        b64Data = part.getJSONObject("inlineData").getString("data")
                        break
                    }
                }

                if (b64Data == null) {
                    return@withContext Result.failure(Exception("No image data found in AI response. Response: $responseBodyString"))
                }

                val imageBytes = Base64.decode(b64Data, Base64.DEFAULT)
                val decodedBitmap =
                    android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

                Result.success(decodedBitmap ?: throw Exception("Failed to decode generated image"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        // Resize bitmap to save token constraints if it's too large
        val maxDim = 1024
        var w = bitmap.width
        var h = bitmap.height
        if (w > maxDim || h > maxDim) {
            val scale = maxDim.toFloat() / Math.max(w, h)
            w = (w * scale).toInt()
            h = (h * scale).toInt()
        }

        val resized = if (w != bitmap.width || h != bitmap.height) {
            bitmap.scale(w, h)
        } else {
            bitmap
        }

        val outputStream = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }
}
