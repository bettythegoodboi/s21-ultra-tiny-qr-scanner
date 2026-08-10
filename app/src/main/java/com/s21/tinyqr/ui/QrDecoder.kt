package com.s21.tinyqr.ui

import android.graphics.Bitmap
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.datamatrix.DataMatrixReader
import java.util.EnumSet
import java.util.Hashtable

object QrDecoder {

    private fun hintsDm(): Hashtable<DecodeHintType, Any> {
        val h = Hashtable<DecodeHintType, Any>()
        h[DecodeHintType.POSSIBLE_FORMATS] = EnumSet.of(BarcodeFormat.DATA_MATRIX)
        h[DecodeHintType.TRY_HARDER] = java.lang.Boolean.TRUE
        h[DecodeHintType.CHARACTER_SET] = "UTF-8"
        return h
    }

    private fun hintsAll(): Hashtable<DecodeHintType, Any> {
        val h = Hashtable<DecodeHintType, Any>()
        h[DecodeHintType.POSSIBLE_FORMATS] = EnumSet.of(
            BarcodeFormat.DATA_MATRIX,
            BarcodeFormat.QR_CODE,
            BarcodeFormat.AZTEC,
            BarcodeFormat.PDF_417
        )
        h[DecodeHintType.TRY_HARDER] = java.lang.Boolean.TRUE
        h[DecodeHintType.CHARACTER_SET] = "UTF-8"
        return h
    }

    private fun decodeSource(source: LuminanceSource): String? {
        val dmReader = DataMatrixReader()
        try {
            return dmReader.decode(BinaryBitmap(HybridBinarizer(source)), hintsDm()).text
        } catch (_: Exception) {
        }
        try {
            return dmReader.decode(BinaryBitmap(GlobalHistogramBinarizer(source)), hintsDm()).text
        } catch (_: Exception) {
        }

        val multi = MultiFormatReader()
        multi.setHints(hintsAll())
        try {
            return multi.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: Exception) {
        }
        try {
            return multi.decodeWithState(BinaryBitmap(GlobalHistogramBinarizer(source))).text
        } catch (_: Exception) {
        }
        return null
    }

    fun decodeYuv(yData: ByteArray, width: Int, height: Int): String? {
        if (width < 16 || height < 16) return null
        try {
            val full = PlanarYUVLuminanceSource(yData, width, height, 0, 0, width, height, false)
            decodeSource(full)?.let { return it }
        } catch (_: Exception) {
        }
        for (ratio in floatArrayOf(0.5f, 0.4f, 0.6f, 0.3f, 0.7f)) {
            val cw = (width * ratio).toInt().coerceAtLeast(32)
            val ch = (height * ratio).toInt().coerceAtLeast(32)
            val left = ((width - cw) / 2).coerceAtLeast(0)
            val top = ((height - ch) / 2).coerceAtLeast(0)
            val rw = minOf(cw, width - left)
            val rh = minOf(ch, height - top)
            if (rw < 24 || rh < 24) continue
            try {
                val crop = PlanarYUVLuminanceSource(yData, width, height, left, top, rw, rh, false)
                decodeSource(crop)?.let { return it }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun tryBitmap(bitmap: Bitmap): String? {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 16 || h < 16) return null
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return try {
            decodeSource(RGBLuminanceSource(w, h, pixels))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Still image / gallery path — try full, crops, and upscales.
     */
    fun decode(bitmap: Bitmap): Pair<String, String>? {
        Log.d("TinyQR", "Gallery decode ${bitmap.width}x${bitmap.height}")

        // 1) Full image
        tryBitmap(bitmap)?.let { return it to "full" }

        // 2) Upscale full if small
        if (bitmap.width < 800 || bitmap.height < 800) {
            val f = 800f / minOf(bitmap.width, bitmap.height)
            val up = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * f).toInt(),
                (bitmap.height * f).toInt(),
                true
            )
            tryBitmap(up)?.let {
                if (up !== bitmap) up.recycle()
                return it to "up-full"
            }
            if (up !== bitmap) up.recycle()
        }

        // 3) Center crops + optional upscale
        for (ratio in floatArrayOf(0.5f, 0.4f, 0.6f, 0.3f, 0.7f, 0.25f)) {
            val w = bitmap.width
            val h = bitmap.height
            val cw = (w * ratio).toInt().coerceAtLeast(32)
            val ch = (h * ratio).toInt().coerceAtLeast(32)
            val left = ((w - cw) / 2).coerceAtLeast(0)
            val top = ((h - ch) / 2).coerceAtLeast(0)
            val rw = minOf(cw, w - left)
            val rh = minOf(ch, h - top)
            if (rw < 24 || rh < 24) continue

            val crop = try {
                Bitmap.createBitmap(bitmap, left, top, rw, rh)
            } catch (_: Exception) {
                continue
            }

            tryBitmap(crop)?.let {
                if (crop !== bitmap) crop.recycle()
                return it to "crop"
            }

            // Upscale small crops so modules have enough pixels
            for (scale in floatArrayOf(2f, 3f, 4f, 5f)) {
                val tw = (rw * scale).toInt()
                val th = (rh * scale).toInt()
                if (tw > 2000 || th > 2000) continue
                val up = try {
                    Bitmap.createScaledBitmap(crop, tw, th, true)
                } catch (_: Exception) {
                    continue
                }
                tryBitmap(up)?.let {
                    up.recycle()
                    if (crop !== bitmap) crop.recycle()
                    return it to "crop-up"
                }
                up.recycle()
            }
            if (crop !== bitmap) crop.recycle()
        }

        Log.d("TinyQR", "Gallery decode failed")
        return null
    }
}
