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
 * Decoder tuned for tiny industrial QR codes (a few mm on curved/dark parts).
 * Main fix: center-crop + strong upscale so each module has enough pixels.
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
        if (bitmap.width < 20 || bitmap.height < 20) return null
        return try {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val source = RGBLuminanceSource(w, h, pixels)

            // Hybrid first
            try {
                val reader = MultiFormatReader()
                reader.setHints(hints())
                return reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
            } catch (_: Exception) {}

            // Global histogram fallback
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

    /** Center crop – tiny QR is usually in the middle when zoomed */
    private fun centerCrop(src: Bitmap, ratio: Float): Bitmap {
        val w = src.width
        val h = src.height
        val cw = (w * ratio).toInt().coerceAtLeast(40)
        val ch = (h * ratio).toInt().coerceAtLeast(40)
        val left = ((w - cw) / 2).coerceAtLeast(0)
        val top = ((h - ch) / 2).coerceAtLeast(0)
        return Bitmap.createBitmap(src, left, top, cw.coerceAtMost(w - left), ch.coerceAtMost(h - top))
    }

    /** Upscale so each QR module has enough pixels to decode */
    private fun upscale(src: Bitmap, factor: Float): Bitmap {
        val w = (src.width * factor).toInt().coerceAtLeast(1)
        val h = (src.height * factor).toInt().coerceAtLeast(1)
        // Cap to avoid OOM on large frames
        val maxSide = 1200
        val scale = if (w > maxSide || h > maxSide) {
            maxSide.toFloat() / maxOf(w, h)
        } else 1f
        val fw = (w * scale).toInt().coerceAtLeast(1)
        val fh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, fw, fh, true)
    }

    private fun tryVariant(bmp: Bitmap): String? {
        decodeOnce(bmp)?.let { return it }
        val c1 = contrastGray(bmp, 1.6f)
        decodeOnce(c1)?.let { c1.recycle(); return it }
        val c2 = contrastGray(bmp, 2.2f)
        decodeOnce(c2)?.let { c1.recycle(); c2.recycle(); return it }
        val inv = invert(c1)
        decodeOnce(inv)?.let {
            c1.recycle(); c2.recycle(); inv.recycle(); return it
        }
        c1.recycle(); c2.recycle(); inv.recycle()
        return null
    }

    /**
     * Main entry for tiny industrial QR.
     * Strategy: full frame → center crops → strong upscale → contrast/invert
     */
    fun decode(bitmap: Bitmap): Pair<String, String>? {
        // 1. Full frame as-is
        tryVariant(bitmap)?.let { return it to "full" }

        // 2. Center crops at different sizes (QR is usually centered when user aims)
        val cropRatios = listOf(0.45f, 0.55f, 0.35f, 0.65f)
        val scales = listOf(3f, 4f, 5f, 2.5f)

        for (ratio in cropRatios) {
            val crop = try {
                centerCrop(bitmap, ratio)
            } catch (_: Exception) {
                continue
            }

            // Try crop directly
            tryVariant(crop)?.let {
                if (crop !== bitmap) crop.recycle()
                return it to "crop"
            }

            // Upscale the crop (critical for tiny codes)
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

        // 3. Upscale full frame as last resort
        try {
            val upFull = upscale(bitmap, 2.5f)
            tryVariant(upFull)?.let {
                upFull.recycle()
                return it to "full-up"
            }
            upFull.recycle()
        } catch (_: Exception) {}

        Log.d("TinyQR", "All decode attempts failed")
        return null
    }
}
