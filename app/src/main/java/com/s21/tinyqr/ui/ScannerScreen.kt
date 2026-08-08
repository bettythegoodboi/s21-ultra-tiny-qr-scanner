package com.s21.tinyqr.ui

import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun ScannerScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var lastResult by remember { mutableStateOf<String?>(null) }
    var isScanning by remember { mutableStateOf(true) }
    var zoomRatio by remember { mutableStateOf(2.5f) } // Start zoomed for tiny QR
    var torchOn by remember { mutableStateOf(false) }
    var useUltrawide by remember { mutableStateOf(true) } // Prefer ultrawide for close focus
    var camera by remember { mutableStateOf<Camera?>(null) }
    var statusText by remember { mutableStateOf("Starting camera...") }

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    // Apply zoom
    LaunchedEffect(zoomRatio, camera) {
        try {
            camera?.cameraControl?.setZoomRatio(zoomRatio)
        } catch (e: Exception) {
            Log.e("TinyQR", "Zoom failed", e)
        }
    }

    // Apply torch
    LaunchedEffect(torchOn, camera) {
        try {
            camera?.cameraControl?.enableTorch(torchOn)
        } catch (e: Exception) {
            Log.e("TinyQR", "Torch failed", e)
        }
    }

    // Rebind camera when switching Main <-> Ultrawide
    fun bindCamera(provider: ProcessCameraProvider, previewView: PreviewView) {
        try {
            provider.unbindAll()

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            // Prefer Ultrawide for better close-range focus on S21 Ultra
            val selector = if (useUltrawide) {
                CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .addCameraFilter { cameraInfos ->
                        // Try to find the widest FOV camera (ultrawide)
                        val filtered = cameraInfos.filter { info ->
                            try {
                                val c2 = Camera2CameraInfo.from(info)
                                val focalLengths = c2.getCameraCharacteristic(
                                    CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                                )
                                // Ultrawide usually has the smallest focal length
                                focalLengths != null && focalLengths.isNotEmpty() && focalLengths[0] < 3.0f
                            } catch (e: Exception) {
                                false
                            }
                        }
                        if (filtered.isNotEmpty()) filtered else cameraInfos
                    }
                    .build()
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val scanner = BarcodeScanning.getClient()

            analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                if (!isScanning) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                val mediaImage = imageProxy.image
                if (mediaImage != null) {
                    val image = InputImage.fromMediaImage(
                        mediaImage,
                        imageProxy.imageInfo.rotationDegrees
                    )
                    scanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            for (barcode in barcodes) {
                                if (barcode.format == Barcode.FORMAT_QR_CODE) {
                                    barcode.rawValue?.let { value ->
                                        lastResult = value
                                        isScanning = false
                                        statusText = "QR Found!"
                                        Log.d("TinyQR", "Found: $value")
                                    }
                                }
                            }
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                        }
                } else {
                    imageProxy.close()
                }
            }

            val cam = provider.bindToLifecycle(
                lifecycleOwner,
                selector,
                preview,
                analysis
            )
            camera = cam

            // Force continuous autofocus + try center focus for close objects
            try {
                val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
                val point = factory.createPoint(0.5f, 0.5f)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build()
                cam.cameraControl.startFocusAndMetering(action)
            } catch (e: Exception) {
                Log.e("TinyQR", "Focus failed", e)
            }

            statusText = if (useUltrawide) "Ultrawide + Zoom ${String.format("%.1f", zoomRatio)}x" else "Main + Zoom ${String.format("%.1f", zoomRatio)}x"

        } catch (e: Exception) {
            Log.e("TinyQR", "Bind failed", e)
            statusText = "Camera error: ${e.message}"
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val executor = ContextCompat.getMainExecutor(ctx)

                cameraProviderFuture.addListener({
                    val provider = cameraProviderFuture.get()
                    bindCamera(provider, previewView)
                }, executor)

                previewView
            },
            modifier = Modifier.fillMaxSize(),
            update = { previewView ->
                // Rebind when switching camera
                cameraProviderFuture.addListener({
                    val provider = cameraProviderFuture.get()
                    bindCamera(provider, previewView)
                }, ContextCompat.getMainExecutor(context))
            }
        )

        // Top status
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(12.dp)
        ) {
            Text(
                text = if (isScanning) statusText else "QR Code Found!",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Hold close to the QR (2-5 cm)",
                color = Color.LightGray,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Bottom controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.75f))
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            if (lastResult != null) {
                Text("Result:", color = Color.White, fontSize = 13.sp)
                Text(
                    text = lastResult ?: "",
                    color = Color.Green,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
                Button(
                    onClick = {
                        lastResult = null
                        isScanning = true
                        statusText = if (useUltrawide) "Ultrawide scanning..." else "Main scanning..."
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Text("Scan Again")
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Camera switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = { useUltrawide = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (useUltrawide) Color(0xFF2196F3) else Color.DarkGray
                    )
                ) {
                    Text("Ultrawide")
                }
                Button(
                    onClick = { useUltrawide = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (!useUltrawide) Color(0xFF2196F3) else Color.DarkGray
                    )
                ) {
                    Text("Main Cam")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Zoom + Torch
            Text(
                text = "Zoom: ${String.format("%.1fx", zoomRatio)}",
                color = Color.White,
                fontSize = 13.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = { zoomRatio = (zoomRatio - 0.5f).coerceAtLeast(1f) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                ) {
                    Text("- Zoom")
                }
                Button(
                    onClick = { zoomRatio = (zoomRatio + 0.5f).coerceAtMost(10f) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                ) {
                    Text("+ Zoom")
                }
                Button(
                    onClick = { torchOn = !torchOn },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (torchOn) Color(0xFFFFC107) else Color.DarkGray
                    )
                ) {
                    Text(if (torchOn) "Torch ON" else "Torch")
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Tip: Use Ultrawide + Zoom 2.5x~4x + hold very close",
                color = Color.Gray,
                fontSize = 11.sp
            )
        }
    }
}
