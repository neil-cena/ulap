package com.ulap.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Button
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.ulap.R
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.Executors

/** One additional bot entry carried inside a scanned QR payload. */
data class ScannedBotEntry(val token: String, val label: String)

data class ScannedCredentials(
    val token: String,
    val chatId: String,
    val additionalBots: List<ScannedBotEntry> = emptyList(),
    val telegramLoggingEnabled: Boolean = false,
    val telegramLoggingChatId: String? = null,
    val googlePhotosWebClientId: String? = null,
    val googlePhotosClientSecret: String? = null,
)

@Composable
fun QrScanScreen(onScanned: (ScannedCredentials) -> Unit) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    if (!hasCameraPermission) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Text(
                    stringResource(R.string.permission_camera_reason),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text(stringResource(R.string.grant_permission))
                }
            }
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            onBarcodeDetected = { raw ->
                parseUlapQr(raw)?.let { onScanned(it) }
            },
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.qr_scan_instruction),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CameraPreview(
    modifier: Modifier = Modifier,
    onBarcodeDetected: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    var alreadyScanned by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                val analyzer = UlapBarcodeAnalyzer { raw ->
                    if (!alreadyScanned) {
                        alreadyScanned = true
                        onBarcodeDetected(raw)
                    }
                }
                imageAnalysis.setAnalyzer(executor, analyzer)
                previewView.tag = analyzer
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis,
                )
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
        onRelease = { view ->
            (view.tag as? UlapBarcodeAnalyzer)?.close()
        },
        modifier = modifier,
    )
}

private class UlapBarcodeAnalyzer(
    private val onDetected: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    private val scanner = BarcodeScanning.getClient()

    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: run { imageProxy.close(); return }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
                    ?.rawValue
                    ?.let { onDetected(it) }
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    fun close() = scanner.close()
}

private fun parseUlapQr(raw: String): ScannedCredentials? {
    return try {
        val json = JSONObject(raw)
        val token = json.optString("t").takeIf { it.isNotBlank() } ?: return null
        val chatId = json.optString("c").takeIf { it.isNotBlank() } ?: return null
        val additionalBots = mutableListOf<ScannedBotEntry>()
        val bArray = json.optJSONArray("b")
        if (bArray != null) {
            for (i in 0 until bArray.length()) {
                val obj = bArray.optJSONObject(i) ?: continue
                val k = obj.optString("k").trim()
                val l = obj.optString("l")
                if (k.isNotBlank()) additionalBots.add(ScannedBotEntry(k, l))
            }
        }
        val telegramLoggingEnabled = json.optBoolean("le", false)
        val telegramLoggingChatId = json.optString("lc").takeIf { it.isNotBlank() }
        val googlePhotosWebClientId = json.optString("gp").takeIf { it.isNotBlank() }
        val googlePhotosClientSecret = json.optString("gs").takeIf { it.isNotBlank() }
        ScannedCredentials(
            token,
            chatId,
            additionalBots,
            telegramLoggingEnabled,
            telegramLoggingChatId,
            googlePhotosWebClientId,
            googlePhotosClientSecret,
        )
    } catch (_: JSONException) {
        null
    }
}
