package com.s21.tinyqr.ui

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
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
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumMap
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
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 95, out)
        val imageBytes = out.toByteArray()
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    } catch (e: Exception) {
        Log.e("TinyQR", "toBitmap failed", e)
        null
    }
}

fun processForQr(src: Bitmap, strong: Boolean = true): Bitmap {
    val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    val paint = Paint()
    val contrast = if (strong) 1.9f else 1.4f
    val translate = (-0.5f * contrast + 0.5f) * 255f
    val cm = ColorMatrix(
        floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        )
    )
    val gray = ColorMatrix()
    gray.setSaturation(0f)
    cm.postConcat(gray)
    paint.colorFilter = ColorMatrixColorFilter(cm)
    canvas.drawBitmap(src, 0f, 0f, paint)
    return result
}

/** Try ZXing decoder - often better on tiny / low-contrast codes */
fun decodeWithZxing(bitmap: Bitmap): String? {
    return try {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val binary = BinaryBitmap(HybridBinarizer(source))
        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
        hints[DecodeHintType.TRY_HARDER] = true
        hints[DecodeHintType.POSSIBLE_FORMATS] = listOf(
            com.google.zxing.BarcodeFormat.QR_CODE,
            com.google.zxing.BarcodeFormat.DATA_MATRIX,
            com.google.zxing.BarcodeFormat.AZTEC,
            com.google.zxing.BarcodeFormat.PDF_417
        )
        val reader = MultiFormatReader()
        reader.setHints(hints)
        val result = reader.decodeWithState(binary)
        result.text
    } catch (e: Exception) {
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
    var zoomRatio by remember { mutableStateOf(3.0f) }
    var torchOn by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Loading cameras...") }
    var availableCams by remember { mutableStateOf<List<CamOption>>(emptyList()) }
    var selectedCamIndex by remember { mutableStateOf(0) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var providerRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var lastFrameBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var manualFocusEnabled by remember { mutableStateOf(false) }
    var focusDistance by remember { mutableStateOf(0f) }
    var maxFocusDistance by remember { mutableStateOf(10f) }
    var enhanceEnabled by remember { mutableStateOf(true) }

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    // Gallery picker
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            if (bitmap != null) {
                statusText = "Decoding image..."
                // Try both processed and original
                val processed = processForQr(bitmap, strong = true)
                var result = decodeWithZxing(processed)
                if (result == null) result = decodeWithZxing(bitmap)

                // Also try ML Kit on the image
                if (result == null) {
                    val image = InputImage.fromBitmap(processed, 0)
                    val options = BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(
                            Barcode.FORMAT_QR_CODE,
                            Barcode.FORMAT_DATA_MATRIX,
                            Barcode.FORMAT_AZTEC,
                            Barcode.FORMAT_PDF417
                        )
                        .build()
                    val scanner = BarcodeScanning.getClient(options)
                    scanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            if (barcodes.isNotEmpty()) {
                                lastResult = barcodes[0].rawValue
                                isScanning = false
                                statusText = "QR Found (from gallery)!"
                            } else {
                                statusText = "No QR found in image"
                            }
                        }
                        .addOnFailureListener {
                            statusText = "Decode failed"
                        }
                } else {
                    lastResult = result
                    isScanning = false
                    statusText = "QR Found (ZXing)!"
                }
            }
        } catch (e: Exception) {
            statusText = "Failed to open image"
            Log.e("TinyQR", "Gallery error", e)
        }
    }

    fun saveBitmapToGallery(bitmap: Bitmap) {
        try {
            val filename = "TinyQR_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TinyQR")
                }
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                Toast.makeText(context, "Saved: $filename", Toast.LENGTH_SHORT).show()
                statusText = "Image saved"
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Save failed", Toast.LENGTH_SHORT).show()
            Log.e("TinyQR", "Save error", e)
        }
    }

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

            if (manualFocusEnabled && option.minFocusDistance > 0f) {
                val extender = Camera2Interop.Extender(previewBuilder)
                extender.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_OFF
                )
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
                .addCameraFilter { list -> list.filter { it == option.cameraInfo } }
                .build()

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_QR_CODE,
                    Barcode.FORMAT_DATA_MATRIX,
                    Barcode.FORMAT_AZTEC,
                    Barcode.FORMAT_PDF417
                )
                .build()
            val mlScanner = BarcodeScanning.getClient(options)

            analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                if (!isScanning) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                try {
                    val bitmap = imageProxy.toBitmapSafe()
                    if (bitmap == null) {
                        imageProxy.close()
                        return@setAnalyzer
                    }

                    // Keep last frame for Capture
                    lastFrameBitmap = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)

                    val processed = if (enhanceEnabled) processForQr(bitmap, strong = true) else bitmap

                    // 1) Try ZXing first (often better for tiny codes)
                    val zxingResult = decodeWithZxing(processed)
                    if (zxingResult != null) {
                        lastResult = zxingResult
                        isScanning = false
                        statusText = "QR Found (ZXing)!"
                        imageProxy.close()
                        if (processed !== bitmap) processed.recycle()
                        return@setAnalyzer
                    }

                    // 2) Fallback to ML Kit
                    val image = InputImage.fromBitmap(processed, imageProxy.imageInfo.rotationDegrees)
                    mlScanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            for (barcode in barcodes) {
                                barcode.rawValue?.let { value ->
                                    lastResult = value
                                    isScanning = false
                                    statusText = "QR Found (ML Kit)!"
                                }
                            }
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                            if (processed !== bitmap) processed.recycle()
                        }
                } catch (e: Exception) {
                    Log.e("TinyQR", "Analysis error", e)
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

            statusText = "${option.label} | Enhance: ${if (enhanceEnabled) "ON" else "OFF"}"
            torchOn = false

        } catch (e: Exception) {
            Log.e("TinyQR", "Bind failed", e)
            statusText = "Error: ${e.message?.take(40)}"
        }
    }

    LaunchedEffect(selectedCamIndex, availableCams, manualFocusEnabled, focusDistance, enhanceEnabled) {
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
                text = "Hold 2~5cm | Dual decoder (ZXing + ML Kit)",
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
                .background(Color.Black.copy(alpha = 0.88f))
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            if (lastResult != null) {
                Text("Result:", color = Color.White, fontSize = 12.sp)
                Text(
                    text = lastResult ?: "",
                    color = Color.Green,
                    fontSize = 15.sp,
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

            // Camera selection
            Text("Camera (${availableCams.size}):", color = Color.White, fontSize = 12.sp)
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

            Spacer(modifier = Modifier.height(6.dp))

            // Switches
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enhance", color = Color.White, fontSize = 13.sp)
                    Switch(checked = enhanceEnabled, onCheckedChange = { enhanceEnabled = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Manual Focus", color = Color.White, fontSize = 13.sp)
                    Switch(checked = manualFocusEnabled, onCheckedChange = { manualFocusEnabled = it })
                }
            }

            if (manualFocusEnabled) {
                Text("Focus (closer ← → far)", color = Color.LightGray, fontSize = 11.sp)
                Slider(
                    value = focusDistance,
                    onValueChange = { focusDistance = it },
                    valueRange = 0f..maxFocusDistance,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Zoom
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
                    onClick = { zoomRatio = (zoomRatio + 0.5f).coerceAtMost(15f) },
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

            // New: Capture + Gallery + Scan
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = {
                        lastFrameBitmap?.let { saveBitmapToGallery(it) }
                            ?: Toast.makeText(context, "No frame yet", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0))
                ) { Text("Capture") }

                Button(
                    onClick = { galleryLauncher.launch("image/*") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BCD4))
                ) { Text("Gallery") }

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
                text = "Best: Ultrawide + Enhance ON + high zoom + Manual Focus",
                color = Color.Gray,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
