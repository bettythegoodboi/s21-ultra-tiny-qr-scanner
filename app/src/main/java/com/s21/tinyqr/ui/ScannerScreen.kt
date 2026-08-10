package com.s21.tinyqr.ui

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
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
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class CamOption(
    val label: String,
    val cameraInfo: CameraInfo,
    val focalLength: Float,
    val hasFlash: Boolean,
    val minFocusDistance: Float
)

fun ImageProxy.toBitmapSafe(): Bitmap? {
    return try {
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
        val bytes = out.toByteArray()
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) {
        Log.e("TinyQR", "toBitmap failed", e)
        null
    }
}

@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun ScannerScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var lastResult by remember { mutableStateOf<String?>(null) }
    var isScanning by remember { mutableStateOf(true) }
    var zoomRatio by remember { mutableStateOf(4.0f) }
    var torchOn by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Loading...") }
    var availableCams by remember { mutableStateOf<List<CamOption>>(emptyList()) }
    var selectedCamIndex by remember { mutableStateOf(0) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var providerRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var lastFrameBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var manualFocusEnabled by remember { mutableStateOf(true) }
    var focusDistance by remember { mutableStateOf(0f) }
    var maxFocusDistance by remember { mutableStateOf(10f) }

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val stream = context.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(stream)
            stream?.close()
            if (bitmap == null) {
                statusText = "Cannot open image"
                return@rememberLauncherForActivityResult
            }
            statusText = "Decoding Data Matrix..."

            // ZXing Data Matrix hardened path
            val zx = QrDecoder.decode(bitmap)
            if (zx != null) {
                lastResult = zx.first
                isScanning = false
                statusText = "Found!"
                return@rememberLauncherForActivityResult
            }

            // ML Kit Data Matrix
            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_DATA_MATRIX,
                    Barcode.FORMAT_QR_CODE,
                    Barcode.FORMAT_AZTEC,
                    Barcode.FORMAT_PDF417
                )
                .build()
            BarcodeScanning.getClient(options)
                .process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { list ->
                    if (list.isNotEmpty()) {
                        lastResult = list[0].rawValue
                        isScanning = false
                        statusText = "Found (ML Kit)!"
                    } else {
                        statusText = "No code found"
                    }
                }
                .addOnFailureListener { statusText = "Decode error" }
        } catch (e: Exception) {
            statusText = "Image error"
            Log.e("TinyQR", "gallery", e)
        }
    }

    fun saveBitmap(bitmap: Bitmap) {
        try {
            val name = "DM_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TinyQR")
                }
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)
                }
                Toast.makeText(context, "Saved $name", Toast.LENGTH_SHORT).show()
                statusText = "Saved"
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Save failed", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(zoomRatio, camera) {
        try {
            camera?.let {
                val zs = it.cameraInfo.zoomState.value ?: return@let
                it.cameraControl.setZoomRatio(zoomRatio.coerceIn(zs.minZoomRatio, zs.maxZoomRatio))
            }
        } catch (_: Exception) {}
    }

    LaunchedEffect(torchOn, camera) {
        try {
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                camera?.cameraControl?.enableTorch(torchOn)
            } else torchOn = false
        } catch (_: Exception) {}
    }

    fun bindCamera() {
        val provider = providerRef ?: return
        val previewView = previewViewRef ?: return
        if (availableCams.isEmpty()) return
        val option = availableCams.getOrNull(selectedCamIndex) ?: return

        try {
            provider.unbindAll()

            val previewBuilder = Preview.Builder()
            if (manualFocusEnabled && option.minFocusDistance > 0f) {
                val ext = Camera2Interop.Extender(previewBuilder)
                ext.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                ext.setCaptureRequestOption(
                    CaptureRequest.LENS_FOCUS_DISTANCE,
                    focusDistance.coerceIn(0f, option.minFocusDistance)
                )
            }
            val preview = previewBuilder.build().also { it.surfaceProvider = previewView.surfaceProvider }

            val selector = CameraSelector.Builder()
                .addCameraFilter { list -> list.filter { it == option.cameraInfo } }
                .build()

            // Higher resolution analysis for tiny Data Matrix
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(Size(1920, 1080))
                .build()

            val mlOptions = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_DATA_MATRIX,
                    Barcode.FORMAT_QR_CODE,
                    Barcode.FORMAT_AZTEC,
                    Barcode.FORMAT_PDF417
                )
                .build()
            val ml = BarcodeScanning.getClient(mlOptions)

            analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { proxy ->
                if (!isScanning) {
                    proxy.close()
                    return@setAnalyzer
                }
                try {
                    val bmp = proxy.toBitmapSafe()
                    if (bmp == null) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    lastFrameBitmap = bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, false)

                    // Primary: hardened Data Matrix decoder
                    val result = QrDecoder.decode(bmp)
                    if (result != null) {
                        lastResult = result.first
                        isScanning = false
                        statusText = "Found!"
                        proxy.close()
                        return@setAnalyzer
                    }

                    // Fallback ML Kit
                    ml.process(InputImage.fromBitmap(bmp, proxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener { codes ->
                            if (codes.isNotEmpty()) {
                                lastResult = codes[0].rawValue
                                isScanning = false
                                statusText = "Found (ML)!"
                            }
                        }
                        .addOnCompleteListener { proxy.close() }
                } catch (e: Exception) {
                    Log.e("TinyQR", "analyze", e)
                    proxy.close()
                }
            }

            val cam = provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            camera = cam
            maxFocusDistance = if (option.minFocusDistance > 0f) option.minFocusDistance else 10f

            if (!manualFocusEnabled) {
                try {
                    val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
                    val action = FocusMeteringAction.Builder(
                        factory.createPoint(0.5f, 0.5f), FocusMeteringAction.FLAG_AF
                    ).setAutoCancelDuration(2, TimeUnit.SECONDS).build()
                    cam.cameraControl.startFocusAndMetering(action)
                } catch (_: Exception) {}
            }

            statusText = "${option.label} | Data Matrix mode"
            torchOn = false
        } catch (e: Exception) {
            Log.e("TinyQR", "bind", e)
            statusText = "Camera error"
        }
    }

    LaunchedEffect(selectedCamIndex, availableCams, manualFocusEnabled, focusDistance) {
        if (availableCams.isNotEmpty()) bindCamera()
    }

    Box(Modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val pv = PreviewView(ctx)
                previewViewRef = pv
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
                                val minF = c2.getCameraCharacteristic(
                                    CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
                                ) ?: 0f
                                val label = when {
                                    focal < 2.5f -> "Ultrawide"
                                    focal < 7f -> "Main"
                                    focal < 20f -> "3x Tele"
                                    else -> "10x Tele"
                                }
                                cams.add(CamOption(label, info, focal, info.hasFlashUnit(), minF))
                            } catch (_: Exception) {}
                        }
                        availableCams = cams.sortedBy { it.focalLength }
                        val uw = availableCams.indexOfFirst { it.label == "Ultrawide" }
                        selectedCamIndex = if (uw >= 0) uw else 0
                        statusText = "${availableCams.size} cameras | Data Matrix"
                    } catch (e: Exception) {
                        statusText = "Init failed"
                    }
                }, ContextCompat.getMainExecutor(ctx))
                pv
            },
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.75f))
                .padding(10.dp)
        ) {
            Text(
                text = if (isScanning) statusText else "CODE FOUND",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Data Matrix industrial mode",
                color = Color.Cyan,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.9f))
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (lastResult != null) {
                Text("Result:", color = Color.White, fontSize = 12.sp)
                Text(
                    text = lastResult ?: "",
                    color = Color.Green,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(4.dp)
                )
                Button(
                    onClick = { lastResult = null; isScanning = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Scan Again") }
                Spacer(Modifier = Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                availableCams.forEachIndexed { i, cam ->
                    Button(
                        onClick = { selectedCamIndex = i },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedCamIndex == i) Color(0xFF2196F3) else Color.DarkGray
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("${cam.label}", fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Manual Focus", color = Color.White, fontSize = 13.sp)
                Switch(checked = manualFocusEnabled, onCheckedChange = { manualFocusEnabled = it })
            }
            if (manualFocusEnabled) {
                Slider(
                    value = focusDistance,
                    onValueChange = { focusDistance = it },
                    valueRange = 0f..maxFocusDistance,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Text("Zoom ${String.format("%.1fx", zoomRatio)}", color = Color.White, fontSize = 12.sp)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = { zoomRatio = (zoomRatio - 0.5f).coerceAtLeast(1f) }) { Text("- Zoom") }
                Button(onClick = { zoomRatio = (zoomRatio + 0.5f).coerceAtMost(15f) }) { Text("+ Zoom") }
                val flash = availableCams.getOrNull(selectedCamIndex)?.hasFlash == true
                Button(
                    onClick = { if (flash) torchOn = !torchOn },
                    enabled = flash,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (torchOn) Color(0xFFFFC107) else Color.DarkGray
                    )
                ) { Text(if (torchOn) "Torch ON" else "Torch") }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(
                    onClick = {
                        lastFrameBitmap?.let { saveBitmap(it) }
                            ?: Toast.makeText(context, "No frame", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0))
                ) { Text("Capture") }

                Button(
                    onClick = { galleryLauncher.launch("image/*") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BCD4))
                ) { Text("Gallery") }

                Button(
                    onClick = { isScanning = true; lastResult = null; statusText = "Scanning..." },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) { Text("Scan") }
            }
        }
    }
}
