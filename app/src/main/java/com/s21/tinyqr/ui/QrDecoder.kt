package com.s21.tinyqr.ui

import android.graphics.Bitmap
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.datamatrix.DataMatrixReader
import java.util.EnumMap

/**
 * Simple proven decoder — same approach as DataMatrixScanner.
 *
 * 4 passes only:
 * 1. HybridBinarizer (normal)
 * 2. Inverted (DPM / laser etch white-on-dark)
 * 3. Contrast stretch then Hybrid
 * 4. GlobalHistogramBinarizer
 *
 * Plus center ROI crop. No custom deblur / grid search.
 */
object QrDecoder {

    private val reader = DataMatrixReader()
    private val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
        put(DecodeHintType.POSSIBLE_FORMATS, listOf(BarcodeFormat.DATA_MATRIX))
        put(DecodeHintType.TRY_HARDER, java.lang.Boolean.TRUE)
    }

    private fun decodeSource(source: LuminanceSource): String? {
        // Pass 1: Standard Hybrid
        try {
            val r = reader.decode(BinaryBitmap(HybridBinarizer(source)), hints)
            reader.reset()
            return r.text
        } catch (_: Exception) {
            reader.reset()
        }

        // Pass 2: Inverted (white-on-dark / DPM)
        try {
            val inv = source.invert()
            val r = reader.decode(BinaryBitmap(HybridBinarizer(inv)), hints)
            reader.reset()
            return r.text
        } catch (_: Exception) {
            reader.reset()
        }

        // Pass 3: Contrast stretch
        try {
            val enhanced = contrastStretch(source)
            val r = reader.decode(BinaryBitmap(HybridBinarizer(enhanced)), hints)
            reader.reset()
            return r.text
        } catch (_: Exception) {
            reader.reset()
        }

        // Pass 4: Global histogram
        try {
            val r = reader.decode(BinaryBitmap(GlobalHistogramBinarizer(source)), hints)
            reader.reset()
            return r.text
        } catch (_: Exception) {
            reader.reset()
        }

        // Pass 4b: Global on inverted
        try {
            val r = reader.decode(BinaryBitmap(GlobalHistogramBinarizer(source.invert())), hints)
            reader.reset()
            return r.text
        } catch (_: Exception) {
            reader.reset()
        }

        return null
    }

    private fun contrastStretch(source: LuminanceSource): LuminanceSource {
        val matrix = source.matrix
        var minV = 255
        var maxV = 0
        for (i in matrix.indices) {
            val v = matrix[i].toInt() and 0xFF
            if (v < minV) minV = v
            if (v > maxV) maxV = v
        }
        val range = maxV - minV
        if (range <= 10 || range >= 220) return source

        val scale = 255f / range
        val out = ByteArray(matrix.size)
        for (i in matrix.indices) {
            val v = matrix[i].toInt() and 0xFF
            out[i] = ((v - minV) * scale).toInt().coerceIn(0, 255).toByte()
        }
        val w = source.width
        val h = source.height
        return object : LuminanceSource(w, h) {
            override fun getRow(y: Int, row: ByteArray?): ByteArray {
                val r = if (row == null || row.size < w) ByteArray(w) else row
                System.arraycopy(out, y * w, r, 0, w)
                return r
            }
            override fun getMatrix(): ByteArray = out
        }
    }

    /** Live camera: raw Y plane + center crop (default 65%) */
    fun decodeYuv(yData: ByteArray, width: Int, height: Int, cropPercent: Float = 0.65f): String? {
        if (width < 16 || height < 16) return null
        try {
            val cropW = (width * cropPercent).toInt().coerceAtLeast(16)
            val cropH = (height * cropPercent).toInt().coerceAtLeast(16)
            val left = (width - cropW) / 2
            val top = (height - cropH) / 2

            val source = PlanarYUVLuminanceSource(
                yData, width, height, left, top, cropW, cropH, false
            )
            decodeSource(source)?.let { return it }

            // Full frame fallback
            val full = PlanarYUVLuminanceSource(
                yData, width, height, 0, 0, width, height, false
            )
            return decodeSource(full)
        } catch (e: Exception) {
            Log.e("TinyQR", "yuv decode", e)
            return null
        }
    }

    /** Gallery / still image */
    fun decode(bitmap: Bitmap): Pair<String, String>? {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 16 || h < 16) return null

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val full = RGBLuminanceSource(w, h, pixels)

        // Full
        decodeSource(full)?.let { return it to "full" }

        // Center crops
        for (ratio in floatArrayOf(0.65f, 0.5f, 0.4f, 0.8f)) {
            val cw = (w * ratio).toInt().coerceAtLeast(16)
            val ch = (h * ratio).toInt().coerceAtLeast(16)
            val left = (w - cw) / 2
            val top = (h - ch) / 2
            try {
                decodeSource(full.crop(left, top, cw, ch))?.let { return it to "crop" }
            } catch (_: Exception) {
            }
        }

        // Quadrants (if image is large)
        if (w > 200 && h > 200) {
            val hw = w / 2
            val hh = h / 2
            for ((x, y) in listOf(0 to 0, hw to 0, 0 to hh, hw to hh)) {
                try {
                    decodeSource(full.crop(x, y, hw, hh))?.let { return it to "quad" }
                } catch (_: Exception) {
                }
            }
        }

        return null
    }
}
