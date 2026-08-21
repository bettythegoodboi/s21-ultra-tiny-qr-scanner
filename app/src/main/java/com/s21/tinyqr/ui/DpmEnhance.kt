package com.s21.tinyqr.ui

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * Industrial DPM-style enhancement for soft / blurry Data Matrix and QR codes on dark parts / metal.
 *
 * Pipeline:
 *  1. Grayscale extraction
 *  2. CLAHE-like local contrast (handles uneven lighting / metallic reflections)
 *  3. Unsharp mask (deblur approximation)
 *  4. Black-hat morphology (extracts dark dots/modules on light backgrounds)
 *  5. Laplacian edge boost
 *  6. Multi-window adaptive binarization & Otsu
 */
object DpmEnhance {

    data class GrayImage(val data: IntArray, val w: Int, val h: Int)

    fun enhanceToBitmap(src: Bitmap): List<Bitmap> {
        val g0 = toGray(src)
        val results = mutableListOf<Bitmap>()

        // A) CLAHE-like local contrast
        val clahe = claheLike(g0, tile = 8, clip = 3.0)
        results += toBitmap(clahe)

        // B) Strong unsharp on CLAHE (main deblur step)
        val sharp = unsharp(clahe, radius = 2, amount = 2.5f, threshold = 0)
        results += toBitmap(sharp)

        // C) Black-hat (dark modules on gray metal / plastic)
        val bh = blackHat(clahe, k = 5)
        val bhSharp = unsharp(bh, radius = 1, amount = 2.0f, threshold = 0)
        results += toBitmap(bhSharp)

        // D) Laplacian boost on sharp
        val lap = addLaplacian(sharp, strength = 1.2f)
        results += toBitmap(lap)

        // E) Adaptive binary versions
        for (win in intArrayOf(15, 25, 35)) {
            if (win < min(g0.w, g0.h)) {
                results += toBitmap(adaptiveBinary(sharp, win, 5))
                results += toBitmap(adaptiveBinary(lap, win, 5))
                results += toBitmap(invert(adaptiveBinary(sharp, win, 5)))
            }
        }

        // F) Otsu on sharpened & Laplacian
        results += toBitmap(otsu(sharp))
        results += toBitmap(invert(otsu(sharp)))
        results += toBitmap(otsu(lap))

        return results
    }

    fun toGray(bitmap: Bitmap): GrayImage {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val g = IntArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            val r = (p shr 16) and 0xff
            val gr = (p shr 8) and 0xff
            val b = p and 0xff
            g[i] = (0.299 * r + 0.587 * gr + 0.114 * b).toInt().coerceIn(0, 255)
        }
        return GrayImage(g, w, h)
    }

    private fun toBitmap(g: GrayImage): Bitmap {
        val px = IntArray(g.data.size)
        for (i in g.data.indices) {
            val v = g.data[i].coerceIn(0, 255)
            px[i] = Color.rgb(v, v, v)
        }
        return Bitmap.createBitmap(px, g.w, g.h, Bitmap.Config.ARGB_8888)
    }

    /** Simplified CLAHE: per-tile histogram stretch with clip */
    fun claheLike(src: GrayImage, tile: Int, clip: Double): GrayImage {
        val w = src.w
        val h = src.h
        val out = IntArray(w * h)
        val tw = max(8, w / tile)
        val th = max(8, h / tile)
        var ty = 0
        while (ty < h) {
            var tx = 0
            while (tx < w) {
                val x2 = min(w, tx + tw)
                val y2 = min(h, ty + th)
                val hist = IntArray(256)
                var count = 0
                for (y in ty until y2) {
                    for (x in tx until x2) {
                        hist[src.data[y * w + x]]++
                        count++
                    }
                }
                // Clip histogram
                val limit = max(1, (count * clip / 256.0).toInt())
                var excess = 0
                for (i in 0..255) {
                    if (hist[i] > limit) {
                        excess += hist[i] - limit
                        hist[i] = limit
                    }
                }
                val boost = excess / 256
                for (i in 0..255) hist[i] += boost
                // CDF
                val cdf = IntArray(256)
                cdf[0] = hist[0]
                for (i in 1..255) cdf[i] = cdf[i - 1] + hist[i]
                val cdfMin = cdf.firstOrNull { it > 0 } ?: 0
                for (y in ty until y2) {
                    for (x in tx until x2) {
                        val v = src.data[y * w + x]
                        val mapped = if (count > cdfMin) {
                            ((cdf[v] - cdfMin).toDouble() / (count - cdfMin) * 255.0)
                                .toInt().coerceIn(0, 255)
                        } else v
                        out[y * w + x] = mapped
                    }
                }
                tx += tw
            }
            ty += th
        }
        return GrayImage(out, w, h)
    }

    /** Unsharp mask deblur */
    fun unsharp(src: GrayImage, radius: Int, amount: Float, threshold: Int): GrayImage {
        val blur = boxBlur(src, radius)
        val out = IntArray(src.data.size)
        for (i in src.data.indices) {
            val diff = src.data[i] - blur.data[i]
            if (kotlin.math.abs(diff) >= threshold) {
                out[i] = (src.data[i] + amount * diff).toInt().coerceIn(0, 255)
            } else {
                out[i] = src.data[i]
            }
        }
        return GrayImage(out, src.w, src.h)
    }

    private fun boxBlur(src: GrayImage, radius: Int): GrayImage {
        if (radius <= 0) return src
        val w = src.w
        val h = src.h
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)
        val pass = radius
        // Horizontal
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0
                var n = 0
                for (k in -pass..pass) {
                    val xx = (x + k).coerceIn(0, w - 1)
                    sum += src.data[y * w + xx]
                    n++
                }
                tmp[y * w + x] = sum / n
            }
        }
        // Vertical
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0
                var n = 0
                for (k in -pass..pass) {
                    val yy = (y + k).coerceIn(0, h - 1)
                    sum += tmp[yy * w + x]
                    n++
                }
                out[y * w + x] = sum / n
            }
        }
        return GrayImage(out, w, h)
    }

    /** Morphological black-hat: original - opening */
    fun blackHat(src: GrayImage, k: Int): GrayImage {
        val opened = dilate(erode(src, k), k)
        val out = IntArray(src.data.size)
        for (i in src.data.indices) {
            val v = (opened.data[i] - src.data[i] + 128).coerceIn(0, 255)
            out[i] = v
        }
        return GrayImage(out, src.w, src.h)
    }

    private fun erode(src: GrayImage, k: Int): GrayImage {
        val r = k / 2
        val out = IntArray(src.data.size)
        val w = src.w
        val h = src.h
        for (y in 0 until h) {
            for (x in 0 until w) {
                var m = 255
                for (dy in -r..r) {
                    for (dx in -r..r) {
                        val xx = (x + dx).coerceIn(0, w - 1)
                        val yy = (y + dy).coerceIn(0, h - 1)
                        m = min(m, src.data[yy * w + xx])
                    }
                }
                out[y * w + x] = m
            }
        }
        return GrayImage(out, w, h)
    }

    private fun dilate(src: GrayImage, k: Int): GrayImage {
        val r = k / 2
        val out = IntArray(src.data.size)
        val w = src.w
        val h = src.h
        for (y in 0 until h) {
            for (x in 0 until w) {
                var m = 0
                for (dy in -r..r) {
                    for (dx in -r..r) {
                        val xx = (x + dx).coerceIn(0, w - 1)
                        val yy = (y + dy).coerceIn(0, h - 1)
                        m = max(m, src.data[yy * w + xx])
                    }
                }
                out[y * w + x] = m
            }
        }
        return GrayImage(out, w, h)
    }

    fun addLaplacian(src: GrayImage, strength: Float): GrayImage {
        val w = src.w
        val h = src.h
        val out = IntArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val c = src.data[y * w + x]
                val lap = 4 * c -
                    src.data[y * w + x - 1] -
                    src.data[y * w + x + 1] -
                    src.data[(y - 1) * w + x] -
                    src.data[(y + 1) * w + x]
                out[y * w + x] = (c + strength * lap).toInt().coerceIn(0, 255)
            }
        }
        for (x in 0 until w) {
            out[x] = src.data[x]
            out[(h - 1) * w + x] = src.data[(h - 1) * w + x]
        }
        for (y in 0 until h) {
            out[y * w] = src.data[y * w]
            out[y * w + w - 1] = src.data[y * w + w - 1]
        }
        return GrayImage(out, w, h)
    }

    fun adaptiveBinary(src: GrayImage, window: Int, c: Int): GrayImage {
        val w = src.w
        val h = src.h
        val half = window / 2
        val integral = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0L
            for (x in 0 until w) {
                row += src.data[y * w + x]
                integral[(y + 1) * (w + 1) + (x + 1)] = integral[y * (w + 1) + (x + 1)] + row
            }
        }
        fun region(x1: Int, y1: Int, x2: Int, y2: Int): Long {
            return integral[y2 * (w + 1) + x2] - integral[y1 * (w + 1) + x2] -
                integral[y2 * (w + 1) + x1] + integral[y1 * (w + 1) + x1]
        }
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val x1 = max(0, x - half)
                val y1 = max(0, y - half)
                val x2 = min(w, x + half + 1)
                val y2 = min(h, y + half + 1)
                val area = (x2 - x1) * (y2 - y1)
                val mean = region(x1, y1, x2, y2).toDouble() / area
                out[y * w + x] = if (src.data[y * w + x] < mean - c) 0 else 255
            }
        }
        return GrayImage(out, w, h)
    }

    fun otsu(src: GrayImage): GrayImage {
        val hist = IntArray(256)
        for (v in src.data) hist[v.coerceIn(0, 255)]++
        val total = src.data.size
        var sum = 0.0
        for (i in 0..255) sum += i * hist[i]
        var sumB = 0.0
        var wB = 0
        var maxVar = 0.0
        var thr = 128
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
                thr = t
            }
        }
        val out = IntArray(src.data.size) { if (src.data[it] < thr) 0 else 255 }
        return GrayImage(out, src.w, src.h)
    }

    fun invert(src: GrayImage): GrayImage {
        val out = IntArray(src.data.size) { 255 - src.data[it] }
        return GrayImage(out, src.w, src.h)
    }
}
