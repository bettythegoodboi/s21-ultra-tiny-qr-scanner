package com.s21.tinyqr.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import java.util.EnumMap

/**
 * Aggressive multi-pass decoder for tiny / low-contrast / unusual QR codes.
 * Tries many combinations until one succeeds.
 */
object QrDecoder {

    private val formats = listOf(
        com.google.zxing.BarcodeFormat.QR_CODE,
        com.google.zxing.BarcodeFormat.DATA_MATRIX,
        com.google.zxing.BarcodeFormat.AZTEC,
        com.google.zxing.BarcodeFormat.PDF_417,
        com.google.zxing.BarcodeFormat.MAXICODE
    )

    private fun baseHints(): EnumMap<DecodeHintType, Any> {
        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
        hints[DecodeHintType.TRY_HARDER] = true
        hints[DecodeHintType.POSSIBLE_FORMATS] = formats
        hints[DecodeHintType.CHARACTER_SET] = "UTF-8"
        return hints
    }

    private fun toPixels(bitmap: Bitmap): IntArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return pixels
    }

    private fun tryDecode(bitmap: Bitmap): String? {
        if (bitmap.width < 20 || bitmap.height < 20) return null
        return try {
            val pixels = toPixels(bitmap)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)

            // Try HybridBinarizer first
            try {
                val binary = BinaryBitmap(HybridBinarizer(source))
                val reader = MultiFormatReader()
                reader.setHints(baseHints())
                return reader.decodeWithState(binary).text
            } catch (_: Exception) {}

            // Then GlobalHistogramBinarizer
            try {
                val binary = BinaryBitmap(GlobalHistogramBinarizer(source))
                val reader = MultiFormatReader()
                reader.setHints(baseHints())
                return reader.decodeWithState(binary).text
            } catch (_: Exception) {}

            null
        } catch (e: Exception) {
            null
        }
    }

    private fun applyContrast(src: Bitmap, contrast: Float, invert: Boolean = false): Bitmap {
        val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()
        val translate = (-0.5f * contrast + 0.5f) * 255f
        val cm = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        // Grayscale
        val gray = ColorMatrix()
        gray.setSaturation(0f)
        cm.postConcat(gray)

        if (invert) {
            val inv = ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            cm.postConcat(inv)
        }

        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return result
    }

    private fun scale(src: Bitmap, factor: Float): Bitmap {
        val w = (src.width * factor).toInt().coerceAtLeast(1)
        val h = (src.height * factor).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun centerCrop(src: Bitmap, ratio: Float = 0.6f): Bitmap {
        val w = src.width
        val h = src.height
        val cw = (w * ratio).toInt().coerceAtLeast(50)
        val ch = (h * ratio).toInt().coerceAtLeast(50)
        val left = ((w - cw) / 2).coerceAtLeast(0)
        val top = ((h - ch) / 2).coerceAtLeast(0)
        return Bitmap.createBitmap(src, left, top, cw.coerceAtMost(w - left), ch.coerceAtMost(h - top))
    }

    /**
     * Main entry: try many strategies until one works.
     */
    fun decode(bitmap: Bitmap): String? {
        val strategies = mutableListOf<Bitmap>()

        // 1. Original
        strategies.add(bitmap)

        // 2. Strong contrast grayscale
        strategies.add(applyContrast(bitmap, 1.8f, invert = false))

        // 3. Inverted (white QR on dark background)
        strategies.add(applyContrast(bitmap, 1.6f, invert = true))

        // 4. Very strong contrast
        strategies.add(applyContrast(bitmap, 2.4f, invert = false))

        // 5. Center crop (QR often in middle when zoomed)
        try {
            strategies.add(centerCrop(bitmap, 0.55f))
            strategies.add(applyContrast(centerCrop(bitmap, 0.55f), 1.9f))
        } catch (_: Exception) {}

        // 6. Upscale versions (helps tiny modules)
        try {
            strategies.add(scale(bitmap, 1.5f))
            strategies.add(applyContrast(scale(bitmap, 1.5f), 1.7f))
            strategies.add(scale(bitmap, 2.0f))
            strategies.add(applyContrast(scale(bitmap, 2.0f), 1.8f))
            strategies.add(applyContrast(scale(bitmap, 2.0f), 1.6f, invert = true))
        } catch (_: Exception) {}

        // 7. Downscale sometimes helps noise
        try {
            strategies.add(scale(bitmap, 0.75f))
            strategies.add(applyContrast(scale(bitmap, 0.75f), 2.0f))
        } catch (_: Exception) {}

        for ((index, candidate) in strategies.withIndex()) {
            val result = tryDecode(candidate)
            if (result != null) {
                Log.d("TinyQR", "Decoded with strategy #$index")
                // Recycle intermediates (not the original)
                if (candidate !== bitmap) {
                    try { candidate.recycle() } catch (_: Exception) {}
                }
                // Recycle remaining
                for (i in (index + 1) until strategies.size) {
                    if (strategies[i] !== bitmap) {
                        try { strategies[i].recycle() } catch (_: Exception) {}
                    }
                }
                return result
            }
            if (candidate !== bitmap) {
                try { candidate.recycle() } catch (_: Exception) {}
            }
        }

        Log.d("TinyQR", "All decode strategies failed")
        return null
    }
}
