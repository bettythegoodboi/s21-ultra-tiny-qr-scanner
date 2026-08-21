package com.s21.tinyqr.ui

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.max
import kotlin.math.min

@Composable
fun ImageCropDialog(
    bitmap: Bitmap,
    onDismiss: () -> Unit,
    onDecodeSelection: (Bitmap) -> Unit
) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Normalized crop box in [0..1] range relative to the displayed image area
    var cropLeft by remember { mutableStateOf(0.25f) }
    var cropTop by remember { mutableStateOf(0.25f) }
    var cropWidth by remember { mutableStateOf(0.50f) }
    var cropHeight by remember { mutableStateOf(0.50f) }

    var isProcessing by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF121212)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Locate Tiny Code",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Drag the box over the QR / Data Matrix",
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )
                    }
                    IconButton(onClick = {
                        scale = 1f
                        offset = Offset.Zero
                        cropLeft = 0.25f
                        cropTop = 0.25f
                        cropWidth = 0.50f
                        cropHeight = 0.50f
                    }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reset",
                            tint = Color.Cyan
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Interactive Image View & Crop Box
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color.Black, shape = RoundedCornerShape(12.dp))
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(0.8f, 10f)
                                offset += pan
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    // Move crop box normalized
                                    val dx = dragAmount.x / size.width
                                    val dy = dragAmount.y / size.height
                                    cropLeft = (cropLeft + dx).coerceIn(0f, 1f - cropWidth)
                                    cropTop = (cropTop + dy).coerceIn(0f, 1f - cropHeight)
                                }
                            }
                    ) {
                        val canvasW = size.width
                        val canvasH = size.height
                        val imgW = imageBitmap.width.toFloat()
                        val imgH = imageBitmap.height.toFloat()

                        // Compute fitted image rect
                        val aspect = imgW / imgH
                        val cAspect = canvasW / canvasH
                        val drawW: Float
                        val drawH: Float
                        if (aspect > cAspect) {
                            drawW = canvasW
                            drawH = canvasW / aspect
                        } else {
                            drawH = canvasH
                            drawW = canvasH * aspect
                        }

                        val imgLeft = (canvasW - drawW) / 2f
                        val imgTop = (canvasH - drawH) / 2f

                        // Draw background dimmed
                        drawRect(Color.Black)

                        // Draw image
                        drawImage(
                            image = imageBitmap,
                            dstOffset = androidx.compose.ui.unit.IntOffset(
                                (imgLeft + offset.x).toInt(),
                                (imgTop + offset.y).toInt()
                            ),
                            dstSize = androidx.compose.ui.unit.IntSize(
                                (drawW * scale).toInt(),
                                (drawH * scale).toInt()
                            )
                        )

                        // Calculate crop rect in screen pixels
                        val boxX = canvasW * cropLeft
                        val boxY = canvasH * cropTop
                        val boxW = canvasW * cropWidth
                        val boxH = canvasH * cropHeight

                        // Darken outside the crop box
                        // Top
                        drawRect(Color.Black.copy(alpha = 0.55f), topLeft = Offset(0f, 0f), size = Size(canvasW, boxY))
                        // Bottom
                        drawRect(Color.Black.copy(alpha = 0.55f), topLeft = Offset(0f, boxY + boxH), size = Size(canvasW, canvasH - (boxY + boxH)))
                        // Left
                        drawRect(Color.Black.copy(alpha = 0.55f), topLeft = Offset(0f, boxY), size = Size(boxX, boxH))
                        // Right
                        drawRect(Color.Black.copy(alpha = 0.55f), topLeft = Offset(boxX + boxW, boxY), size = Size(canvasW - (boxX + boxW), boxH))

                        // Draw target box border
                        drawRect(
                            color = Color(0xFF00E676),
                            topLeft = Offset(boxX, boxY),
                            size = Size(boxW, boxH),
                            style = Stroke(width = 3.dp.toPx())
                        )

                        // Corner markers
                        val cornerLen = 20.dp.toPx()
                        val strokeW = 4.dp.toPx()
                        // Top-left
                        drawLine(Color.White, Offset(boxX, boxY), Offset(boxX + cornerLen, boxY), strokeW)
                        drawLine(Color.White, Offset(boxX, boxY), Offset(boxX, boxY + cornerLen), strokeW)
                        // Top-right
                        drawLine(Color.White, Offset(boxX + boxW, boxY), Offset(boxX + boxW - cornerLen, boxY), strokeW)
                        drawLine(Color.White, Offset(boxX + boxW, boxY), Offset(boxX + boxW, boxY + cornerLen), strokeW)
                        // Bottom-left
                        drawLine(Color.White, Offset(boxX, boxY + boxH), Offset(boxX + cornerLen, boxY + boxH), strokeW)
                        drawLine(Color.White, Offset(boxX, boxY + boxH), Offset(boxX, boxY + boxH - cornerLen), strokeW)
                        // Bottom-right
                        drawLine(Color.White, Offset(boxX + boxW, boxY + boxH), Offset(boxX + boxW - cornerLen, boxY + boxH), strokeW)
                        drawLine(Color.White, Offset(boxX + boxW, boxY + boxH), Offset(boxX + boxW, boxY + boxH - cornerLen), strokeW)

                        // Center crosshair
                        val midX = boxX + boxW / 2f
                        val midY = boxY + boxH / 2f
                        val chLen = 10.dp.toPx()
                        drawLine(Color.Yellow.copy(alpha = 0.8f), Offset(midX - chLen, midY), Offset(midX + chLen, midY), 2.dp.toPx())
                        drawLine(Color.Yellow.copy(alpha = 0.8f), Offset(midX, midY - chLen), Offset(midX, midY + chLen), 2.dp.toPx())
                    }

                    if (isProcessing) {
                        CircularProgressIndicator(
                            color = Color(0xFF00E676),
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Crop Size Adjuster Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Box Size:", color = Color.White, fontSize = 13.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Slider(
                        value = cropWidth,
                        onValueChange = {
                            cropWidth = it
                            cropHeight = it // Keep square for typical 2D barcodes
                            cropLeft = cropLeft.coerceIn(0f, 1f - cropWidth)
                            cropTop = cropTop.coerceIn(0f, 1f - cropHeight)
                        },
                        valueRange = 0.10f..0.90f,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Preset zoom buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(
                        onClick = { cropWidth = 0.20f; cropHeight = 0.20f },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Tiny (20%)", fontSize = 12.sp)
                    }
                    Button(
                        onClick = { cropWidth = 0.40f; cropHeight = 0.40f },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Medium (40%)", fontSize = 12.sp)
                    }
                    Button(
                        onClick = { cropWidth = 0.70f; cropHeight = 0.70f },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Large (70%)", fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Action button: Decode Selection
                Button(
                    onClick = {
                        isProcessing = true
                        try {
                            val imgW = bitmap.width
                            val imgH = bitmap.height

                            val selX = (cropLeft * imgW).toInt().coerceIn(0, imgW - 1)
                            val selY = (cropTop * imgH).toInt().coerceIn(0, imgH - 1)
                            val selW = (cropWidth * imgW).toInt().coerceIn(16, imgW - selX)
                            val selH = (cropHeight * imgH).toInt().coerceIn(16, imgH - selY)

                            val croppedBitmap = Bitmap.createBitmap(bitmap, selX, selY, selW, selH)
                            onDecodeSelection(croppedBitmap)
                        } catch (_: Exception) {
                            isProcessing = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.Done, contentDescription = null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Decode Selected Box", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
    }
}
