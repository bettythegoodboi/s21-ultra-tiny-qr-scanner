package com.s21.tinyqr.ui

import android.graphics.Bitmap
import android.graphics.Canvas
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
 * Industrial 2D decoder.
 * This part code is Data Matrix (confirmed by Cognex: 00086192327).
 * Prioritize Data Matrix, then QR / Aztec / PDF417.
 */
object QrDecoder {

    // Data Matrix FIRST - industrial part marks are usually Data Matrix
    private val formatsDataMatrixFirst = EnumSet.of(
        BarcodeFormat.DATA_MATRIX,
        BarcodeFormat.QR_CODE,
        BarcodeFormat.AZTEC,
        BarcodeFormat.PDF_417
    )

    private val formatsDataMatrixOnly = EnumSet.of(
        BarcodeFormat.DATA_MATRIX
    )

    private fun hints(formats: EnumSet<BarcodeFormat>): Map<DecodeHintType, Any> {
        val map = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
        map[DecodeHintType.POSSIBLE_FORMATS] = formats
        map[DecodeHintType.TRY_HARDER] = true
        map[DecodeHintType.CHARACTER_SET] = "UTF-8"
        return map
    }

    private fun decodeOnce(bitmap: Bitmap, formats: EnumSet<BarcodeFormat>): String? {
        if (bitmap.width < 16 || bitmap.height < 16) return null
        return try {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val source = RGBLuminanceSource(w, h, pixels)

            // Hybrid
            try {
                val reader = MultiFormatReader()
                reader.setHints(hints(formats))
                val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
                Log.d("TinyQR", "Decoded ${result.barcodeFormat}: ${result.text}")
                return result.text
            } catch (_: Exception) {}

            // Global histogram
            try {
                val reader = MultiFormatReader()
                reader.setHints(hints(formats))
                val result = reader.decodeWithState(BinaryBitmap(GlobalHistogramBinarizer(source)))
                Log.d("TinyQR", "Decoded ${result.barcodeFormat}: ${result.text}")
                return result.text
            } catch (_: Exception) {}

            null
        } catch (_: Exception) {
            null
        }
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
        paint.colorFilter = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun centerCrop(src: Bitmap, ratio: Float): Bitmap {
        val w = src.width
        val h = src.height
        val cw = (w * ratio).toInt().coerceAtLeast(32)
        val ch = (h * ratio).toInt().coerceAtLeast(32)
        val left = ((w - cw) / 2).coerceAtLeast(0)
        val top = ((h - ch) / 2).coerceAtLeast(0)
        return Bitmap.createBitmap(src, left, top, cw.coerceAtMost(w - left), ch.coerceAtMost(h - top))
    }

    private fun upscale(src: Bitmap, factor: Float): Bitmap {
        val w = (src.width * factor).toInt().coerceAtLeast(1)
        val h = (src.height * factor).toInt().coerceAtLeast(1)
        val maxSide = 1400
        val scale = if (w > maxSide || h > maxSide) maxSide.toFloat() / maxOf(w, h) else 1f
        return Bitmap.createScaledBitmap(
            src,
            (w * scale).toInt().coerceAtLeast(1),
            (h * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun tryAllFormats(bmp: Bitmap): String? {
        // 1) Data Matrix only first (industrial codes)
        decodeOnce(bmp, formatsDataMatrixOnly)?.let { return it }
        // 2) All formats
        decodeOnce(bmp, formatsDataMatrixFirst)?.let { return it }
        return null
    }

    private fun tryVariant(bmp: Bitmap): String? {
        tryAllFormats(bmp)?.let { return it }

        val c1 = contrastGray(bmp, 1.5f)
        tryAllFormats(c1)?.let { c1.recycle(); return it }

        val c2 = contrastGray(bmp, 2.2f)
        tryAllFormats(c2)?.let { c1.recycle(); c2.recycle(); return it }

        val inv = invert(c1)
        tryAllFormats(inv)?.let {
            c1.recycle(); c2.recycle(); inv.recycle(); return it
        }

        c1.recycle(); c2.recycle(); inv.recycle()
        return null
    }

    fun decode(bitmap: Bitmap): Pair<String, String>? {
        // Full frame
        tryVariant(bitmap)?.let { return it to "DataMatrix/QR" }

        // Center crops + upscale (critical for tiny industrial marks)
        val ratios = listOf(0.4f, 0.5f, 0.35f, 0.6f, 0.3f)
        val scales = listOf(3f, 4f, 5f, 6f, 2.5f)

        for (ratio in ratios) {
            val crop = try {
                centerCrop(bitmap, ratio)
            } catch (_: Exception) {
                continue
            }

            tryVariant(crop)?.let {
                if (crop !== bitmap) crop.recycle()
                return it to "crop"
            }

            for (s in scales) {
                val up = try {
                    upscale(crop, s)
                } catch (_: Exception) {
                    continue
                }
                tryVariant(up)?.let {
                    up.recycle()
                    if (crop !== bitmap) crop.recycle()
                    return it to "upscale"
                }
                up.recycle()
            }
            if (crop !== bitmap) crop.recycle()
        }

        try {
            val upFull = upscale(bitmap, 3f)
            tryVariant(upFull)?.let {
                upFull.recycle()
                return it to "full-up"
            }
            upFull.recycle()
        } catch (_: Exception) {}

        Log.d("TinyQR", "Decode failed")
        return null
    }
}
