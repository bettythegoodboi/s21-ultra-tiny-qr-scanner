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
import com.google.zxing.datamatrix.DataMatrixReader
import java.util.EnumMap
import java.util.EnumSet
import java.util.Hashtable

/**
 * Hardened for industrial Data Matrix (e.g. Cognex result: 00086192327).
 */
object QrDecoder {

    private val dmOnly = EnumSet.of(BarcodeFormat.DATA_MATRIX)
    private val all2d = EnumSet.of(
        BarcodeFormat.DATA_MATRIX,
        BarcodeFormat.QR_CODE,
        BarcodeFormat.AZTEC,
        BarcodeFormat.PDF_417
    )

    private fun hints(formats: EnumSet<BarcodeFormat>): Hashtable<DecodeHintType, Any> {
        val h = Hashtable<DecodeHintType, Any>()
        h[DecodeHintType.POSSIBLE_FORMATS] = formats
        h[DecodeHintType.TRY_HARDER] = true
        h[DecodeHintType.CHARACTER_SET] = "UTF-8"
        return h
    }

    private fun pixelsOf(bitmap: Bitmap): IntArray {
        val p = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(p, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return p
    }

    private fun decodeDataMatrixDirect(bitmap: Bitmap): String? {
        return try {
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixelsOf(bitmap))
            val reader = DataMatrixReader()
            val h = hints(dmOnly)

            // Hybrid
            try {
                val r = reader.decode(BinaryBitmap(HybridBinarizer(source)), h)
                Log.d("TinyQR", "DM direct hybrid: ${r.text}")
                return r.text
            } catch (_: Exception) {}

            // Global
            try {
                val r = reader.decode(BinaryBitmap(GlobalHistogramBinarizer(source)), h)
                Log.d("TinyQR", "DM direct global: ${r.text}")
                return r.text
            } catch (_: Exception) {}

            null
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeMulti(bitmap: Bitmap): String? {
        return try {
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixelsOf(bitmap))
            val reader = MultiFormatReader()

            for (formats in listOf(dmOnly, all2d)) {
                reader.setHints(hints(formats))
                try {
                    val r = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
                    Log.d("TinyQR", "Multi ${r.barcodeFormat}: ${r.text}")
                    return r.text
                } catch (_: Exception) {}
                try {
                    val r = reader.decodeWithState(BinaryBitmap(GlobalHistogramBinarizer(source)))
                    Log.d("TinyQR", "Multi ${r.barcodeFormat}: ${r.text}")
                    return r.text
                } catch (_: Exception) {}
                reader.reset()
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun contrastGray(src: Bitmap, c: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val t = (-0.5f * c + 0.5f) * 255f
        val cm = ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(ColorMatrix().also { it.setSaturation(0f) })
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
        val cw = (w * ratio).toInt().coerceAtLeast(24)
        val ch = (h * ratio).toInt().coerceAtLeast(24)
        val left = ((w - cw) / 2).coerceAtLeast(0)
        val top = ((h - ch) / 2).coerceAtLeast(0)
        return Bitmap.createBitmap(src, left, top, minOf(cw, w - left), minOf(ch, h - top))
    }

    private fun scale(src: Bitmap, factor: Float): Bitmap {
        val w = (src.width * factor).toInt().coerceAtLeast(1)
        val h = (src.height * factor).toInt().coerceAtLeast(1)
        val max = 1600
        val s = if (maxOf(w, h) > max) max.toFloat() / maxOf(w, h) else 1f
        return Bitmap.createScaledBitmap(src, (w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1), true)
    }

    private fun tryImage(bmp: Bitmap): String? {
        decodeDataMatrixDirect(bmp)?.let { return it }
        decodeMulti(bmp)?.let { return it }

        val variants = listOf(
            contrastGray(bmp, 1.4f),
            contrastGray(bmp, 1.8f),
            contrastGray(bmp, 2.4f),
            invert(bmp),
            invert(contrastGray(bmp, 1.6f))
        )
        for (v in variants) {
            decodeDataMatrixDirect(v)?.let {
                variants.forEach { b -> if (b !== bmp) try { b.recycle() } catch (_: Exception) {} }
                return it
            }
            decodeMulti(v)?.let {
                variants.forEach { b -> if (b !== bmp) try { b.recycle() } catch (_: Exception) {} }
                return it
            }
        }
        variants.forEach { b -> if (b !== bmp) try { b.recycle() } catch (_: Exception) {} }
        return null
    }

    fun decode(bitmap: Bitmap): Pair<String, String>? {
        // 1 full
        tryImage(bitmap)?.let { return it to "DM" }

        // 2 center crops + upscale (needed for tiny marks)
        for (ratio in listOf(0.35f, 0.45f, 0.55f, 0.25f, 0.65f)) {
            val crop = try { centerCrop(bitmap, ratio) } catch (_: Exception) { continue }
            tryImage(crop)?.let {
                if (crop !== bitmap) crop.recycle()
                return it to "crop"
            }
            for (f in listOf(3f, 4f, 5f, 6f, 8f)) {
                val up = try { scale(crop, f) } catch (_: Exception) { continue }
                tryImage(up)?.let {
                    up.recycle()
                    if (crop !== bitmap) crop.recycle()
                    return it to "up"
                }
                up.recycle()
            }
            if (crop !== bitmap) crop.recycle()
        }

        // 3 full upscale
        try {
            val up = scale(bitmap, 3f)
            tryImage(up)?.let {
                up.recycle()
                return it to "full-up"
            }
            up.recycle()
        } catch (_: Exception) {}

        return null
    }
}
