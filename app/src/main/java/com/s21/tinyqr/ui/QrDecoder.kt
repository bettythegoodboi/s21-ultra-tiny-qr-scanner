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

    private fun tryEnhanced(bitmap: Bitmap): String? {
        // 1) Custom DM on original
        CustomDataMatrix.decode(bitmap)?.let { return it }

        // 2) DPM enhance variants (deblur / sharpen / black-hat / adaptive)
        val variants = try {
            DpmEnhance.enhanceToBitmap(bitmap)
        } catch (e: Exception) {
            Log.e("TinyQR", "enhance failed", e)
            emptyList()
        }

        for (v in variants) {
            try {
                CustomDataMatrix.decode(v)?.let {
                    variants.forEach { b -> if (b !== bitmap) try { b.recycle() } catch (_: Exception) {} }
                    return it
                }
                // Also direct ZXing on enhanced
                val px = IntArray(v.width * v.height)
                v.getPixels(px, 0, v.width, 0, 0, v.width, v.height)
                decodeSource(RGBLuminanceSource(v.width, v.height, px))?.let {
                    variants.forEach { b -> if (b !== bitmap) try { b.recycle() } catch (_: Exception) {} }
                    return it
                }
            } catch (_: Exception) {
            }
        }
        variants.forEach { b -> if (b !== bitmap) try { b.recycle() } catch (_: Exception) {} }
        return null
    }

    fun decodeYuv(yData: ByteArray, width: Int, height: Int): String? {
        if (width < 16 || height < 16) return null
        try {
            val pixels = IntArray(width * height)
            val n = minOf(yData.size, pixels.size)
            for (i in 0 until n) {
                val y = yData[i].toInt() and 0xff
                pixels[i] = (0xff shl 24) or (y shl 16) or (y shl 8) or y
            }
            val bmp = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)

            tryEnhanced(bmp)?.let {
                bmp.recycle()
                return it
            }

            // Center crops
            for (ratio in floatArrayOf(0.5f, 0.4f, 0.6f, 0.35f)) {
                val cw = (width * ratio).toInt().coerceAtLeast(32)
                val ch = (height * ratio).toInt().coerceAtLeast(32)
                val left = ((width - cw) / 2).coerceAtLeast(0)
                val top = ((height - ch) / 2).coerceAtLeast(0)
                val rw = minOf(cw, width - left)
                val rh = minOf(ch, height - top)
                val crop = Bitmap.createBitmap(bmp, left, top, rw, rh)
                tryEnhanced(crop)?.let {
                    crop.recycle()
                    bmp.recycle()
                    return it
                }
                crop.recycle()
            }
            bmp.recycle()
        } catch (e: Exception) {
            Log.e("TinyQR", "yuv", e)
        }

        try {
            val full = PlanarYUVLuminanceSource(yData, width, height, 0, 0, width, height, false)
            decodeSource(full)?.let { return it }
        } catch (_: Exception) {
        }
        return null
    }

    fun decode(bitmap: Bitmap): Pair<String, String>? {
        Log.d("TinyQR", "decode ${bitmap.width}x${bitmap.height}")

        tryEnhanced(bitmap)?.let { return it to "dpm" }

        // Upscale small images then enhance
        if (bitmap.width < 700 || bitmap.height < 700) {
            val f = 700f / minOf(bitmap.width, bitmap.height)
            val up = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * f).toInt(),
                (bitmap.height * f).toInt(),
                true
            )
            tryEnhanced(up)?.let {
                if (up !== bitmap) up.recycle()
                return it to "dpm-up"
            }
            if (up !== bitmap) up.recycle()
        }

        // Center crops
        for (ratio in floatArrayOf(0.5f, 0.4f, 0.6f, 0.3f)) {
            val w = bitmap.width
            val h = bitmap.height
            val cw = (w * ratio).toInt().coerceAtLeast(32)
            val ch = (h * ratio).toInt().coerceAtLeast(32)
            val left = ((w - cw) / 2).coerceAtLeast(0)
            val top = ((h - ch) / 2).coerceAtLeast(0)
            val rw = minOf(cw, w - left)
            val rh = minOf(ch, h - top)
            try {
                val crop = Bitmap.createBitmap(bitmap, left, top, rw, rh)
                tryEnhanced(crop)?.let {
                    if (crop !== bitmap) crop.recycle()
                    return it to "dpm-crop"
                }
                val up = Bitmap.createScaledBitmap(crop, rw * 3, rh * 3, true)
                tryEnhanced(up)?.let {
                    up.recycle()
                    if (crop !== bitmap) crop.recycle()
                    return it to "dpm-crop-up"
                }
                up.recycle()
                if (crop !== bitmap) crop.recycle()
            } catch (_: Exception) {
            }
        }

        return null
    }
}
