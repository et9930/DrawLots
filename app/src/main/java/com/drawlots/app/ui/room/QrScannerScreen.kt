package com.drawlots.app.ui.room

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.drawlots.app.R
import com.drawlots.app.room.QrDecoding
import com.drawlots.app.room.RoomCode
import com.drawlots.app.room.RoomInvite
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/**
 * 扫码加入房间。
 *
 * - 相机权限按需申请；被拒绝时给出「从相册选二维码」与「手输房间码」两条路，不是死胡同。
 * - 相机画面里没扫到也可以从相册挑一张二维码截图。
 * - 扫到非本协议的二维码会提示，并继续扫。
 */
@Composable
fun QrScannerScreen(
    onDecoded: (RoomInvite) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var message by remember { mutableStateOf<String?>(null) }
    var decoding by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCamera = granted }

    LaunchedEffect(Unit) {
        if (!hasCamera) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    fun handleScanned(text: String) {
        val invite = RoomCode.decodeInvite(text)
        if (invite == null) {
            message = "这不是抓阄房间的二维码"
        } else {
            onDecoded(invite)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        decoding = true
        // 相册解码不需要相机权限
        decodeQrFromUri(context, uri) { result ->
            decoding = false
            when (result) {
                is GalleryDecode.Found -> handleScanned(result.text)
                GalleryDecode.NoQr -> message = "这张图里没找到二维码"
                GalleryDecode.Unreadable -> message = "读不到这张图，换一张试试"
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (hasCamera) {
                CameraPreview(onText = { text -> handleScanned(text) })
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(28.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "没有相机权限",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "可以用相册里的二维码截图，或者回到面板手输房间码。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("重新申请相机权限")
                    }
                }
            }

            // 取景提示
            if (hasCamera) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(240.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White.copy(alpha = 0.08f)),
                )
                Text(
                    text = "把房主的二维码放进框里",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(top = 280.dp),
                )
            }

            if (decoding) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(20.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                        Spacer(Modifier.width(10.dp))
                        Text("正在识别…", color = Color.White)
                    }
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                message?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Button(
                    onClick = {
                        galleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_gallery),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("从相册选二维码")
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss) { Text("手输房间码", color = Color.White) }
            }
        }
    }
}

@Composable
private fun CameraPreview(onText: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val reported = remember { AtomicBoolean(false) }
    val currentOnText by rememberUpdatedState(onText)
    val providerRef = remember { AtomicReference<ProcessCameraProvider?>(null) }

    // 用 CameraX 的挂起版 awaitInstance，避开 ListenableFuture（Guava 只在运行时传递依赖里）
    LaunchedEffect(lifecycleOwner) {
        val provider = runCatching { ProcessCameraProvider.awaitInstance(context) }.getOrNull()
            ?: return@LaunchedEffect
        providerRef.set(provider)

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(executor) { image ->
            try {
                if (!reported.get()) {
                    val plane = image.planes.firstOrNull()
                    val buffer = plane?.buffer
                    if (plane != null && buffer != null) {
                        val data = ByteArray(buffer.remaining())
                        buffer.get(data)
                        QrDecoding.decodeLuminance(
                            data = data,
                            width = image.width,
                            height = image.height,
                            rowStride = plane.rowStride,
                        )?.let { text ->
                            if (reported.compareAndSet(false, true)) currentOnText(text)
                        }
                    }
                }
            } catch (e: Exception) {
                // 单帧解码失败很正常，继续下一帧
            } finally {
                image.close()
            }
        }
        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
        }
    }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            runCatching { providerRef.get()?.unbindAll() }
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

/** 相册兜底解码的结果：区分「读不到图」和「图里没有二维码」，提示才说得清。 */
private sealed interface GalleryDecode {
    data class Found(val text: String) : GalleryDecode
    data object NoQr : GalleryDecode
    data object Unreadable : GalleryDecode
}

/** 从相册图片里找二维码（不需要相机权限）。读取与解码放在后台线程，结果回主线程。 */
private fun decodeQrFromUri(context: Context, uri: Uri, onResult: (GalleryDecode) -> Unit) {
    val appContext = context.applicationContext
    Thread {
        val result = runCatching {
            val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@runCatching GalleryDecode.Unreadable
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return@runCatching GalleryDecode.Unreadable
            }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: return@runCatching GalleryDecode.Unreadable
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val decoded = QrDecoding.decodePixels(pixels, bitmap.width, bitmap.height)
            bitmap.recycle()
            if (decoded == null) GalleryDecode.NoQr else GalleryDecode.Found(decoded)
        }.getOrDefault(GalleryDecode.Unreadable)
        android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(result) }
    }.start()
}
