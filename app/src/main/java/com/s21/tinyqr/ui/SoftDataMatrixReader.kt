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
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fundamental soft/blurry Data Matrix reader.
 *
 * Why Cognex works on soft marks:
 * 1. Deblur / edge recovery
 * 2. Soft module sampling (not hard pixels)
 * 3. Search grid size + sub-pixel phase
 * 4. Score L-border + timing pattern
 * 5. Rebuild clean symbol → decode (ECC recovers residual errors)
 *
 * ECC200 square sizes: 10,12,14,...,26,32,...
 */
object SoftDataMatrixReader {

    private const val TAG = "SoftDM"

    // Valid ECC200 square module counts
    private val DM_SIZES = intArrayOf(10, 12, 14, 16, 18, 20, 22, 24, 26, 32, 36, 40)

    fun decode(bitmap: Bitmap): String? {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 24 || h < 24) return null

        val gray = toGrayFloat(bitmap)

        // Candidate images: original + deblurred + sharpened
        val candidates = mutableListOf(
            gray,
            richardsonLucyApprox(gray, w, h, iterations = 6),
            unsharp(gray, w, h, radius = 2, amount = 2.8f),
            unsharp(clahe(gray, w, h), w, h, radius = 1, amount = 2.2f)
        )

        var bestText: String? = null
        var bestScore = -1.0

        // Full frame + center crops
        val regions = mutableListOf(intArrayOf(0, 0, w, h))
        for (ratio in floatArrayOf(0.55f, 0.45f, 0.65f, 0.35f)) {
            val cw = (w * ratio).toInt().coerceAtLeast(32)
            val ch = (h * ratio).toInt().coerceAtLeast(32)
            val left = ((w - cw) / 2).coerceAtLeast(0)
            val top = ((h - ch) / 2).coerceAtLeast(0)
            regions.add(intArrayOf(left, top, min(cw, w - left), min(ch, h - top)))
        }

        for (img in candidates) {
            for (reg in regions) {
                val (x0, y0, rw, rh) = reg
                if (rw < 28 || rh < 28) continue
                val roi = extract(img, w, h, x0, y0, rw, rh)

                for (n in DM_SIZES) {
                    if (n > min(rw, rh) / 2) continue
                    for (phase in floatArrayOf(0.35f, 0.45f, 0.5f, 0.55f, 0.65f)) {
                        val grid = softSample(roi, rw, rh, n, phase)
                        val score = scoreDataMatrixGrid(grid, n)
                        if (score < 1.2) continue // weak L/timing

                        val clean = gridToBitmap(grid, n, modulePx = 10)
                        val text = decodePure(clean)
                        if (text != null && score > bestScore) {
                            bestScore = score
                            bestText = text
                            Log.d(TAG, "hit n=$n phase=$phase score=$score text=$text")
                        }
                        clean.recycle()
                    }
                }
            }
        }

        return bestText
    }

    private fun toGrayFloat(bitmap: Bitmap): FloatArray {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val g = FloatArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            val r = (p shr 16) and 0xff
            val gr = (p shr 8) and 0xff
            val b = p and 0xff
            g[i] = (0.299f * r + 0.587f * gr + 0.114f * b)
        }
        return g
    }

    private fun extract(src: FloatArray, sw: Int, sh: Int, x0: Int, y0: Int, rw: Int, rh: Int): FloatArray {
        val out = FloatArray(rw * rh)
        for (y in 0 until rh) {
            for (x in 0 until rw) {
                out[y * rw + x] = src[(y0 + y) * sw + (x0 + x)]
            }
        }
        return out
    }

    /** Approximate Richardson–Lucy with Gaussian PSF (deblur soft modules) */
    private fun richardsonLucyApprox(
        src: FloatArray, w: Int, h: Int, iterations: Int
    ): FloatArray {
        val psfRadius = 2
        val psf = gaussianKernel(psfRadius, 1.1f)
        val k = psfRadius * 2 + 1
        var est = src.copyOf()
        // prevent zeros
        for (i in est.indices) if (est[i] < 1f) est[i] = 1f

        val conv = FloatArray(w * h)
        val relative = FloatArray(w * h)
        val corr = FloatArray(w * h)

        repeat(iterations) {
            convolve(est, w, h, psf, k, conv)
            for (i in src.indices) {
                val c = if (conv[i] < 1e-3f) 1e-3f else conv[i]
                relative[i] = src[i] / c
            }
            convolve(relative, w, h, psf, k, corr) // PSF is symmetric
            for (i in est.indices) {
                est[i] = (est[i] * corr[i]).coerceIn(0f, 255f)
            }
        }
        return est
    }

    private fun gaussianKernel(radius: Int, sigma: Float): FloatArray {
        val k = radius * 2 + 1
        val ker = FloatArray(k * k)
        var sum = 0f
        for (y in -radius..radius) {
            for (x in -radius..radius) {
                val v = exp(-(x * x + y * y) / (2 * sigma * sigma)).toFloat()
                ker[(y + radius) * k + (x + radius)] = v
                sum += v
            }
        }
        for (i in ker.indices) ker[i] /= sum
        return ker
    }

    private fun convolve(
        src: FloatArray, w: Int, h: Int,
        ker: FloatArray, k: Int, out: FloatArray
    ) {
        val r = k / 2
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                for (ky in 0 until k) {
                    for (kx in 0 until k) {
                        val yy = (y + ky - r).coerceIn(0, h - 1)
                        val xx = (x + kx - r).coerceIn(0, w - 1)
                        sum += src[yy * w + xx] * ker[ky * k + kx]
                    }
                }
                out[y * w + x] = sum
            }
        }
    }

    private fun unsharp(src: FloatArray, w: Int, h: Int, radius: Int, amount: Float): FloatArray {
        val blur = FloatArray(w * h)
        // box blur approx
        val tmp = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0f
                var n = 0
                for (k in -radius..radius) {
                    s += src[y * w + (x + k).coerceIn(0, w - 1)]
                    n++
                }
                tmp[y * w + x] = s / n
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0f
                var n = 0
                for (k in -radius..radius) {
                    s += tmp[(y + k).coerceIn(0, h - 1) * w + x]
                    n++
                }
                blur[y * w + x] = s / n
            }
        }
        val out = FloatArray(w * h)
        for (i in src.indices) {
            out[i] = (src[i] + amount * (src[i] - blur[i])).coerceIn(0f, 255f)
        }
        return out
    }

    private fun clahe(src: FloatArray, w: Int, h: Int): FloatArray {
        // lightweight local stretch
        val out = FloatArray(w * h)
        val tile = 8
        val tw = max(8, w / tile)
        val th = max(8, h / tile)
        var ty = 0
        while (ty < h) {
            var tx = 0
            while (tx < w) {
                val x2 = min(w, tx + tw)
                val y2 = min(h, ty + th)
                var minV = 255f
                var maxV = 0f
                for (y in ty until y2) for (x in tx until x2) {
                    val v = src[y * w + x]
                    if (v < minV) minV = v
                    if (v > maxV) maxV = v
                }
                val range = (maxV - minV).coerceAtLeast(1f)
                for (y in ty until y2) for (x in tx until x2) {
                    out[y * w + x] = ((src[y * w + x] - minV) / range * 255f)
                }
                tx += tw
            }
            ty += th
        }
        return out
    }

    /** Soft sample: average neighborhood at each module center */
    private fun softSample(roi: FloatArray, rw: Int, rh: Int, n: Int, phase: Float): FloatArray {
        val grid = FloatArray(n * n)
        // normalize roi
        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        for (v in roi) {
            if (v < minV) minV = v
            if (v > maxV) maxV = v
        }
        val range = (maxV - minV).coerceAtLeast(1f)

        for (my in 0 until n) {
            for (mx in 0 until n) {
                val cy = ((my + phase) / n * rh).roundToInt().coerceIn(0, rh - 1)
                val cx = ((mx + phase) / n * rw).roundToInt().coerceIn(0, rw - 1)
                var sum = 0f
                var cnt = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val yy = (cy + dy).coerceIn(0, rh - 1)
                        val xx = (cx + dx).coerceIn(0, rw - 1)
                        sum += (roi[yy * rw + xx] - minV) / range
                        cnt++
                    }
                }
                grid[my * n + mx] = sum / cnt
            }
        }
        return grid
    }

    /**
     * Score Data Matrix structure:
     * - L solid borders (two adjacent sides mostly dark)
     * - Timing patterns alternate on the other two sides
     */
    private fun scoreDataMatrixGrid(grid: FloatArray, n: Int): Double {
        fun row(y: Int) = FloatArray(n) { grid[y * n + it] }
        fun col(x: Int) = FloatArray(n) { grid[it * n + x] }
        fun darkRatio(line: FloatArray) = line.count { it < 0.5f }.toDouble() / line.size
        fun altScore(line: FloatArray): Double {
            if (line.size < 2) return 0.0
            var flips = 0
            for (i in 1 until line.size) {
                val a = line[i - 1] < 0.5f
                val b = line[i] < 0.5f
                if (a != b) flips++
            }
            return flips.toDouble() / (line.size - 1)
        }

        val left = darkRatio(col(0))
        val right = darkRatio(col(n - 1))
        val top = darkRatio(row(0))
        val bottom = darkRatio(row(n - 1))

        val solidPairs = listOf(
            left to bottom,
            bottom to right,
            right to top,
            top to left
        )
        val bestSolid = solidPairs.maxOf { (a, b) -> a + b }

        // Timing should be on the non-solid sides — use average alternation of all borders
        val timing = (altScore(row(0)) + altScore(row(n - 1)) +
            altScore(col(0)) + altScore(col(n - 1))) / 4.0

        return bestSolid + timing
    }

    private fun gridToBitmap(grid: FloatArray, n: Int, modulePx: Int): Bitmap {
        val s = n * modulePx
        val px = IntArray(s * s)
        for (my in 0 until n) {
            for (mx in 0 until n) {
                val dark = grid[my * n + mx] < 0.5f
                val color = if (dark) Color.BLACK else Color.WHITE
                for (py in 0 until modulePx) {
                    for (pxi in 0 until modulePx) {
                        px[(my * modulePx + py) * s + (mx * modulePx + pxi)] = color
                    }
                }
            }
        }
        return Bitmap.createBitmap(px, s, s, Bitmap.Config.ARGB_8888)
    }

    private fun decodePure(bitmap: Bitmap): String? {
        return try {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val source = RGBLuminanceSource(w, h, pixels)
            val reader = DataMatrixReader()
            val hints = Hashtable<DecodeHintType, Any>()
            hints[DecodeHintType.POSSIBLE_FORMATS] = EnumSet.of(BarcodeFormat.DATA_MATRIX)
            hints[DecodeHintType.TRY_HARDER] = java.lang.Boolean.TRUE
            hints[DecodeHintType.PURE_BARCODE] = java.lang.Boolean.TRUE
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (_: Exception) {
            null
        }
    }
}
