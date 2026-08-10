package com.s21.tinyqr.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import java.util.EnumMap
import java.util.EnumSet

/**
 * Decoder tuned for tiny industrial / DPM QR codes
 * (dark-on-dark, low contrast, small physical size).
 */
object QrDecoder {

    private val supportedFormats = EnumSet.of(
        BarcodeFormat.QR_CODE,
        BarcodeFormat.DATA_MATRIX,
        BarcodeFormat.AZTEC,
        BarcodeFormat.PDF_417
    )

    private fun hints(): Map<DecodeHintType, Any> {
        val map = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
        map[DecodeHintType.POSSIBLE_FORMATS] = supportedFormats
        map[DecodeHintType.TRY_HARDER] = true
        map[DecodeHintType.CHARACTER_SET] = "UTF-8"
        return map
    }

    private fun decodeOnce(bitmap: Bitmap): String? {
        return try {
            val w = bitmap.width
            val h = bitmap.height
            if (w < 20 || h < 20) return null

            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val source = RGBLuminanceSource(w, h, pixels)

            // Hybrid
            try {
                val reader = MultiFormatReader()
                reader.setHints(hints())
                return reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
            } catch (_: Exception) {}

            // Global histogram
            try {
                val reader = MultiFormatReader()
                reader.setHints(hints())
                return reader.decodeWithState(BinaryBitmap(GlobalHistogramBinarizer(source))).text
            } catch (_: Exception) {}

            null
        } catch (_: Exception) {
            null
        }
    }

    /** Convert to grayscale int array (0-255) */
    private fun toGray(bitmap: Bitmap): IntArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            // ITU-R BT.601
            gray[i] = ((Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000)
        }
        return gray
    }

    /**
     * Simple local adaptive threshold (mean of neighborhood).
     * Critical for dark-on-dark industrial QR.
     */
    private fun adaptiveThreshold(bitmap: Bitmap, blockSize: Int = 25, C: Int = 8): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val gray = toGray(bitmap)
        val outPixels = IntArray(w * h)
        val half = blockSize / 2

        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0
                var count = 0
                for (dy in -half..half) {
                    for (dx in -half..half) {
                        val ny = (y + dy).coerceIn(0, h - 1)
                        val nx = (x + dx).coerceIn(0, w - 1)
                        sum += gray[ny * w + nx]
                        count++
                    }
                }
                val mean = sum / count
                val value = if (gray[y * w + x] < mean - C) 0 else 255
                outPixels[y * w + x] = Color.rgb(value, value, value)
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(outPixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun scale(src: Bitmap, factor: Float): Bitmap {
        val w = (src.width * factor).toInt().coerceAtLeast(32)
        val h = (src.height * factor).toInt().coerceAtLeast(32)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun centerCrop(src: Bitmap, ratio: Float): Bitmap {
        val w = src.width
        val h = src.height
        val cw = (w * ratio).toInt().coerceAtLeast(40)
        val ch = (h * ratio).toInt().coerceAtLeast(40)
        val left = ((w - cw) / 2).coerceAtLeast(0)
        val top = ((h - ch) / 2).coerceAtLeast(0)
        return Bitmap.createBitmap(src, left, top, cw.coerceAtMost(w - left), ch.coerceAtMost(h - top))
    }

    private fun contrastGray(src: Bitmap, contrast: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint()
        val t = (-0.5f * contrast + 0.5f) * 255f
        val cm = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, t,
                0f, contrast, 0f, 0f, t,
                0f, 0f, contrast, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val gray = ColorMatrix()
        gray.setSaturation(0f)
        cm.postConcat(gray)
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun invert(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint()
        val cm = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /**
     * Main decode entry for live camera + gallery.
     * Returns (text, engineName) or null.
     */
    fun decode(bitmap: Bitmap): Pair<String, String>? {
        val candidates = mutableListOf<Bitmap>()

        // Original
        candidates.add(bitmap)

        // Center crops (QR is usually centered when zoomed)
        try {
            candidates.add(centerCrop(bitmap, 0.5f))
            candidates.add(centerCrop(bitmap, 0.4f))
            candidates.add(centerCrop(bitmap, 0.65f))
        } catch (_: Exception) {}

        // Contrast versions
        candidates.add(contrastGray(bitmap, 1.8f))
        candidates.add(contrastGray(bitmap, 2.5f))

        // Adaptive threshold (key for dark-on-dark industrial marks)
        try {
            val crop = centerCrop(bitmap, 0.55f)
            val up = scale(crop, 2.5f)
            candidates.add(adaptiveThreshold(up, blockSize = 21, C = 6))
            candidates.add(adaptiveThreshold(up, blockSize = 31, C = 10))
            candidates.add(adaptiveThreshold(up, blockSize = 15, C = 4))
            candidates.add(invert(adaptiveThreshold(up, blockSize = 21, C = 6)))
        } catch (e: Exception) {
            Log.e("TinyQR", "Adaptive failed", e)
        }

        // Upscaled original
        try {
            candidates.add(scale(bitmap, 2f))
            candidates.add(contrastGray(scale(centerCrop(bitmap, 0.5f), 3f), 2.0f))
        } catch (_: Exception) {}

        // Inverted
        candidates.add(invert(bitmap))
        candidates.add(invert(contrastGray(bitmap, 1.8f)))

        for ((i, candidate) in candidates.withIndex()) {
            val text = decodeOnce(candidate)
            if (text != null) {
                Log.d("TinyQR", "Success strategy #$i")
                // recycle non-original
                for ((j, c) in candidates.withIndex()) {
                    if (j != i && c !== bitmap) {
                        try { c.recycle() } catch (_: Exception) {}
                    }
                }
                return text to "ZXing"
            }
        }

        for (c in candidates) {
            if (c !== bitmap) {
                try { c.recycle() } catch (_: Exception) {}
            }
        }

        Log.d("TinyQR", "All strategies failed")
        return null
    }
}
