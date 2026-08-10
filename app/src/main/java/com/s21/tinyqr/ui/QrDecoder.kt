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
            Log.d("TinyQR", "Multi global: ${r.text}")
            return r.text
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

    private fun tryBitmapRegion(bitmap: Bitmap): String? {
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

    private fun scaleIfNeeded(src: Bitmap, minSide: Int = 180): Bitmap {
        val min = minOf(src.width, src.height)
        if (min >= minSide) return src
        val f = minSide.toFloat() / min
        return Bitmap.createScaledBitmap(
            src,
            (src.width * f).toInt().coerceAtLeast(1),
            (src.height * f).toInt().coerceAtLeast(1),
            true
        )
    }

    /**
     * Gallery / still image path.
     * Searches multiple regions because the code is often NOT centered.
     */
    fun decode(bitmap: Bitmap): Pair<String, String>? {
        // Shrink huge photos for speed (keep detail)
        var work = bitmap
        val maxSide = 1600
        if (maxOf(bitmap.width, bitmap.height) > maxSide) {
            val s = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
            work = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * s).toInt(),
                (bitmap.height * s).toInt(),
                true
            )
        }

        // 1) Full image
        tryBitmapRegion(work)?.let {
            if (work !== bitmap) work.recycle()
            return it to "full"
        }

        val w = work.width
        val h = work.height

        // 2) Grid of crops — code can be anywhere in the photo
        // 3x3 positions x several sizes
        val sizes = listOf(0.35f, 0.5f, 0.65f, 0.25f)
        val positions = listOf(
            0.0f to 0.0f,   // top-left
            0.5f to 0.0f,   // top-center
            1.0f to 0.0f,   // top-right
            0.0f to 0.5f,   // mid-left
            0.5f to 0.5f,   // center
            1.0f to 0.5f,   // mid-right
            0.0f to 1.0f,   // bot-left
            0.5f to 1.0f,   // bot-center
            1.0f to 1.0f    // bot-right
        )

        for (sizeRatio in sizes) {
            val cw = (w * sizeRatio).toInt().coerceAtLeast(40)
            val ch = (h * sizeRatio).toInt().coerceAtLeast(40)
            for ((px, py) in positions) {
                val left = ((w - cw) * px).toInt().coerceIn(0, maxOf(0, w - cw))
                val top = ((h - ch) * py).toInt().coerceIn(0, maxOf(0, h - ch))
                val rw = minOf(cw, w - left)
                val rh = minOf(ch, h - top)
                if (rw < 32 || rh < 32) continue

                try {
                    val crop = Bitmap.createBitmap(work, left, top, rw, rh)
                    val scaled = scaleIfNeeded(crop, 200)
                    tryBitmapRegion(scaled)?.let {
                        if (scaled !== crop) scaled.recycle()
                        if (crop !== work) crop.recycle()
                        if (work !== bitmap) work.recycle()
                        return it to "region"
                    }
                    if (scaled !== crop) scaled.recycle()
                    if (crop !== work) crop.recycle()
                } catch (_: Exception) {
                }
            }
        }

        // 3) Whole image mild upscale once
        try {
            val up = scaleIfNeeded(work, 400)
            if (up !== work) {
                tryBitmapRegion(up)?.let {
                    up.recycle()
                    if (work !== bitmap) work.recycle()
                    return it to "up"
                }
                up.recycle()
            }
        } catch (_: Exception) {
        }

        if (work !== bitmap) work.recycle()
        return null
    }
}
