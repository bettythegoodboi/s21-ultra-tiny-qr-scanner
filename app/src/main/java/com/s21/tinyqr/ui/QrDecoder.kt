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

/**
 * Simple correct decoder for short industrial Data Matrix.
 * Content example: 00086192327
 *
 * Key: feed clean luminance, try DataMatrixReader first, avoid destructive filters.
 */
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
        // 1) Dedicated Data Matrix reader (correct for this use case)
        val dmReader = DataMatrixReader()
        try {
            val r = dmReader.decode(BinaryBitmap(HybridBinarizer(source)), hintsDm())
            Log.d("TinyQR", "DM: ${r.text}")
            return r.text
        } catch (_: Exception) {
        }
        try {
            val r = dmReader.decode(BinaryBitmap(GlobalHistogramBinarizer(source)), hintsDm())
            Log.d("TinyQR", "DM global: ${r.text}")
            return r.text
        } catch (_: Exception) {
        }

        // 2) MultiFormat fallback
        val multi = MultiFormatReader()
        multi.setHints(hintsAll())
        try {
            val r = multi.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            Log.d("TinyQR", "Multi ${r.barcodeFormat}: ${r.text}")
            return r.text
        } catch (_: Exception) {
        }
        try {
            val r = multi.decodeWithState(BinaryBitmap(GlobalHistogramBinarizer(source)))
            Log.d("TinyQR", "Multi global ${r.barcodeFormat}: ${r.text}")
            return r.text
        } catch (_: Exception) {
        }
        return null
    }

    /** Preferred path: raw camera Y plane (no JPEG loss) */
    fun decodeYuv(yData: ByteArray, width: Int, height: Int): String? {
        if (width < 16 || height < 16) return null

        // Full frame
        try {
            val full = PlanarYUVLuminanceSource(yData, width, height, 0, 0, width, height, false)
            decodeSource(full)?.let { return it }
        } catch (_: Exception) {
        }

        // Center crops at several sizes — tiny code is usually centered when user aims
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

    /** Bitmap path (gallery / captured JPEG) */
    fun decode(bitmap: Bitmap): Pair<String, String>? {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 16 || h < 16) return null

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // Full
        try {
            decodeSource(RGBLuminanceSource(w, h, pixels))?.let { return it to "full" }
        } catch (_: Exception) {
        }

        // Center crops
        for (ratio in floatArrayOf(0.5f, 0.4f, 0.6f, 0.3f)) {
            val cw = (w * ratio).toInt().coerceAtLeast(32)
            val ch = (h * ratio).toInt().coerceAtLeast(32)
            val left = ((w - cw) / 2).coerceAtLeast(0)
            val top = ((h - ch) / 2).coerceAtLeast(0)
            val rw = minOf(cw, w - left)
            val rh = minOf(ch, h - top)
            try {
                val cropBmp = Bitmap.createBitmap(bitmap, left, top, rw, rh)
                // Mild upscale if crop is small
                val target = if (rw < 200 || rh < 200) {
                    val f = 200f / minOf(rw, rh)
                    Bitmap.createScaledBitmap(cropBmp, (rw * f).toInt(), (rh * f).toInt(), true)
                } else cropBmp

                val cp = IntArray(target.width * target.height)
                target.getPixels(cp, 0, target.width, 0, 0, target.width, target.height)
                decodeSource(RGBLuminanceSource(target.width, target.height, cp))?.let {
                    if (target !== cropBmp) target.recycle()
                    if (cropBmp !== bitmap) cropBmp.recycle()
                    return it to "crop"
                }
                if (target !== cropBmp) target.recycle()
                if (cropBmp !== bitmap) cropBmp.recycle()
            } catch (_: Exception) {
            }
        }
        return null
    }
}
