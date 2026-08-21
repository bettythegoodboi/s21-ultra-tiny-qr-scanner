package com.s21.tinyqr.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.datamatrix.DataMatrixReader
import java.util.EnumSet
import java.util.Hashtable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Custom Data Matrix pipeline for tiny industrial marks.
 *
 * Steps:
 * 1. Grayscale
 * 2. Local adaptive threshold (handles dark plastic / metal + uneven light)
 * 3. Find solid L-border candidates (Data Matrix finder)
 * 4. Sample module grid
 * 5. Reconstruct clean black/white image
 * 6. Decode ECC200 content
 */
object CustomDataMatrix {

    private const val TAG = "CustomDM"

    fun decode(bitmap: Bitmap): String? {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 20 || h < 20) return null

        val gray = toGray(bitmap)

        // Try several window sizes for adaptive threshold
        for (window in intArrayOf(15, 25, 35, 45, 11, 55)) {
            if (window >= min(w, h)) continue
            val binary = adaptiveThreshold(gray, w, h, window, 8)
            decodeBinary(binary, w, h)?.let { return it }

            // Inverted (light mark on dark)
            val inv = IntArray(binary.size) { if (binary[it] == 0) 255 else 0 }
            decodeBinary(inv, w, h)?.let { return it }
        }

        // Global Otsu fallback
        val otsu = otsuThreshold(gray, w, h)
        decodeBinary(otsu, w, h)?.let { return it }
        val invOtsu = IntArray(otsu.size) { if (otsu[it] == 0) 255 else 0 }
        decodeBinary(invOtsu, w, h)?.let { return it }

        // Multi-scale: shrink then process (noise reduction)
        if (w > 200 && h > 200) {
            val sw = w / 2
            val sh = h / 2
            val small = Bitmap.createScaledBitmap(bitmap, sw, sh, true)
            val result = decode(small)
            if (small !== bitmap) small.recycle()
            if (result != null) return result
        }

        return null
    }

    private fun decodeBinary(binary: IntArray, w: Int, h: Int): String? {
        val step = max(8, min(w, h) / 20)
        val sizes = intArrayOf(
            min(w, h) / 3,
            min(w, h) / 4,
            min(w, h) / 5,
            min(w, h) / 2,
            48, 64, 80, 96, 112, 128
        ).filter { it in 24 until min(w, h) }.distinct()

        for (sz in sizes) {
            var y = 0
            while (y + sz <= h) {
                var x = 0
                while (x + sz <= w) {
                    if (hasLBorder(binary, w, h, x, y, sz)) {
                        sampleAndDecode(binary, w, h, x, y, sz)?.let { return it }
                    }
                    // Also try without L check — brute sample
                    if (sz <= 96) {
                        sampleAndDecode(binary, w, h, x, y, sz)?.let { return it }
                    }
                    x += step
                }
                y += step
            }
        }

        // Full-frame sample at several module estimates
        for (modules in intArrayOf(10, 12, 14, 16, 18, 20, 22, 24, 26, 32)) {
            sampleRegion(binary, w, h, 0, 0, w, h, modules)?.let { return it }
        }
        return null
    }

    private fun hasLBorder(bin: IntArray, w: Int, h: Int, x0: Int, y0: Int, sz: Int): Boolean {
        if (x0 + sz > w || y0 + sz > h) return false
        fun dark(x: Int, y: Int) = bin[y * w + x] < 128

        var left = 0
        var bottom = 0
        var right = 0
        var top = 0
        for (i in 0 until sz) {
            if (dark(x0, y0 + i)) left++
            if (dark(x0 + i, y0 + sz - 1)) bottom++
            if (dark(x0 + sz - 1, y0 + i)) right++
            if (dark(x0 + i, y0)) top++
        }
        val thr = (sz * 0.65).toInt()
        val solidPairs = listOf(
            left >= thr && bottom >= thr,
            bottom >= thr && right >= thr,
            right >= thr && top >= thr,
            top >= thr && left >= thr
        )
        return solidPairs.any { it }
    }

    private fun sampleAndDecode(
        bin: IntArray, w: Int, h: Int,
        x0: Int, y0: Int, sz: Int
    ): String? {
        for (modules in intArrayOf(10, 12, 14, 16, 18, 20, 22, 24, 26, 28, 32)) {
            sampleRegion(bin, w, h, x0, y0, sz, sz, modules)?.let { return it }
        }
        return null
    }

    private fun sampleRegion(
        bin: IntArray, bw: Int, bh: Int,
        x0: Int, y0: Int, rw: Int, rh: Int,
        modules: Int
    ): String? {
        if (rw < modules * 2 || rh < modules * 2) return null

        val cellW = rw.toFloat() / modules
        val cellH = rh.toFloat() / modules
        if (cellW < 1.5f || cellH < 1.5f) return null

        val grid = Array(modules) { BooleanArray(modules) }
        for (my in 0 until modules) {
            for (mx in 0 until modules) {
                val cx = (x0 + (mx + 0.5f) * cellW).roundToInt().coerceIn(0, bw - 1)
                val cy = (y0 + (my + 0.5f) * cellH).roundToInt().coerceIn(0, bh - 1)
                var dark = 0
                var total = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val px = (cx + dx).coerceIn(0, bw - 1)
                        val py = (cy + dy).coerceIn(0, bh - 1)
                        total++
                        if (bin[py * bw + px] < 128) dark++
                    }
                }
                grid[my][mx] = dark * 2 >= total
            }
        }

        // Rebuild clean high-res bitmap from grid
        val scale = 8
        val outW = modules * scale
        val outH = modules * scale
        val pixels = IntArray(outW * outH)
        for (my in 0 until modules) {
            for (mx in 0 until modules) {
                val color = if (grid[my][mx]) Color.BLACK else Color.WHITE
                for (py in 0 until scale) {
                    for (px in 0 until scale) {
                        pixels[(my * scale + py) * outW + (mx * scale + px)] = color
                    }
                }
            }
        }

        return decodeCleanBitmap(pixels, outW, outH)
    }

    private fun decodeCleanBitmap(pixels: IntArray, w: Int, h: Int): String? {
        return try {
            val source = RGBLuminanceSource(w, h, pixels)
            val reader = DataMatrixReader()
            val hints = Hashtable<DecodeHintType, Any>()
            hints[DecodeHintType.POSSIBLE_FORMATS] = EnumSet.of(BarcodeFormat.DATA_MATRIX)
            hints[DecodeHintType.TRY_HARDER] = java.lang.Boolean.TRUE
            hints[DecodeHintType.PURE_BARCODE] = java.lang.Boolean.TRUE
            val result = reader.decode(BinaryBitmap(HybridBinarizer(source)), hints)
            Log.d(TAG, "Decoded: ${result.text}")
            result.text
        } catch (_: Exception) {
            null
        }
    }

    private fun toGray(bitmap: Bitmap): IntArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xff
            val g = (p shr 8) and 0xff
            val b = p and 0xff
            gray[i] = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
        }
        return gray
    }

    private fun adaptiveThreshold(
        gray: IntArray, w: Int, h: Int,
        window: Int, c: Int
    ): IntArray {
        val out = IntArray(w * h)
        val half = window / 2
        val integral = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0L
            for (x in 0 until w) {
                rowSum += gray[y * w + x]
                integral[(y + 1) * (w + 1) + (x + 1)] =
                    integral[y * (w + 1) + (x + 1)] + rowSum
            }
        }
        fun sum(x1: Int, y1: Int, x2: Int, y2: Int): Long {
            val a = integral[y1 * (w + 1) + x1]
            val b = integral[y1 * (w + 1) + x2]
            val cVal = integral[y2 * (w + 1) + x1]
            val d = integral[y2 * (w + 1) + x2]
            return d - b - cVal + a
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val x1 = max(0, x - half)
                val y1 = max(0, y - half)
                val x2 = min(w, x + half + 1)
                val y2 = min(h, y + half + 1)
                val area = (x2 - x1) * (y2 - y1)
                val mean = sum(x1, y1, x2, y2).toDouble() / area
                out[y * w + x] = if (gray[y * w + x] < mean - c) 0 else 255
            }
        }
        return out
    }

    private fun otsuThreshold(gray: IntArray, w: Int, h: Int): IntArray {
        val hist = IntArray(256)
        for (v in gray) hist[v.coerceIn(0, 255)]++
        val total = w * h
        var sum = 0.0
        for (i in 0..255) sum += i * hist[i]
        var sumB = 0.0
        var wB = 0
        var maxVar = 0.0
        var threshold = 128
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t * hist[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val v = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (v > maxVar) {
                maxVar = v
                threshold = t
            }
        }
        val out = IntArray(gray.size)
        for (i in gray.indices) {
            out[i] = if (gray[i] < threshold) 0 else 255
        }
        return out
    }
}
