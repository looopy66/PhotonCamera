package com.hinnka.mycamera.lut.creator

import android.graphics.Bitmap
import android.util.Base64
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
    private val apiKey: String
) {

    companion object {
        const val BUILT_IN_API_URL = "https://camera-api.hinnka.com/v1"
        const val BUILT_IN_API_KEY = "ix8wzecbrapNdJqSlumyhY31JuFiuh/fzHFsEJThYtg="
        const val BUILT_IN_IMAGE_MODEL = "models/gemini-3.1-flash-image-preview"
        const val BUILT_IN_MODEL = "models/gemini-3-flash-preview"
        const val GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun getAvailableModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val requestUrl = "$GEMINI_API_URL/models?pageSize=1000"

            val request = Request.Builder()
                .url(requestUrl)
                .addHeader("x-goog-api-key", apiKey)
                .get()
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("API request failed: ${response.code} ${response.body?.string()}"))
            }

            val bodyString = response.body?.string() ?: ""
            val jsonObject = JSONObject(bodyString)
            val dataArray = jsonObject.getJSONArray("models")

            val models = mutableListOf<String>()
            for (i in 0 until dataArray.length()) {
                val modelObj = dataArray.getJSONObject(i)
                val id = modelObj.optString("name")
                if (id.isNotEmpty()) {
                    models.add(id)
                }
            }

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
                        .url("$GEMINI_API_URL/$model:generateContent")
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

    suspend fun evaluateImageQuality(
        bitmap: Bitmap,
        isBuiltIn: Boolean,
        model: String,
        localeTag: String
    ): Result<AiPhotoEvaluation> =
        withContext(Dispatchers.IO) {
            try {
                val base64Image = bitmapToBase64(bitmap)
                val prompt = """
You are an expert photography critic, curator, and competition judge. Provide a rigorous, discerning, and deeply observant critique. 

Use this rigorous grading scale:
- 90-100 (Masterpiece): Exceptional imagery with profound emotional impact, masterful visual hierarchy, and unforgettable narrative depth.
- 80-89 (Excellent): Compelling work with a strong authorial voice, deliberate use of light/geometry, and highly engaging subject matter.
- 70-79 (Competent): Good photography with clear intent and solid aesthetics, but lacks the distinctive 'punctum' or unique perspective to be truly memorable.
- 60-69 (Functional): Ordinary but acceptable. Serves as a clear record of a moment or subject, yet lacks significant atmospheric or aesthetic draw.
- 40-59 (Flawed): Weak execution or concept. Plagued by poor visual flow, distracting elements, missed timing, or lack of a clear focal point.
- 0-39 (Unsuccessful): Fundamental failures in both intent and execution, resulting in a visually confusing or entirely unengaging image.

The user's current system language is "$localeTag". The "summary" value MUST be written in that language.
Return JSON only, without markdown, using this exact schema:
{
  "overallScore": 0-100 integer,
  "imageQualityScore": 0-100 integer,
  "compositionScore": 0-100 integer,
  "subjectScore": 0-100 integer,
  "emotionScore": 0-100 integer,
  "summary": "one highly insightful, professional sentence summarizing the critique in the user's system language"
}

Scoring Guidelines:
- imageQualityScore: Evaluate tonal range, exposure choices, focus intent, and artifacting. Does the technical execution (whether pristine or intentionally degraded for mood) elevate the artistic vision?
- compositionScore: Assess visual hierarchy, framing, weight distribution, juxtaposition, and figure-to-ground relationship. Does the underlying geometry guide the eye powerfully?
- subjectScore: Judge the intrinsic intrigue of the subject, the timing of the capture, and how effectively the image isolates its core message without distraction.
- emotionScore: Measure the atmospheric depth, narrative power, mood, and the image's ability to evoke a visceral, lasting psychological response.
- overallScore: A holistic synthesis reflecting the image's total artistic, documentary, and viewing value.
                """.trimIndent()

                val jsonObject = JSONObject().apply {
                    val contents = JSONArray().apply {
                        val content = JSONObject().apply {
                            val parts = JSONArray().apply {
                                put(JSONObject().apply { put("text", prompt) })
                                put(JSONObject().apply {
                                    put("inline_data", JSONObject().apply {
                                        put("mime_type", "image/jpeg")
                                        put("data", base64Image)
                                    })
                                })
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
                        .url("$BUILT_IN_API_URL/chat/completions")
                        .addHeader("Authorization", "Bearer $apiKey")
                        .post(requestBody)
                        .build()
                } else {
                    Request.Builder()
                        .url("$GEMINI_API_URL/$model:generateContent")
                        .addHeader("x-goog-api-key", apiKey)
                        .post(requestBody)
                        .build()
                }

                val response = client.newCall(request).execute()
                PLog.d("OpenAIApiClient", "Evaluate response: ${response.code}")
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    return@withContext Result.failure(Exception("API failed: ${response.code}\n${request.url}\n$errorBody"))
                }

                val responseBodyString = response.body?.string() ?: ""
                val text = extractTextFromResponse(responseBodyString)
                Result.success(parseEvaluation(text))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun extractTextFromResponse(responseBodyString: String): String {
        val jsonResponse = JSONObject(responseBodyString)

        jsonResponse.optJSONArray("candidates")?.let { candidates ->
            if (candidates.length() > 0) {
                val parts = candidates
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                val textBuilder = StringBuilder()
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    if (part.has("text")) {
                        textBuilder.append(part.getString("text"))
                    }
                }
                if (textBuilder.isNotBlank()) return textBuilder.toString()
            }
        }

        jsonResponse.optJSONArray("choices")?.let { choices ->
            if (choices.length() > 0) {
                val firstChoice = choices.getJSONObject(0)
                val message = firstChoice.optJSONObject("message")
                val content = message?.optString("content").orEmpty()
                if (content.isNotBlank()) return content
                val text = firstChoice.optString("text")
                if (text.isNotBlank()) return text
            }
        }

        return responseBodyString
    }

    private fun parseEvaluation(text: String): AiPhotoEvaluation {
        val cleaned = text
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val jsonStart = cleaned.indexOf('{')
        val jsonEnd = cleaned.lastIndexOf('}')
        val jsonText = if (jsonStart >= 0 && jsonEnd >= jsonStart) {
            cleaned.substring(jsonStart, jsonEnd + 1)
        } else {
            cleaned
        }
        val json = JSONObject(jsonText)
        return AiPhotoEvaluation(
            overallScore = json.optInt("overallScore").coerceIn(0, 100),
            imageQualityScore = json.optInt("imageQualityScore").coerceIn(0, 100),
            compositionScore = json.optInt("compositionScore").coerceIn(0, 100),
            subjectScore = json.optInt("subjectScore").coerceIn(0, 100),
            emotionScore = json.optInt("emotionScore").coerceIn(0, 100),
            summary = json.optString("summary").trim()
        )
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

data class AiPhotoEvaluation(
    val overallScore: Int,
    val imageQualityScore: Int,
    val compositionScore: Int,
    val subjectScore: Int,
    val emotionScore: Int,
    val summary: String
)
