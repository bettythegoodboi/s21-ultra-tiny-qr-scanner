package com.s21.tinyqr.ui

import android.graphics.Bitmap
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
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
        return h
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
            val result = decodeBitmapInternal(bmp)
            bmp.recycle()
            return result
        } catch (e: Exception) {
            Log.e("TinyQR", "yuv", e)
            return null
        }
    }

    fun decode(bitmap: Bitmap): Pair<String, String>? {
        Log.d("TinyQR", "decode ${bitmap.width}x${bitmap.height}")
        decodeBitmapInternal(bitmap)?.let { return it to "soft-dm" }

        // Upscale small
        if (minOf(bitmap.width, bitmap.height) < 500) {
            val f = 500f / minOf(bitmap.width, bitmap.height)
            val up = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * f).toInt(),
                (bitmap.height * f).toInt(),
                true
            )
            val r = decodeBitmapInternal(up)
            if (up !== bitmap) up.recycle()
            if (r != null) return r to "soft-up"
        }
        return null
    }

    private fun decodeBitmapInternal(bitmap: Bitmap): String? {
        // 1) Fundamental soft/blurry reader (main path)
        SoftDataMatrixReader.decode(bitmap)?.let { return it }

        // 2) DPM enhance + custom
        try {
            CustomDataMatrix.decode(bitmap)?.let { return it }
            val variants = DpmEnhance.enhanceToBitmap(bitmap)
            for (v in variants) {
                SoftDataMatrixReader.decode(v)?.let {
                    variants.forEach { b -> try { b.recycle() } catch (_: Exception) {} }
                    return it
                }
                CustomDataMatrix.decode(v)?.let {
                    variants.forEach { b -> try { b.recycle() } catch (_: Exception) {} }
                    return it
                }
            }
            variants.forEach { b -> try { b.recycle() } catch (_: Exception) {} }
        } catch (e: Exception) {
            Log.e("TinyQR", "enhance path", e)
        }

        // 3) Direct ZXing last
        try {
            val px = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, px)
            val reader = DataMatrixReader()
            try {
                return reader.decode(BinaryBitmap(HybridBinarizer(source)), hintsDm()).text
            } catch (_: Exception) {
            }
            try {
                return reader.decode(BinaryBitmap(GlobalHistogramBinarizer(source)), hintsDm()).text
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
        return null
    }
}
