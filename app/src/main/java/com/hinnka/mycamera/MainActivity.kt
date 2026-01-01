package com.hinnka.mycamera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.OrientationEventListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.hinnka.mycamera.ui.camera.CameraScreen
import com.hinnka.mycamera.ui.gallery.GalleryScreen
import com.hinnka.mycamera.ui.gallery.PhotoDetailScreen
import com.hinnka.mycamera.ui.gallery.PhotoEditScreen
import com.hinnka.mycamera.ui.theme.PhotonCameraTheme
import com.hinnka.mycamera.viewmodel.CameraViewModel
import com.hinnka.mycamera.viewmodel.GalleryViewModel

/**
 * 应用屏幕枚举
 */
enum class Screen {
    CAMERA,
    GALLERY,
    PHOTO_DETAIL,
    PHOTO_EDIT
}

class MainActivity : ComponentActivity() {
    
    private val cameraViewModel: CameraViewModel by viewModels()
    private val galleryViewModel: GalleryViewModel by viewModels()
    
    private var hasCameraPermission by mutableStateOf(false)
    private var currentScreen by mutableStateOf(Screen.CAMERA)
    private var photoDetailIndex by mutableStateOf(0)
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 启用全屏模式
        enableEdgeToEdge()
        hideSystemUI()
        orientationListen(cameraViewModel)
        
        // 检查相机权限
        hasCameraPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        
        setContent {
            PhotonCameraTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    if (hasCameraPermission) {
                        when (currentScreen) {
                            Screen.CAMERA -> {
                                CameraScreen(
                                    viewModel = cameraViewModel,
                                    galleryViewModel = galleryViewModel,
                                    onGalleryClick = {
                                        currentScreen = Screen.GALLERY
                                    }
                                )
                            }
                            Screen.GALLERY -> {
                                GalleryScreen(
                                    viewModel = galleryViewModel,
                                    onBack = {
                                        currentScreen = Screen.CAMERA
                                    },
                                    onPhotoClick = { index ->
                                        photoDetailIndex = index
                                        currentScreen = Screen.PHOTO_DETAIL
                                    }
                                )
                            }
                            Screen.PHOTO_DETAIL -> {
                                PhotoDetailScreen(
                                    viewModel = galleryViewModel,
                                    initialIndex = photoDetailIndex,
                                    onBack = {
                                        currentScreen = Screen.GALLERY
                                    },
                                    onEdit = {
                                        currentScreen = Screen.PHOTO_EDIT
                                    }
                                )
                            }
                            Screen.PHOTO_EDIT -> {
                                PhotoEditScreen(
                                    viewModel = galleryViewModel,
                                    onBack = {
                                        currentScreen = Screen.PHOTO_DETAIL
                                    }
                                )
                            }
                        }
                    } else {
                        PermissionScreen(
                            onRequestPermission = {
                                permissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        )
                    }
                }
            }
        }
    }
    
    @Deprecated("Use OnBackPressedDispatcher instead")
    override fun onBackPressed() {
        when (currentScreen) {
            Screen.GALLERY -> currentScreen = Screen.CAMERA
            Screen.PHOTO_DETAIL -> currentScreen = Screen.GALLERY
            Screen.PHOTO_EDIT -> {
                galleryViewModel.exitEditMode()
                currentScreen = Screen.PHOTO_DETAIL
            }
            Screen.CAMERA -> super.onBackPressed()
        }
    }
    
    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = 
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    fun orientationListen(viewModel: CameraViewModel) {
        val orientationListener: OrientationEventListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                // 只在横竖屏切换时才更新状态，避免频繁重组
                viewModel.updateOrientation(orientation)
            }
        }
        orientationListener.enable()
    }
}

@Composable
fun PermissionScreen(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                text = stringResource(R.string.camera_permission_required),
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            
            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF6B35)
                )
            ) {
                Text(text = stringResource(R.string.grant_permission))
            }
        }
    }
}
