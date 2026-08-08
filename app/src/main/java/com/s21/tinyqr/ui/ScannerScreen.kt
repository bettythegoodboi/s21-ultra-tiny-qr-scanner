package com.s21.tinyqr.ui

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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

data class CamOption(
    val label: String,
    val cameraInfo: CameraInfo,
    val focalLength: Float,
    val hasFlash: Boolean,
    val minFocusDistance: Float // diopters, 0 = infinity
)

@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun ScannerScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var lastResult by remember { mutableStateOf<String?>(null) }
    var isScanning by remember { mutableStateOf(true) }
    var zoomRatio by remember { mutableStateOf(2.0f) }
    var torchOn by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Loading cameras...") }
    var availableCams by remember { mutableStateOf<List<CamOption>>(emptyList()) }
    var selectedCamIndex by remember { mutableStateOf(0) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var providerRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    // Manual focus
    var manualFocusEnabled by remember { mutableStateOf(false) }
    var focusDistance by remember { mutableStateOf(0f) } // 0 = infinity, higher = closer
    var maxFocusDistance by remember { mutableStateOf(10f) }

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    // Zoom
    LaunchedEffect(zoomRatio, camera) {
        try {
            camera?.let {
                val zoomState = it.cameraInfo.zoomState.value
                if (zoomState != null) {
                    val clamped = zoomRatio.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                    it.cameraControl.setZoomRatio(clamped)
                }
            }
        } catch (_: Exception) {}
    }

    // Torch
    LaunchedEffect(torchOn, camera) {
        try {
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                camera?.cameraControl?.enableTorch(torchOn)
            } else {
                torchOn = false
            }
        } catch (_: Exception) {}
    }

    fun bindSelectedCamera() {
        val provider = providerRef ?: return
        val previewView = previewViewRef ?: return
        if (availableCams.isEmpty()) return

        val option = availableCams.getOrNull(selectedCamIndex) ?: return

        try {
            provider.unbindAll()

            val previewBuilder = Preview.Builder()

            // Apply Camera2Interop for manual focus if enabled
            if (manualFocusEnabled && option.minFocusDistance > 0f) {
                val extender = Camera2Interop.Extender(previewBuilder)
                extender.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_OFF
                )
                // focusDistance is in diopters (1/meters). Higher = closer.
                val diopters = focusDistance.coerceIn(0f, option.minFocusDistance)
                extender.setCaptureRequestOption(
                    CaptureRequest.LENS_FOCUS_DISTANCE,
                    diopters
                )
            }

            val preview = previewBuilder.build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            val selector = CameraSelector.Builder()
                .addCameraFilter { list ->
                    list.filter { it == option.cameraInfo }
                }
                .build()

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
                                    }
                                }
                            }
                        }
                        .addOnCompleteListener { imageProxy.close() }
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

            maxFocusDistance = if (option.minFocusDistance > 0f) option.minFocusDistance else 10f

            // If not in manual mode, do continuous AF on center
            if (!manualFocusEnabled) {
                try {
                    val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
                    val point = factory.createPoint(0.5f, 0.5f)
                    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                        .setAutoCancelDuration(2, TimeUnit.SECONDS)
                        .build()
                    cam.cameraControl.startFocusAndMetering(action)
                } catch (_: Exception) {}
            }

            statusText = "${option.label} | ${if (manualFocusEnabled) "Manual Focus" else "Auto Focus"}"
            torchOn = false

        } catch (e: Exception) {
            Log.e("TinyQR", "Bind failed", e)
            statusText = "Error: ${e.message?.take(40)}"
        }
    }

    // Rebind when camera or manual focus settings change
    LaunchedEffect(selectedCamIndex, availableCams, manualFocusEnabled, focusDistance) {
        if (availableCams.isNotEmpty()) {
            bindSelectedCamera()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                previewViewRef = previewView
                val executor = ContextCompat.getMainExecutor(ctx)

                cameraProviderFuture.addListener({
                    try {
                        val provider = cameraProviderFuture.get()
                        providerRef = provider

                        val cams = mutableListOf<CamOption>()
                        for (info in provider.availableCameraInfos) {
                            if (info.lensFacing != CameraSelector.LENS_FACING_BACK) continue
                            try {
                                val c2 = Camera2CameraInfo.from(info)
                                val focals = c2.getCameraCharacteristic(
                                    CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                                )
                                val focal = focals?.firstOrNull() ?: 0f
                                val hasFlash = info.hasFlashUnit()

                                val minFocus = c2.getCameraCharacteristic(
                                    CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
                                ) ?: 0f

                                val label = when {
                                    focal < 2.5f -> "Ultrawide"
                                    focal < 7f -> "Main"
                                    focal < 20f -> "3x Tele"
                                    else -> "10x Tele"
                                }

                                cams.add(CamOption(label, info, focal, hasFlash, minFocus))
                            } catch (e: Exception) {
                                Log.e("TinyQR", "Cam info error", e)
                            }
                        }

                        availableCams = cams.sortedBy { it.focalLength }

                        val uwIndex = availableCams.indexOfFirst { it.label == "Ultrawide" }
                        selectedCamIndex = if (uwIndex >= 0) uwIndex else 0

                        statusText = "Found ${availableCams.size} cameras"

                    } catch (e: Exception) {
                        statusText = "Camera init failed"
                        Log.e("TinyQR", "Init failed", e)
                    }
                }, executor)

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Top status
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(10.dp)
        ) {
            Text(
                text = if (isScanning) statusText else "QR Code Found!",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Hold 2~5cm from tiny QR | Cams found: ${availableCams.size}",
                color = Color.LightGray,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Bottom panel
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.82f))
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            if (lastResult != null) {
                Text("Result:", color = Color.White, fontSize = 12.sp)
                Text(
                    text = lastResult ?: "",
                    color = Color.Green,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                Button(
                    onClick = {
                        lastResult = null
                        isScanning = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Scan Again")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Camera buttons
            Text("Select Camera (${availableCams.size} available):", color = Color.White, fontSize = 12.sp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                availableCams.forEachIndexed { index, cam ->
                    Button(
                        onClick = { selectedCamIndex = index },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedCamIndex == index) Color(0xFF2196F3) else Color.DarkGray
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("${cam.label}\n${String.format("%.1f", cam.focalLength)}mm", fontSize = 11.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Manual Focus section
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Manual Focus", color = Color.White, fontSize = 13.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = manualFocusEnabled,
                    onCheckedChange = { manualFocusEnabled = it }
                )
            }

            if (manualFocusEnabled) {
                Text(
                    text = "Focus Distance (closer ← → infinity)",
                    color = Color.LightGray,
                    fontSize = 11.sp
                )
                Slider(
                    value = focusDistance,
                    onValueChange = { focusDistance = it },
                    valueRange = 0f..maxFocusDistance,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Zoom + Torch
            Text("Zoom: ${String.format("%.1fx", zoomRatio)}", color = Color.White, fontSize = 12.sp)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = { zoomRatio = (zoomRatio - 0.5f).coerceAtLeast(1f) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                ) { Text("- Zoom") }

                Button(
                    onClick = { zoomRatio = (zoomRatio + 0.5f).coerceAtMost(12f) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                ) { Text("+ Zoom") }

                val hasFlash = availableCams.getOrNull(selectedCamIndex)?.hasFlash == true
                Button(
                    onClick = { if (hasFlash) torchOn = !torchOn },
                    enabled = hasFlash,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (torchOn) Color(0xFFFFC107) else Color.DarkGray,
                        disabledContainerColor = Color.Gray
                    )
                ) {
                    Text(if (!hasFlash) "No Flash" else if (torchOn) "Torch ON" else "Torch")
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = {
                        try {
                            camera?.let { cam ->
                                val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
                                val point = factory.createPoint(0.5f, 0.5f)
                                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                    .build()
                                cam.cameraControl.startFocusAndMetering(action)
                                statusText = "Focusing..."
                            }
                        } catch (_: Exception) {}
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF607D8B))
                ) { Text("Auto Focus") }

                Button(
                    onClick = {
                        isScanning = true
                        lastResult = null
                        statusText = "Scanning..."
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) { Text("Scan Now") }
            }

            Text(
                text = "Tip: Ultrawide + Manual Focus + Zoom 2~4x for tiny QR",
                color = Color.Gray,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
