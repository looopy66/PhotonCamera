package com.hinnka.mycamera.lut.creator

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hinnka.mycamera.data.CustomImportManager
import com.hinnka.mycamera.lut.LutManager
import com.hinnka.mycamera.utils.PLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.io.File

class LutCreatorViewModel(application: Application) : AndroidViewModel(application) {

    private val importManager = CustomImportManager(application)
    private val lutManager = LutManager(application)
    private val userPrefsRepo =
        com.hinnka.mycamera.data.ContentRepository.getInstance(application).userPreferencesRepository

    private val _uiState = MutableStateFlow<LutCreatorUiState>(LutCreatorUiState.Idle)
    val uiState: StateFlow<LutCreatorUiState> = _uiState

    val aiAnalysisEnabled = MutableStateFlow(false)

    fun analyzeImages(uris: List<Uri>, customPrompt: String = "", isAiEnabled: Boolean = aiAnalysisEnabled.value) {
        if (uris.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = LutCreatorUiState.Analyzing

            try {
                // Read bitmaps from URIs
                val bitmaps = uris.mapNotNull { loadBitmapFromUri(it) }
                if (bitmaps.isEmpty()) {
                    _uiState.value = LutCreatorUiState.Error("Failed to decode image(s)")
                    return@launch
                }

                // Always run Local Analysis first to get the Target Control Points via K-Means.
                // This gives us a stable mapping of the image's actual target colors.
                val localRecipe = LocalImageAnalyzer.analyzeImages(bitmaps)

                val recipe = if (isAiEnabled) {
                    val userPrefs = userPrefsRepo.userPreferences.firstOrNull()
                    val isBuiltIn = userPrefs?.useBuiltInAiService ?: false
                    val apiKey = if (isBuiltIn) OpenAIApiClient.BUILT_IN_API_KEY else userPrefs?.openAIApiKey ?: ""
                    val baseUrl = if (isBuiltIn) OpenAIApiClient.BUILT_IN_API_URL else userPrefs?.openAIBaseUrl ?: ""
                    val model = if (isBuiltIn) {
                        OpenAIApiClient.BUILT_IN_MODEL
                    } else {
                        userPrefs?.openAIModel?.takeIf { it.isNotEmpty() } ?: OpenAIApiClient.BUILT_IN_MODEL
                    }

                    if (apiKey.isEmpty()) {
                        _uiState.value = LutCreatorUiState.Error("OpenAI API Key is not set in settings")
                        return@launch
                    }

                    val client = OpenAIApiClient(apiKey, baseUrl)

                    PLog.d(
                        "LutCreatorViewModel",
                        "Calling AI Client for Source Color Inference... Custom prompt: $customPrompt, Model: $model"
                    )
                    // Pass the images to AI to generate a pure Style Recipe (without constraints)
                    val result = client.analyzeImagesForLut(
                        bitmaps,
                        model,
                        customPrompt = customPrompt
                    )
                    val aiStyleRecipe = result.getOrThrow()

                    // Now, use Inverse RBF to map our precise Local Targets through the AI's Style Recipe
                    // This finds the exact Source colors that produce our Local Targets under the AI's style model.
                    val inferredSources = LutGenerator.inverseInterpolate(
                        styleRecipe = aiStyleRecipe,
                        localTargets = localRecipe.controlPoints
                    )

                    // Sanitize the Final output: limit extreme shifts between the inferred Source and actual Local Target
                    // If the AI's style caused an extreme shift (e.g. green to black),
                    // we clamp the vector magnitude to preserve the *direction* but limit the *strength*.
                    val maxDist = 0.4f // Limits shifts to a robust but realistic max delta
                    val sanitizedPoints = inferredSources.map { cp ->
                        val dr = cp.sourceR - cp.targetR
                        val dg = cp.sourceG - cp.targetG
                        val db = cp.sourceB - cp.targetB
                        val dist = kotlin.math.sqrt((dr * dr + dg * dg + db * db).toDouble()).toFloat()

                        if (dist > maxDist) {
                            val scale = maxDist / dist
                            cp.copy(
                                sourceR = (cp.targetR + dr * scale).coerceIn(0f, 1f),
                                sourceG = (cp.targetG + dg * scale).coerceIn(0f, 1f),
                                sourceB = (cp.targetB + db * scale).coerceIn(0f, 1f)
                            )
                        } else {
                            cp
                        }
                    }
                    LutRecipe(sanitizedPoints)
                } else {
                    localRecipe
                }

                PLog.d("LutCreatorViewModel", "Recipe: $recipe")

                _uiState.value = LutCreatorUiState.AnalysisComplete(recipe)
            } catch (e: Exception) {
                _uiState.value = LutCreatorUiState.Error("Analysis failed: ${e.message}")
            }
        }
    }

    private fun loadBitmapFromUri(uri: Uri): android.graphics.Bitmap? {
        return try {
            val source = android.graphics.ImageDecoder.createSource(getApplication<Application>().contentResolver, uri)
            android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE) // Needs mutable maybe later, software allows fast reading
            }
        } catch (e: Exception) {
            null
        }
    }

    fun generateAndImportLut(name: String, recipe: LutRecipe) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = LutCreatorUiState.Generating

            try {
                // 1. Generate FloatArray 33x33x33x3
                val lutData = LutGenerator.generateLut(recipe)
                // 2. Export to .cube format
                val cubeContent = LutGenerator.exportToCubeString(lutData, 33, name)

                // 3. Write to temp file and use CustomImportManager
                val tempFile = File(getApplication<Application>().cacheDir, "temp_gen.cube")
                tempFile.writeText(cubeContent)

                val uri = Uri.fromFile(tempFile)
                val lutId = importManager.importLut(uri, name, "AI LUT")

                if (lutId != null) {
                    // Initialize to ensure memory cache and disk are synced
                    lutManager.initialize()
                    _uiState.value = LutCreatorUiState.Success(lutId)
                } else {
                    _uiState.value = LutCreatorUiState.Error("Import failed")
                }
            } catch (e: Exception) {
                _uiState.value = LutCreatorUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun resetToIdle() {
        _uiState.value = LutCreatorUiState.Idle
    }
}

sealed class LutCreatorUiState {
    object Idle : LutCreatorUiState()
    object Analyzing : LutCreatorUiState()
    data class AnalysisComplete(val recipe: LutRecipe) : LutCreatorUiState()
    object Generating : LutCreatorUiState()
    data class Success(val lutId: String) : LutCreatorUiState()
    data class Error(val message: String) : LutCreatorUiState()
}
