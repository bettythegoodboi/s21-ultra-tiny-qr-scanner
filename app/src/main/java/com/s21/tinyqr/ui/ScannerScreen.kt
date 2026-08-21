package com.s21.tinyqr.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

fun ImageProxy.toYPlane(): Pair<ByteArray, Pair<Int, Int>>? {
    return try {
        val yPlane = planes[0]
        val yBuf = yPlane.buffer
        val pixelStride = yPlane.pixelStride
        val rowStride = yPlane.rowStride
        val w = width
        val h = height
        val data = ByteArray(w * h)
        if (pixelStride == 1 && rowStride == w) {
            yBuf.get(data, 0, minOf(yBuf.remaining(), data.size))
        } else {
            var pos = 0
            for (row in 0 until h) {
                val rowStart = row * rowStride
                for (col in 0 until w) {
                    data[pos++] = yBuf.get(rowStart + col * pixelStride)
                }
            }
        }
        data to (w to h)
    } catch (e: Exception) {
        Log.e("TinyQR", "Y plane extract failed", e)
        null
    }
}

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
    } catch (_: Exception) {
        null
    }
}

fun triggerHapticFeedback(context: Context) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator?.vibrate(
                VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            vibrator?.vibrate(70)
        }
    } catch (_: Exception) {}
}

@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun ScannerScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var lastResult by remember { mutableStateOf<DecodeResult?>(null) }
    var isScanning by remember { mutableStateOf(true) }
    var zoomRatio by remember { mutableStateOf(3.0f) }
    var torchOn by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Ready to scan") }
    var availableCams by remember { mutableStateOf<List<CamOption>>(emptyList()) }
    var selectedCamIndex by remember { mutableStateOf(0) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var providerRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var lastFrameBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var manualFocusEnabled by remember { mutableStateOf(true) }
    var focusDistance by remember { mutableStateOf(10f) }
    var maxFocusDistance by remember { mutableStateOf(20f) }

    // Tap-to-focus animation state
    var tapPoint by remember { mutableStateOf<Offset?>(null) }
    val tapAnimProgress = remember { Animatable(1f) }

    // Gallery / Image Selection & Manual Crop Dialog
    var pickedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showCropDialog by remember { mutableStateOf(false) }
    var isGalleryProcessing by remember { mutableStateOf(false) }

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    // Gallery Picker launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            isGalleryProcessing = true
            statusText = "Loading picture..."
            val bmp = withContext(Dispatchers.IO) {
                try {
                    val stream = context.contentResolver.openInputStream(uri)
                    val decoded = BitmapFactory.decodeStream(stream)
                    stream?.close()
                    decoded
                } catch (_: Exception) {
                    null
                }
            }

            if (bmp == null) {
                isGalleryProcessing = false
                Toast.makeText(context, "Could not load image", Toast.LENGTH_SHORT).show()
                return@launch
            }

            pickedBitmap = bmp
            statusText = "Scanning image for tiny codes..."

            // Try auto-detection first
            val autoResult = withContext(Dispatchers.Default) {
                // 1. Unified Multi-Pass Decoder
                QrDecoder.decode(bmp)
            }

            if (autoResult != null) {
                isGalleryProcessing = false
                lastResult = autoResult
                isScanning = false
                triggerHapticFeedback(context)
                statusText = "Code Found: ${autoResult.formatName}"
            } else {
                // Fallback: ML Kit full scan
                val mlOptions = BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(
                        Barcode.FORMAT_DATA_MATRIX,
                        Barcode.FORMAT_QR_CODE,
                        Barcode.FORMAT_AZTEC,
                        Barcode.FORMAT_PDF417
                    )
                    .build()
                val ml = BarcodeScanning.getClient(mlOptions)
                ml.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { codes ->
                        isGalleryProcessing = false
                        if (codes.isNotEmpty()) {
                            val code = codes[0]
                            val fmt = when (code.format) {
                                Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"
                                Barcode.FORMAT_QR_CODE -> "QR Code"
                                Barcode.FORMAT_AZTEC -> "Aztec"
                                else -> "2D Barcode"
                            }
                            lastResult = DecodeResult(code.rawValue ?: "", fmt, "ML Kit")
                            isScanning = false
                            triggerHapticFeedback(context)
                            statusText = "Code Found!"
                        } else {
                            // If auto-detection fails, prompt user with interactive crop tool
                            statusText = "No code detected automatically. Adjust the crop box."
                            showCropDialog = true
                        }
                    }
                    .addOnFailureListener {
                        isGalleryProcessing = false
                        statusText = "Auto scan failed. Opening manual selector..."
                        showCropDialog = true
                    }
            }
        }
    }

    fun saveBitmap(bitmap: Bitmap) {
        try {
            val name = "TinyQR_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
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
                Toast.makeText(context, "Saved frame to Gallery", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            Toast.makeText(context, "Save failed", Toast.LENGTH_SHORT).show()
        }
    }

    // Dynamic zoom adjustment
    LaunchedEffect(zoomRatio, camera) {
        try {
            camera?.let {
                val zs = it.cameraInfo.zoomState.value ?: return@let
                it.cameraControl.setZoomRatio(zoomRatio.coerceIn(zs.minZoomRatio, zs.maxZoomRatio))
            }
        } catch (_: Exception) {}
    }

    // Torch control
    LaunchedEffect(torchOn, camera) {
        try {
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                camera?.cameraControl?.enableTorch(torchOn)
            } else {
                torchOn = false
            }
        } catch (_: Exception) {}
    }

    // Dynamic manual focus adjustment (smooth without rebuilding camera session)
    LaunchedEffect(focusDistance, manualFocusEnabled, camera) {
        val cam = camera ?: return@LaunchedEffect
        try {
            val camera2Control = Camera2CameraControl.from(cam.cameraControl)
            if (manualFocusEnabled) {
                val options = CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_OFF
                    )
                    .setCaptureRequestOption(
                        CaptureRequest.LENS_FOCUS_DISTANCE,
                        focusDistance
                    )
                    .build()
                camera2Control.setCaptureRequestOptions(options)
            } else {
                val options = CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    )
                    .build()
                camera2Control.setCaptureRequestOptions(options)
            }
        } catch (e: Exception) {
            Log.e("TinyQR", "Failed to update focus distance", e)
        }
    }

    // Bind camera session
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
                ext.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_OFF
                )
                ext.setCaptureRequestOption(
                    CaptureRequest.LENS_FOCUS_DISTANCE,
                    focusDistance.coerceIn(0f, option.minFocusDistance)
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
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
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

            val analyzerExecutor = Executors.newSingleThreadExecutor()

            analysis.setAnalyzer(analyzerExecutor) { proxy ->
                if (!isScanning) {
                    proxy.close()
                    return@setAnalyzer
                }
                try {
                    // Stage 1: Ultra-fast raw YUV decode with multi-pass (ZXing + Custom DPM)
                    val yPair = proxy.toYPlane()
                    if (yPair != null) {
                        val (yData, size) = yPair
                        val res = QrDecoder.decodeYuv(yData, size.first, size.second, cropPercent = 0.50f)
                        if (res != null) {
                            lastResult = res
                            isScanning = false
                            triggerHapticFeedback(context)
                            proxy.toBitmapSafe()?.let { lastFrameBitmap = it }
                            proxy.close()
                            return@setAnalyzer
                        }
                    }

                    // Stage 2: ML Kit Barcode analysis
                    val media = proxy.image
                    if (media != null) {
                        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                        ml.process(image)
                            .addOnSuccessListener { codes ->
                                if (codes.isNotEmpty() && isScanning) {
                                    val code = codes[0]
                                    val fmt = when (code.format) {
                                        Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"
                                        Barcode.FORMAT_QR_CODE -> "QR Code"
                                        Barcode.FORMAT_AZTEC -> "Aztec"
                                        else -> "2D Barcode"
                                    }
                                    lastResult = DecodeResult(code.rawValue ?: "", fmt, "ML Kit")
                                    isScanning = false
                                    triggerHapticFeedback(context)
                                }
                            }
                            .addOnCompleteListener {
                                proxy.toBitmapSafe()?.let { lastFrameBitmap = it }
                                proxy.close()
                            }
                    } else {
                        proxy.close()
                    }
                } catch (e: Exception) {
                    Log.e("TinyQR", "Analyzer exception", e)
                    proxy.close()
                }
            }

            val cam = provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            camera = cam
            maxFocusDistance = if (option.minFocusDistance > 0f) option.minFocusDistance else 20f
            if (focusDistance > maxFocusDistance) focusDistance = maxFocusDistance

            statusText = "${option.label} | Targeting tiny codes"
            torchOn = false
        } catch (e: Exception) {
            Log.e("TinyQR", "Bind failed", e)
            statusText = "Camera init error"
        }
    }

    // Trigger binding on camera selection change
    LaunchedEffect(selectedCamIndex, availableCams) {
        if (availableCams.isNotEmpty()) bindCamera()
    }

    // Tap-to-focus handler
    fun handleTapToFocus(offset: Offset, viewWidth: Float, viewHeight: Float) {
        val cam = camera ?: return
        if (viewWidth <= 0f || viewHeight <= 0f) return

        tapPoint = offset
        coroutineScope.launch {
            tapAnimProgress.snapTo(0f)
            tapAnimProgress.animateTo(1f, animationSpec = tween(600))
            tapPoint = null
        }

        try {
            val factory = SurfaceOrientedMeteringPointFactory(viewWidth, viewHeight)
            val point = factory.createPoint(offset.x, offset.y)
            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build()
            cam.cameraControl.startFocusAndMetering(action)
        } catch (e: Exception) {
            Log.e("TinyQR", "Tap to focus failed", e)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Camera Preview + Tap Gestures
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
                                    focal < 2.5f -> "Ultrawide (Macro)"
                                    focal < 7f -> "Main"
                                    focal < 20f -> "3x Tele"
                                    else -> "10x Tele"
                                }
                                cams.add(CamOption(label, info, focal, info.hasFlashUnit(), minF))
                            } catch (_: Exception) {}
                        }
                        availableCams = cams.sortedBy { it.focalLength }
                        val uw = availableCams.indexOfFirst { it.label.contains("Ultrawide") }
                        selectedCamIndex = if (uw >= 0) uw else 0
                    } catch (_: Exception) {
                        statusText = "Camera detection failed"
                    }
                }, ContextCompat.getMainExecutor(ctx))
                pv
            },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { tapOffset ->
                        handleTapToFocus(tapOffset, size.width.toFloat(), size.height.toFloat())
                    }
                }
        )

        // Targeting Reticle & Viewfinder Frame
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasW = size.width
            val canvasH = size.height

            // Center target box (50% size)
            val boxSize = minOf(canvasW, canvasH) * 0.52f
            val boxLeft = (canvasW - boxSize) / 2f
            val boxTop = (canvasH - boxSize) / 2f

            // Frame border
            drawRect(
                color = if (isScanning) Color(0xFF00E676) else Color.Yellow,
                topLeft = Offset(boxLeft, boxTop),
                size = Size(boxSize, boxSize),
                style = Stroke(width = 2.dp.toPx())
            )

            // High-precision corner brackets
            val cornerLen = 24.dp.toPx()
            val strokeW = 4.dp.toPx()
            val cornerColor = Color.White

            // Top-left
            drawLine(cornerColor, Offset(boxLeft, boxTop), Offset(boxLeft + cornerLen, boxTop), strokeW)
            drawLine(cornerColor, Offset(boxLeft, boxTop), Offset(boxLeft, boxTop + cornerLen), strokeW)
            // Top-right
            drawLine(cornerColor, Offset(boxLeft + boxSize, boxTop), Offset(boxLeft + boxSize - cornerLen, boxTop), strokeW)
            drawLine(cornerColor, Offset(boxLeft + boxSize, boxTop), Offset(boxLeft + boxSize, boxTop + cornerLen), strokeW)
            // Bottom-left
            drawLine(cornerColor, Offset(boxLeft, boxTop + boxSize), Offset(boxLeft + cornerLen, boxTop + boxSize), strokeW)
            drawLine(cornerColor, Offset(boxLeft, boxTop + boxSize), Offset(boxLeft, boxTop + boxSize - cornerLen), strokeW)
            // Bottom-right
            drawLine(cornerColor, Offset(boxLeft + boxSize, boxTop + boxSize), Offset(boxLeft + boxSize - cornerLen, boxTop + boxSize), strokeW)
            drawLine(cornerColor, Offset(boxLeft + boxSize, boxTop + boxSize), Offset(boxLeft + boxSize, boxTop + boxSize - cornerLen), strokeW)

            // Center Crosshair
            val midX = canvasW / 2f
            val midY = canvasH / 2f
            val crosshairLen = 12.dp.toPx()
            drawLine(Color.Cyan.copy(alpha = 0.8f), Offset(midX - crosshairLen, midY), Offset(midX + crosshairLen, midY), 2.dp.toPx())
            drawLine(Color.Cyan.copy(alpha = 0.8f), Offset(midX, midY - crosshairLen), Offset(midX, midY + crosshairLen), 2.dp.toPx())

            // Tap to focus animated ring
            tapPoint?.let { tp ->
                val radius = (40.dp.toPx()) * (1f - tapAnimProgress.value * 0.3f)
                val alpha = 1f - tapAnimProgress.value
                drawCircle(
                    color = Color.Yellow.copy(alpha = alpha),
                    radius = radius,
                    center = tp,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }

        // Top Status Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .background(Color.Black.copy(alpha = 0.75f))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = if (isScanning) statusText else "SCAN COMPLETE",
                color = if (isScanning) Color.White else Color(0xFF00E676),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Aim at tiny Data Matrix / QR code (< 5mm)",
                color = Color.Cyan,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Interactive Image Manual Cropper (Dialog)
        if (showCropDialog && pickedBitmap != null) {
            ImageCropDialog(
                bitmap = pickedBitmap!!,
                onDismiss = { showCropDialog = false },
                onDecodeSelection = { cropped ->
                    showCropDialog = false
                    coroutineScope.launch {
                        statusText = "Decoding selected area..."
                        val res = withContext(Dispatchers.Default) {
                            QrDecoder.decode(cropped)
                        }
                        if (res != null) {
                            lastResult = res
                            isScanning = false
                            triggerHapticFeedback(context)
                            statusText = "Code Found in Selection!"
                        } else {
                            Toast.makeText(context, "No code found in selected box. Try zooming further.", Toast.LENGTH_LONG).show()
                            showCropDialog = true
                        }
                    }
                }
            )
        }

        // Bottom Controls Panel
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .background(Color.Black.copy(alpha = 0.90f))
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Result Bottom Sheet View
            AnimatedVisibility(
                visible = lastResult != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                lastResult?.let { result ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    color = Color(0xFF00E676),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = result.formatName,
                                        color = Color.Black,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                                if (result.details.isNotEmpty()) {
                                    Text(result.details, color = Color.Gray, fontSize = 11.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = result.text,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            // Action buttons: Copy, Open Browser, Share, Scan Again
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // Copy
                                Button(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("TinyQR", result.text))
                                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Copy", fontSize = 12.sp)
                                }

                                // Open Browser if URL
                                if (result.text.startsWith("http://") || result.text.startsWith("https://") || result.text.startsWith("www.")) {
                                    Button(
                                        onClick = {
                                            val url = if (!result.text.startsWith("http://") && !result.text.startsWith("https://")) {
                                                "https://${result.text}"
                                            } else result.text
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                context.startActivity(intent)
                                            } catch (_: Exception) {
                                                Toast.makeText(context, "Cannot open URL", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0091EA)),
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.OpenInBrowser, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Open", fontSize = 12.sp)
                                    }
                                }

                                // Share
                                Button(
                                    onClick = {
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, result.text)
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, "Share Scanned Code"))
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.Share, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Share", fontSize = 12.sp)
                                }

                                // Scan Again
                                Button(
                                    onClick = {
                                        lastResult = null
                                        isScanning = true
                                        statusText = "Aim at code..."
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                                    modifier = Modifier.weight(1.2f),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Scan", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            // Camera selector chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                availableCams.forEachIndexed { i, cam ->
                    Button(
                        onClick = { selectedCamIndex = i },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedCamIndex == i) Color(0xFF2196F3) else Color(0xFF2B2B2B)
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(cam.label, fontSize = 12.sp, color = Color.White)
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Focus & Macro controls
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Close Macro Focus", color = Color.White, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (manualFocusEnabled) "Manual (${String.format(Locale.US, "%.1f", focusDistance)} D)" else "Auto", color = Color.LightGray, fontSize = 11.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Switch(
                        checked = manualFocusEnabled,
                        onCheckedChange = { manualFocusEnabled = it }
                    )
                }
            }

            if (manualFocusEnabled) {
                Slider(
                    value = focusDistance,
                    onValueChange = { focusDistance = it },
                    valueRange = 0f..maxFocusDistance,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Zoom & Torch Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Zoom ${String.format(Locale.US, "%.1fx", zoomRatio)}", color = Color.White, fontSize = 12.sp)

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = { zoomRatio = (zoomRatio - 0.5f).coerceAtLeast(1f) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) { Text("-", fontSize = 14.sp) }

                    Button(
                        onClick = { zoomRatio = (zoomRatio + 0.5f).coerceAtMost(15f) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) { Text("+", fontSize = 14.sp) }

                    val flash = availableCams.getOrNull(selectedCamIndex)?.hasFlash == true
                    Button(
                        onClick = { if (flash) torchOn = !torchOn },
                        enabled = flash,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (torchOn) Color(0xFFFFC107) else Color(0xFF333333)
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = if (torchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = "Torch",
                            tint = if (torchOn) Color.Black else Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action Toolbar: Capture frame, Gallery Pick, Manual Select
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Capture current frame
                Button(
                    onClick = {
                        lastFrameBitmap?.let { saveBitmap(it) }
                            ?: Toast.makeText(context, "No frame available", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7B1FA2)),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    Icon(imageVector = Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Capture", fontSize = 13.sp)
                }

                // Choose from Gallery
                Button(
                    onClick = { galleryLauncher.launch("image/*") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00838F)),
                    modifier = Modifier.weight(1.2f),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    Icon(imageVector = Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Select Image", fontSize = 13.sp)
                }

                // Manual Crop / Zoom (if image is already picked)
                if (pickedBitmap != null) {
                    Button(
                        onClick = { showCropDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100)),
                        modifier = Modifier.weight(1.1f),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Crop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Crop/Zoom", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
