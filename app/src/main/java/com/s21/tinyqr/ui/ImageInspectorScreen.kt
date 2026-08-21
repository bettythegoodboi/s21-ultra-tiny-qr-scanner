package com.s21.tinyqr.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

@Composable
fun ImageInspectorScreen(
    bitmap: Bitmap,
    onBackToCamera: () -> Unit,
    onNewImagePick: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var scale by remember { mutableStateOf(1.0f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    // Screen box rect (relative to Canvas size)
    var boxCenterRatio by remember { mutableStateOf(Offset(0.5f, 0.5f)) }
    var boxSizeRatio by remember { mutableStateOf(0.40f) } // relative to min(canvasW, canvasH)

    var isProcessing by remember { mutableStateOf(false) }
    var scanStatus by remember { mutableStateOf("Position box over code and tap 'Scan Selected Box'") }
    var decodeResult by remember { mutableStateOf<DecodeResult?>(null) }

    // Auto-scan whole image on first open
    LaunchedEffect(bitmap) {
        isProcessing = true
        scanStatus = "Auto-scanning whole image..."
        val res = withContext(Dispatchers.Default) {
            // Check original and rotations
            QrDecoder.decode(bitmap)
        }
        if (res != null) {
            decodeResult = res
            scanStatus = "Code Found: ${res.formatName}!"
            triggerHapticFeedback(context)
            isProcessing = false
        } else {
            // ML Kit fallback on whole bitmap
            val mlOptions = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_DATA_MATRIX,
                    Barcode.FORMAT_QR_CODE,
                    Barcode.FORMAT_AZTEC,
                    Barcode.FORMAT_PDF417
                ).build()
            BarcodeScanning.getClient(mlOptions)
                .process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { codes ->
                    isProcessing = false
                    if (codes.isNotEmpty()) {
                        val c = codes[0]
                        val fmt = when (c.format) {
                            Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"
                            Barcode.FORMAT_QR_CODE -> "QR Code"
                            Barcode.FORMAT_AZTEC -> "Aztec"
                            else -> "2D Barcode"
                        }
                        decodeResult = DecodeResult(c.rawValue ?: "", fmt, "ML Kit")
                        scanStatus = "Code Found!"
                        triggerHapticFeedback(context)
                    } else {
                        scanStatus = "No code found automatically. Move the box over the code & tap Scan."
                    }
                }
                .addOnFailureListener {
                    isProcessing = false
                    scanStatus = "Position box over code and tap 'Scan Selected Box'"
                }
        }
    }

    fun cropAndScanBox(canvasW: Float, canvasH: Float) {
        if (canvasW <= 0f || canvasH <= 0f) return
        isProcessing = true
        scanStatus = "Cropping & decoding..."
        decodeResult = null

        coroutineScope.launch {
            val res = withContext(Dispatchers.Default) {
                try {
                    val imgW = bitmap.width.toFloat()
                    val imgH = bitmap.height.toFloat()

                    // Fitted aspect ratio base scale
                    val baseScale = min(canvasW / imgW, canvasH / imgH)
                    val totalScale = baseScale * scale

                    val drawnW = imgW * totalScale
                    val drawnH = imgH * totalScale
                    val drawnLeft = (canvasW - drawnW) / 2f + panOffset.x
                    val drawnTop = (canvasH - drawnH) / 2f + panOffset.y

                    val boxSizePx = min(canvasW, canvasH) * boxSizeRatio
                    val boxLeftPx = (canvasW * boxCenterRatio.x) - (boxSizePx / 2f)
                    val boxTopPx = (canvasH * boxCenterRatio.y) - (boxSizePx / 2f)

                    // Project screen box to bitmap pixel coordinates
                    val bmpCropX = ((boxLeftPx - drawnLeft) / totalScale).toInt().coerceIn(0, bitmap.width - 1)
                    val bmpCropY = ((boxTopPx - drawnTop) / totalScale).toInt().coerceIn(0, bitmap.height - 1)
                    val bmpCropW = (boxSizePx / totalScale).toInt().coerceIn(16, bitmap.width - bmpCropX)
                    val bmpCropH = (boxSizePx / totalScale).toInt().coerceIn(16, bitmap.height - bmpCropY)

                    if (bmpCropW < 16 || bmpCropH < 16) return@withContext null

                    val cropped = Bitmap.createBitmap(bitmap, bmpCropX, bmpCropY, bmpCropW, bmpCropH)

                    // Decode cropped area with all multi-pass + DPM filters + 4 rotations
                    var found: DecodeResult? = QrDecoder.decode(cropped)

                    if (found == null) {
                        // Try 90, 180, 270 rotations for tilted codes
                        for (rot in intArrayOf(90, 180, 270)) {
                            val matrix = Matrix().apply { postRotate(rot.toFloat()) }
                            val rotBmp = Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, matrix, true)
                            found = QrDecoder.decode(rotBmp)
                            rotBmp.recycle()
                            if (found != null) break
                        }
                    }

                    if (cropped !== bitmap) cropped.recycle()
                    found
                } catch (e: Exception) {
                    null
                }
            }

            isProcessing = false
            if (res != null) {
                decodeResult = res
                scanStatus = "Code Found in Box: ${res.formatName}!"
                triggerHapticFeedback(context)
            } else {
                scanStatus = "No code found in selected box. Adjust size/position and try again."
                Toast.makeText(context, "No code detected in box. Try zooming in or adjusting the frame.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101010))
            .systemBarsPadding()
    ) {
        // Top Navigation & Action Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E1E1E))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onBackToCamera,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Camera", tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Camera", fontSize = 12.sp)
            }

            Text(
                text = "Picture Scanner",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )

            Button(
                onClick = onNewImagePick,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00838F)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Icon(imageVector = Icons.Default.PhotoLibrary, contentDescription = "Pick Image", tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Change", fontSize = 12.sp)
            }
        }

        // Status Header
        Surface(
            color = if (decodeResult != null) Color(0xFF1B5E20) else Color(0xFF263238),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = scanStatus,
                color = Color.White,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        // Main Image Canvas & Interactive Bounding Box
        var canvasSize by remember { mutableStateOf(Size.Zero) }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
                .pointerInput(Unit) {
                    // Two-finger pinch to zoom & pan
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.5f, 15f)
                        panOffset += pan
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        // Drag the green box on screen
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            if (canvasSize.width > 0 && canvasSize.height > 0) {
                                val newX = (boxCenterRatio.x + dragAmount.x / canvasSize.width).coerceIn(0.1f, 0.9f)
                                val newY = (boxCenterRatio.y + dragAmount.y / canvasSize.height).coerceIn(0.1f, 0.9f)
                                boxCenterRatio = Offset(newX, newY)
                            }
                        }
                    }
            ) {
                canvasSize = size
                val canvasW = size.width
                val canvasH = size.height
                val imgW = imageBitmap.width.toFloat()
                val imgH = imageBitmap.height.toFloat()

                val baseScale = min(canvasW / imgW, canvasH / imgH)
                val totalScale = baseScale * scale

                val drawnW = imgW * totalScale
                val drawnH = imgH * totalScale
                val drawnLeft = (canvasW - drawnW) / 2f + panOffset.x
                val drawnTop = (canvasH - drawnH) / 2f + panOffset.y

                // 1. Draw Image with zoom & pan
                drawImage(
                    image = imageBitmap,
                    dstOffset = androidx.compose.ui.unit.IntOffset(drawnLeft.toInt(), drawnTop.toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(drawnW.toInt(), drawnH.toInt())
                )

                // 2. Compute Selection Box Position
                val boxPx = min(canvasW, canvasH) * boxSizeRatio
                val boxLeft = (canvasW * boxCenterRatio.x) - (boxPx / 2f)
                val boxTop = (canvasH * boxCenterRatio.y) - (boxPx / 2f)

                // 3. Shaded Dimmed Area Outside Box
                val dimColor = Color.Black.copy(alpha = 0.50f)
                // Top
                drawRect(dimColor, topLeft = Offset(0f, 0f), size = Size(canvasW, max(0f, boxTop)))
                // Bottom
                drawRect(dimColor, topLeft = Offset(0f, boxTop + boxPx), size = Size(canvasW, max(0f, canvasH - (boxTop + boxPx))))
                // Left
                drawRect(dimColor, topLeft = Offset(0f, boxTop), size = Size(max(0f, boxLeft), boxPx))
                // Right
                drawRect(dimColor, topLeft = Offset(boxLeft + boxPx, boxTop), size = Size(max(0f, canvasW - (boxLeft + boxPx)), boxPx))

                // 4. Box Border & Center Crosshair
                drawRect(
                    color = Color(0xFF00E676),
                    topLeft = Offset(boxLeft, boxTop),
                    size = Size(boxPx, boxPx),
                    style = Stroke(width = 3.dp.toPx())
                )

                // Corner Highlights
                val cLen = 22.dp.toPx()
                val sW = 4.5.dp.toPx()
                val cCol = Color.White
                // TL
                drawLine(cCol, Offset(boxLeft, boxTop), Offset(boxLeft + cLen, boxTop), sW)
                drawLine(cCol, Offset(boxLeft, boxTop), Offset(boxLeft, boxTop + cLen), sW)
                // TR
                drawLine(cCol, Offset(boxLeft + boxPx, boxTop), Offset(boxLeft + boxPx - cLen, boxTop), sW)
                drawLine(cCol, Offset(boxLeft + boxPx, boxTop), Offset(boxLeft + boxPx, boxTop + cLen), sW)
                // BL
                drawLine(cCol, Offset(boxLeft, boxTop + boxPx), Offset(boxLeft + cLen, boxTop + boxPx), sW)
                drawLine(cCol, Offset(boxLeft, boxTop + boxPx), Offset(boxLeft, boxTop + boxPx - cLen), sW)
                // BR
                drawLine(cCol, Offset(boxLeft + boxPx, boxTop + boxPx), Offset(boxLeft + boxPx - cLen, boxTop + boxPx), sW)
                drawLine(cCol, Offset(boxLeft + boxPx, boxTop + boxPx), Offset(boxLeft + boxPx, boxTop + boxPx - cLen), sW)

                // Center crosshair
                val midX = boxLeft + boxPx / 2f
                val midY = boxTop + boxPx / 2f
                val ch = 10.dp.toPx()
                drawLine(Color.Yellow, Offset(midX - ch, midY), Offset(midX + ch, midY), 2.dp.toPx())
                drawLine(Color.Yellow, Offset(midX, midY - ch), Offset(midX, midY + ch), 2.dp.toPx())
            }

            if (isProcessing) {
                CircularProgressIndicator(
                    color = Color(0xFF00E676),
                    modifier = Modifier.size(52.dp)
                )
            }
        }

        // Result Card (if code is found)
        AnimatedVisibility(
            visible = decodeResult != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            decodeResult?.let { result ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                color = Color(0xFF00E676),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = result.formatName,
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Text(result.details, color = Color.Gray, fontSize = 11.sp)
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = result.text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Button(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("TinyQR", result.text))
                                    Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Copy", fontSize = 12.sp)
                            }

                            if (result.text.startsWith("http://") || result.text.startsWith("https://") || result.text.startsWith("www.")) {
                                Button(
                                    onClick = {
                                        val url = if (!result.text.startsWith("http")) "https://${result.text}" else result.text
                                        try {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                        } catch (_: Exception) {}
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0091EA)),
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.OpenInBrowser, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Open", fontSize = 12.sp)
                                }
                            }

                            Button(
                                onClick = {
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, result.text)
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, "Share Code"))
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Share", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // Bottom Controls: Box Size, Zoom Presets, and Big Prominent "SCAN SELECTED BOX" Button
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A1A))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Box Size Adjustment
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Box Size:", color = Color.LightGray, fontSize = 12.sp)

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = { boxSizeRatio = 0.20f },
                        colors = ButtonDefaults.buttonColors(containerColor = if (boxSizeRatio == 0.20f) Color(0xFF2196F3) else Color(0xFF333333)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) { Text("Tiny 20%", fontSize = 11.sp) }

                    Button(
                        onClick = { boxSizeRatio = 0.40f },
                        colors = ButtonDefaults.buttonColors(containerColor = if (boxSizeRatio == 0.40f) Color(0xFF2196F3) else Color(0xFF333333)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) { Text("Med 40%", fontSize = 11.sp) }

                    Button(
                        onClick = { boxSizeRatio = 0.70f },
                        colors = ButtonDefaults.buttonColors(containerColor = if (boxSizeRatio == 0.70f) Color(0xFF2196F3) else Color(0xFF333333)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) { Text("Large 70%", fontSize = 11.sp) }

                    Button(
                        onClick = {
                            scale = 1f
                            panOffset = Offset.Zero
                            boxCenterRatio = Offset(0.5f, 0.5f)
                            boxSizeRatio = 0.40f
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF444444)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) { Text("Reset", fontSize = 11.sp) }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Prominent Scan Button
            Button(
                onClick = { cropAndScanBox(canvasSize.width, canvasSize.height) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(imageVector = Icons.Default.CropFree, contentDescription = null, tint = Color.Black, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "SCAN SELECTED BOX",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }
}
