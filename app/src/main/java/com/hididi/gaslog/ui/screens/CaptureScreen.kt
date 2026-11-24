package com.hididi.gaslog.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.hididi.gaslog.service.PhotoStore
import com.hididi.gaslog.ui.vm.GasLogViewModel

@Composable
fun CaptureScreen(
    vm: GasLogViewModel,
    onCaptured: () -> Unit,
    onCancel: () -> Unit,
    onPickGallery: () -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!hasPermission) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("需要相机权限才能拍摄燃气表", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "照片只保存在手机本地，不会上传。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("授予权限")
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.TextButton(onClick = onPickGallery) { Text("改为从相册选择") }
            androidx.compose.material3.TextButton(onClick = onCancel) { Text("返回") }
        }
        return
    }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    var errorText by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                bindCamera(ctx, previewView, imageCapture, lifecycleOwner) { msg -> errorText = msg }
                previewView
            },
        )

        // 取景辅助框
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 120.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .border(2.dp, Color.White.copy(alpha = 0.75f), RoundedCornerShape(18.dp)),
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {
                torchOn = !torchOn
                // 手电筒通过 CameraControl 切换，绑定成功后可用
                cameraControl?.enableTorch(torchOn)
            }) {
                Icon(
                    if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                    contentDescription = "闪光灯",
                    tint = Color.White,
                )
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "把燃气表读数窗放进取景框，保持水平、避免反光",
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodySmall,
            )
            errorText?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) {
                    IconButton(onClick = onPickGallery) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = "相册", tint = Color.White)
                    }
                }
                Spacer(Modifier.width(24.dp))
                Box(
                    Modifier
                        .size(74.dp)
                        .background(Color.White.copy(alpha = 0.25f), CircleShape)
                        .padding(6.dp),
                ) {
                    Button(
                        onClick = {
                            if (busy) return@Button
                            busy = true
                val file = PhotoStore.newTemporaryPhotoFile(context)
                            val options = ImageCapture.OutputFileOptions.Builder(file).build()
                            imageCapture.takePicture(
                                options,
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                        busy = false
                                        vm.startCapture(file, System.currentTimeMillis())
                                        onCaptured()
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        busy = false
                                        errorText = "拍照失败：${exception.message}"
                                    }
                                },
                            )
                        },
                        modifier = Modifier.fillMaxSize(),
                        shape = CircleShape,
                        enabled = !busy,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        if (busy) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Box(
                                Modifier
                                    .size(52.dp)
                                    .background(Color.White, CircleShape),
                            )
                        }
                    }
                }
                Spacer(Modifier.width(24.dp))
                Box(Modifier.width(64.dp))
            }
        }
    }
}

private var cameraControl: androidx.camera.core.CameraControl? = null

private fun bindCamera(
    context: Context,
    previewView: PreviewView,
    imageCapture: ImageCapture,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    onError: (String) -> Unit,
) {
    val providerFuture = ProcessCameraProvider.getInstance(context)
    providerFuture.addListener({
        try {
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            provider.unbindAll()
            val camera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture,
            )
            cameraControl = camera.cameraControl
        } catch (e: Exception) {
            onError("相机启动失败：${e.message}")
        }
    }, ContextCompat.getMainExecutor(context))
}
