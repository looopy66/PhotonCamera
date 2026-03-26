package com.hinnka.mycamera.screencapture

import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.Surface as ComposeSurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.hinnka.mycamera.R
import com.hinnka.mycamera.data.ContentRepository
import com.hinnka.mycamera.data.UserPreferencesRepository
import com.hinnka.mycamera.ui.camera.CameraGLSurfaceView
import com.hinnka.mycamera.ui.theme.PhotonCameraTheme
import com.hinnka.mycamera.utils.PLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ScreenCapturePipActivity : ComponentActivity() {

    private lateinit var controller: ScreenCaptureTestController
    private lateinit var userPreferencesRepository: UserPreferencesRepository
    private var isInPiPMode by mutableStateOf(false)
    private var stopReceiverRegistered = false
    private var hasReleasedCapture = false
    private val stopCheckHandler = Handler(Looper.getMainLooper())
    private val stopCheckRunnable = Runnable {
        val actuallyInPip = isInPictureInPictureMode || ScreenCapturePipState.isInPipMode.value
        PLog.d(TAG, "stop check: finishing=$isFinishing destroyed=$isDestroyed inPip=$actuallyInPip")
        if (!actuallyInPip) {
            handleCaptureClosed()
        }
    }

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == PhantomPipPreviewCoordinator.ACTION_STOP_PHANTOM_PIP_PREVIEW) {
                lifecycleScope.launch {
                    userPreferencesRepository.savePhantomMode(false)
                }
                controller.stopCapture()
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PLog.d(TAG, "onCreate")
        ScreenCapturePipState.setInPipMode(false)
        controller = ScreenCaptureTestController(this)
        userPreferencesRepository = ContentRepository.getInstance(this).userPreferencesRepository
        registerStopReceiver()

        val grant = ScreenCaptureProjectionStore.consume()
        if (grant == null) {
            finish()
            return
        }

        setContent {
            PhotonCameraTheme {
                ComposeSurface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    ScreenCapturePipContent(
                        controller = controller,
                        isInPiPMode = isInPiPMode,
                        onRequestPip = { enterPipIfPossible() }
                    )
                }
            }
        }

        controller.startCapture(grant.resultCode, grant.data)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        PLog.d(TAG, "onUserLeaveHint")
        enterPipIfPossible()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PLog.d(TAG, "onPictureInPictureModeChanged: $isInPictureInPictureMode")
        ScreenCapturePipState.setInPipMode(isInPictureInPictureMode)
        isInPiPMode = isInPictureInPictureMode
    }

    override fun onStop() {
        super.onStop()
        PLog.d(TAG, "onStop: frameworkInPip=$isInPictureInPictureMode stateInPip=${ScreenCapturePipState.isInPipMode.value}")
        stopCheckHandler.removeCallbacks(stopCheckRunnable)
        stopCheckHandler.postDelayed(stopCheckRunnable, 400L)
    }

    private fun registerStopReceiver() {
        val filter = IntentFilter(PhantomPipPreviewCoordinator.ACTION_STOP_PHANTOM_PIP_PREVIEW)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(stopReceiver, filter)
        }
        stopReceiverRegistered = true
    }

    override fun onDestroy() {
        PLog.d(TAG, "onDestroy: changingConfigurations=$isChangingConfigurations frameworkInPip=$isInPictureInPictureMode")
        stopCheckHandler.removeCallbacksAndMessages(null)
        if (stopReceiverRegistered) {
            unregisterReceiver(stopReceiver)
            stopReceiverRegistered = false
        }
        if (!isChangingConfigurations) {
            handleCaptureClosed()
        }
        ScreenCapturePipState.setInPipMode(false)
        super.onDestroy()
    }

    private fun handleCaptureClosed() {
        if (hasReleasedCapture) return
        PLog.d(TAG, "handleCaptureClosed")
        hasReleasedCapture = true
        lifecycleScope.launch {
            userPreferencesRepository.savePhantomMode(false)
        }
        controller.release()
        ScreenCapturePipState.setInPipMode(false)
    }

    private fun enterPipIfPossible() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        try {
            PLog.d(TAG, "enterPipIfPossible")
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(3, 4))
                .build()
            enterPictureInPictureMode(params)
        } catch (t: Throwable) {
            PLog.e(TAG, "Failed to enter picture-in-picture", t)
        }
    }

    companion object {
        private const val TAG = "ScreenCapturePip"
    }
}

@Composable
private fun ScreenCapturePipContent(
    controller: ScreenCaptureTestController,
    isInPiPMode: Boolean,
    onRequestPip: () -> Unit
) {
    val uiState by controller.uiState.collectAsState()
    val renderConfig by ScreenCaptureRenderConfigStore.config.collectAsState()
    var viewportWidth by remember { mutableIntStateOf(0) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val windowMetrics = remember {
        context.getSystemService(WindowManager::class.java).currentWindowMetrics
    }
    val captureWidth = remember(windowMetrics) { windowMetrics.bounds.width().coerceAtLeast(1) }
    val captureHeight = remember(windowMetrics) { windowMetrics.bounds.height().coerceAtLeast(1) }

    LaunchedEffect(uiState.isCapturing) {
        if (!uiState.isCapturing) return@LaunchedEffect
        PLog.d("ScreenCapturePip", "capture running, waiting for preview surface before entering PiP")
    }

    LaunchedEffect(uiState.isCapturing, uiState.hasPreviewSurface) {
        if (uiState.isCapturing && uiState.hasPreviewSurface) {
            PLog.d("ScreenCapturePip", "preview surface ready, entering PiP")
            delay(180L)
            onRequestPip()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            controller.detachPreviewSurface(null)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { size ->
                viewportWidth = size.width
                viewportHeight = size.height
            },
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { context ->
                CameraGLSurfaceView(context).apply {
                    onSurfaceReady = { surface ->
                        controller.attachPreviewSurface(
                            surface = surface,
                            width = captureWidth,
                            height = captureHeight,
                            densityDpi = context.resources.displayMetrics.densityDpi
                        )
                    }
                    onSurfaceDestroyed = {
                        controller.detachPreviewSurface(null)
                    }
                }
            },
            update = { glSurfaceView ->
                glSurfaceView.setPreviewSize(captureWidth, captureHeight)
                glSurfaceView.setSensorOrientation(0)
                glSurfaceView.setLensFacing(1)
                glSurfaceView.setDeviceRotation(0)
                glSurfaceView.setCalibrationOffset(0)
                glSurfaceView.setLut(renderConfig.lutConfig)
                glSurfaceView.setLutEnabled(renderConfig.lutConfig != null)
                glSurfaceView.setColorRecipeEnabled(!renderConfig.colorRecipeParams.isDefault())
                // MediaProjection feeds the SurfaceTexture with a vertically flipped source space.
                // Map the user-selected crop from display coordinates into projection coordinates here.
                glSurfaceView.setSourceCrop(renderConfig.crop.flipVertically())
                glSurfaceView.setParams(
                    params = renderConfig.colorRecipeParams,
                    aperture = 0f
                )
                glSurfaceView.getRenderSurface()?.let { surface ->
                    controller.attachPreviewSurface(
                        surface = surface,
                        width = captureWidth,
                        height = captureHeight,
                        densityDpi = glSurfaceView.resources.displayMetrics.densityDpi
                    )
                }
                if (viewportWidth > 0 && viewportHeight > 0) {
                    glSurfaceView.requestRenderFrame()
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (!isInPiPMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.screen_capture_status_running), color = Color.White)
            }
        }
    }
}
