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
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.common.GlobalHistogramBinarizer
import java.util.EnumMap
import java.util.EnumSet

/**
 * Correct multi-format 2D decoder.
 * Supports: QR Code, Data Matrix, Aztec, PDF417
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
        // Important for some industrial / small codes
        map[DecodeHintType.PURE_BARCODE] = false
        return map
    }

    private fun decodeOnce(bitmap: Bitmap, useHybrid: Boolean): String? {
        return try {
            val width = bitmap.width
            val height = bitmap.height
            if (width < 16 || height < 16) return null

            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val source = RGBLuminanceSource(width, height, pixels)

            val binarizer = if (useHybrid) {
                HybridBinarizer(source)
            } else {
                GlobalHistogramBinarizer(source)
            }

            val binary = BinaryBitmap(binarizer)
            val reader = MultiFormatReader()
            reader.setHints(hints())
            val result = reader.decodeWithState(binary)
            Log.d("TinyQR", "Decoded format: ${result.barcodeFormat}")
            result.text
        } catch (e: Exception) {
            null
        }
    }

    private fun toGrayContrast(src: Bitmap, contrast: Float = 1.5f): Bitmap {
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
     * Try decode with correct formats.
     * Order: original → contrast → invert → both binarizers
     */
    fun decode(bitmap: Bitmap): Pair<String, String>? {
        val variants = listOf(
            bitmap,
            toGrayContrast(bitmap, 1.5f),
            toGrayContrast(bitmap, 2.0f),
            invert(bitmap),
            invert(toGrayContrast(bitmap, 1.6f))
        )

        for (variant in variants) {
            // Hybrid binarizer (default, good for most)
            decodeOnce(variant, useHybrid = true)?.let {
                return it to "ZXing"
            }
            // Global histogram (sometimes better for uniform codes)
            decodeOnce(variant, useHybrid = false)?.let {
                return it to "ZXing"
            }
        }

        // Cleanup variants except original
        for (v in variants) {
            if (v !== bitmap) {
                try { v.recycle() } catch (_: Exception) {}
            }
        }
        return null
    }
}
