package com.neozeon.kubedroid.ui.cluster

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.neozeon.kubedroid.data.qr.QrChunkCodec
import com.neozeon.kubedroid.data.qr.QrEnvelopeCodec
import java.util.concurrent.Executors

/**
 * Chunked QR import (spec 03 "UI" + dev-plan stage 5): CameraX preview +
 * ML Kit QR analyzer feeding [QrChunkCodec.Decoder] in strict order; the
 * scanner UI shows `i/n` progress and offers a restart on a failed
 * sequence (chunks must arrive in order).
 */
@Composable
fun QrScannerDialog(
    onDismiss: () -> Unit,
    onKubeconfig: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    var progress by remember { mutableStateOf("Waiting for camera…") }
    var error by remember { mutableStateOf<String?>(null) }
    var restartToken by remember { mutableStateOf(0) }

    // fresh decoder per restart: chunks must arrive in order again
    val decoder = remember(restartToken) { QrChunkCodec.Decoder() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Scan kubeconfig QR chunks", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(progress, style = MaterialTheme.typography.bodyMedium)
        error?.let { message ->
            Spacer(Modifier.height(4.dp))
            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                error = null
                progress = "Waiting…"
                restartToken++
            }) { Text("Restart scan") }
        }
        Spacer(Modifier.height(8.dp))
        if (hasCameraPermission) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val providerFuture = ProcessCameraProvider.getInstance(ctx)
                    providerFuture.addListener({
                        val provider = providerFuture.get()
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(analysisExecutor) { proxy ->
                            analyzeFrame(proxy, scanner, decoder) { outcome ->
                                when (outcome) {
                                    is QrChunkCodec.Decoder.Outcome.Awaiting ->
                                        progress = outcome.progressLabel()
                                    is QrChunkCodec.Decoder.Outcome.Complete -> {
                                        val kubeconfig =
                                            QrEnvelopeCodec.decodeKubeconfig(outcome.jsonPayload)
                                        if (kubeconfig != null) {
                                            onKubeconfig(kubeconfig)
                                        } else {
                                            error = "QR payload is not a kubedroid kubeconfig envelope"
                                        }
                                    }
                                    is QrChunkCodec.Decoder.Outcome.Failed ->
                                        error = outcome.reason
                                }
                            }
                        }
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            analysis,
                        )
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                onRelease = {
                    runCatching {
                        ProcessCameraProvider.getInstance(context).get().unbindAll()
                    }
                    analysisExecutor.shutdown()
                },
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("Camera permission is required to scan QR codes.")
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Grant camera permission")
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onDismiss) { Text("Cancel") }
    }
}

private fun QrChunkCodec.Decoder.Outcome.Awaiting.progressLabel(): String {
    val scanned = nextIndex - 1
    return if (total == null) "Scanned $scanned chunk(s)…" else "Scanned $scanned/$total"
}

private fun analyzeFrame(
    proxy: ImageProxy,
    scanner: BarcodeScanner,
    decoder: QrChunkCodec.Decoder,
    onOutcome: (QrChunkCodec.Decoder.Outcome) -> Unit,
) {
    val mediaImage = proxy.image
    if (mediaImage == null) {
        proxy.close()
        return
    }
    val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
    scanner.process(image)
        .addOnSuccessListener { barcodes: List<Barcode> ->
            for (barcode in barcodes) {
                val value = barcode.rawValue ?: continue
                if (!value.startsWith(QrChunkCodec.PREFIX)) continue
                val outcome = decoder.accept(value)
                if (outcome is QrChunkCodec.Decoder.Outcome.Awaiting) continue
                onOutcome(outcome)
                return@addOnSuccessListener
            }
        }
        .addOnCompleteListener { proxy.close() }
}
