package com.s21.tinyqr.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
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
import com.google.zxing.qrcode.QRCodeReader
import java.io.ByteArrayOutputStream
import java.util.EnumMap
import java.util.EnumSet

data class DecodeResult(
    val text: String,
    val formatName: String,
    val details: String = ""
)

/**
 * High-performance, multi-engine decoder optimized for tiny (<5mm) and low-contrast
 * Data Matrix and QR codes on Samsung Galaxy S21 Ultra.
 */
object QrDecoder {

    private const val TAG = "TinyQrDecoder"

    // Multi-format reader with maximum effort hints
    private val multiReader = MultiFormatReader()
    private val multiHints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
        put(
            DecodeHintType.POSSIBLE_FORMATS,
            listOf(
                BarcodeFormat.DATA_MATRIX,
                BarcodeFormat.QR_CODE,
                BarcodeFormat.AZTEC,
                BarcodeFormat.PDF_417,
                BarcodeFormat.CODE_128
            )
        )
        put(DecodeHintType.TRY_HARDER, java.lang.Boolean.TRUE)
    }

    private val dmReader = DataMatrixReader()
    private val qrReader = QRCodeReader()
    private val dedicatedHints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
        put(DecodeHintType.TRY_HARDER, java.lang.Boolean.TRUE)
    }

    /**
     * Decodes a LuminanceSource using 5 binarization passes:
     * 1. HybridBinarizer (Normal)
     * 2. HybridBinarizer on Inverted (White-on-dark / Laser DPM)
     * 3. Contrast stretch + HybridBinarizer
     * 4. GlobalHistogramBinarizer
     * 5. GlobalHistogramBinarizer on Inverted
     */
    private fun decodeSource(source: LuminanceSource): DecodeResult? {
        // Pass 1: Standard Hybrid (Data Matrix dedicated + MultiFormat)
        try {
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            try {
                val r = dmReader.decode(bitmap, dedicatedHints)
                dmReader.reset()
                return DecodeResult(r.text, "Data Matrix", "ZXing Hybrid")
            } catch (_: Exception) {
                dmReader.reset()
            }
            try {
                val r = qrReader.decode(bitmap, dedicatedHints)
                qrReader.reset()
                return DecodeResult(r.text, "QR Code", "ZXing Hybrid")
            } catch (_: Exception) {
                qrReader.reset()
            }
            try {
                val r = multiReader.decodeWithState(bitmap)
                multiReader.reset()
                return DecodeResult(r.text, r.barcodeFormat.name, "ZXing Hybrid")
            } catch (_: Exception) {
                multiReader.reset()
            }
        } catch (_: Exception) {}

        // Pass 2: Inverted (Laser etched / white mark on dark background)
        try {
            val invSource = source.invert()
            val invBitmap = BinaryBitmap(HybridBinarizer(invSource))
            try {
                val r = dmReader.decode(invBitmap, dedicatedHints)
                dmReader.reset()
                return DecodeResult(r.text, "Data Matrix", "ZXing Inverted")
            } catch (_: Exception) {
                dmReader.reset()
            }
            try {
                val r = qrReader.decode(invBitmap, dedicatedHints)
                qrReader.reset()
                return DecodeResult(r.text, "QR Code", "ZXing Inverted")
            } catch (_: Exception) {
                qrReader.reset()
            }
            try {
                val r = multiReader.decodeWithState(invBitmap)
                multiReader.reset()
                return DecodeResult(r.text, r.barcodeFormat.name, "ZXing Inverted")
            } catch (_: Exception) {
                multiReader.reset()
            }
        } catch (_: Exception) {}

        // Pass 3: Contrast Stretched
        try {
            val stretched = contrastStretch(source)
            val strBitmap = BinaryBitmap(HybridBinarizer(stretched))
            try {
                val r = dmReader.decode(strBitmap, dedicatedHints)
                dmReader.reset()
                return DecodeResult(r.text, "Data Matrix", "ZXing Stretched")
            } catch (_: Exception) {
                dmReader.reset()
            }
            try {
                val r = qrReader.decode(strBitmap, dedicatedHints)
                qrReader.reset()
                return DecodeResult(r.text, "QR Code", "ZXing Stretched")
            } catch (_: Exception) {
                qrReader.reset()
            }
        } catch (_: Exception) {}

        // Pass 4: Global Histogram
        try {
            val globBitmap = BinaryBitmap(GlobalHistogramBinarizer(source))
            try {
                val r = dmReader.decode(globBitmap, dedicatedHints)
                dmReader.reset()
                return DecodeResult(r.text, "Data Matrix", "ZXing Global")
            } catch (_: Exception) {
                dmReader.reset()
            }
            try {
                val r = qrReader.decode(globBitmap, dedicatedHints)
                qrReader.reset()
                return DecodeResult(r.text, "QR Code", "ZXing Global")
            } catch (_: Exception) {
                qrReader.reset()
            }
        } catch (_: Exception) {}

        // Pass 5: Inverted Global Histogram
        try {
            val invGlobBitmap = BinaryBitmap(GlobalHistogramBinarizer(source.invert()))
            try {
                val r = dmReader.decode(invGlobBitmap, dedicatedHints)
                dmReader.reset()
                return DecodeResult(r.text, "Data Matrix", "ZXing Global Inverted")
            } catch (_: Exception) {
                dmReader.reset()
            }
            try {
                val r = qrReader.decode(invGlobBitmap, dedicatedHints)
                qrReader.reset()
                return DecodeResult(r.text, "QR Code", "ZXing Global Inverted")
            } catch (_: Exception) {
                qrReader.reset()
            }
        } catch (_: Exception) {}

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

    /**
     * Real-time live camera analysis directly on raw 8-bit Y plane.
     */
    fun decodeYuv(
        yData: ByteArray,
        width: Int,
        height: Int,
        cropPercent: Float = 0.50f
    ): DecodeResult? {
        if (width < 16 || height < 16) return null
        try {
            // Priority: Center reticle crop where user aims the tiny code
            val cropW = (width * cropPercent).toInt().coerceAtLeast(16)
            val cropH = (height * cropPercent).toInt().coerceAtLeast(16)
            val left = (width - cropW) / 2
            val top = (height - cropH) / 2

            val centerSource = PlanarYUVLuminanceSource(
                yData, width, height, left, top, cropW, cropH, false
            )
            decodeSource(centerSource)?.let { return it }

            // Secondary: 75% crop
            val cropW2 = (width * 0.75f).toInt().coerceAtLeast(16)
            val cropH2 = (height * 0.75f).toInt().coerceAtLeast(16)
            val left2 = (width - cropW2) / 2
            val top2 = (height - cropH2) / 2
            val midSource = PlanarYUVLuminanceSource(
                yData, width, height, left2, top2, cropW2, cropH2, false
            )
            decodeSource(midSource)?.let { return it }

            // Full frame fallback
            val fullSource = PlanarYUVLuminanceSource(
                yData, width, height, 0, 0, width, height, false
            )
            return decodeSource(fullSource)
        } catch (e: Exception) {
            Log.e(TAG, "decodeYuv error", e)
            return null
        }
    }

    /**
     * Decodes a still Bitmap (from camera capture or local gallery selection).
     * Executes a comprehensive multi-stage search with multi-scale crops,
     * quadrant partitioning, DPM morphological enhancement, and soft grid sampling.
     */
    fun decode(bitmap: Bitmap): DecodeResult? {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 16 || h < 16) return null

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val fullSource = RGBLuminanceSource(w, h, pixels)

        // 1. Direct full frame decode
        decodeSource(fullSource)?.let { return it }

        // 2. Multi-ratio center crops (for high-res phone captures where code is small in center)
        for (ratio in floatArrayOf(0.25f, 0.40f, 0.55f, 0.70f, 0.85f)) {
            val cw = (w * ratio).toInt().coerceAtLeast(16)
            val ch = (h * ratio).toInt().coerceAtLeast(16)
            val left = (w - cw) / 2
            val top = (h - ch) / 2
            try {
                decodeSource(fullSource.crop(left, top, cw, ch))?.let { return it }
            } catch (_: Exception) {}
        }

        // 3. Quadrant tiles for high-res images
        if (w > 300 && h > 300) {
            val hw = (w * 0.55f).toInt()
            val hh = (h * 0.55f).toInt()
            val offsets = listOf(
                0 to 0,
                (w - hw) to 0,
                0 to (h - hh),
                (w - hw) to (h - hh),
                (w - hw) / 2 to (h - hh) / 2
            )
            for ((x, y) in offsets) {
                try {
                    decodeSource(fullSource.crop(x, y, hw, hh))?.let { return it }
                } catch (_: Exception) {}
            }
        }

        // 4. Custom DPM Enhanced Pipeline (CLAHE, unsharp mask, black-hat, adaptive binary)
        try {
            val enhancedList = DpmEnhance.enhanceToBitmap(bitmap)
            for (enh in enhancedList) {
                val ew = enh.width
                val eh = enh.height
                val epixels = IntArray(ew * eh)
                enh.getPixels(epixels, 0, ew, 0, 0, ew, eh)
                val enhSource = RGBLuminanceSource(ew, eh, epixels)
                val res = decodeSource(enhSource)
                enh.recycle()
                if (res != null) return res.copy(details = "DPM Enhanced (${res.details})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "DpmEnhance pipeline error", e)
        }

        // 5. Custom Data Matrix Heuristic Finder
        try {
            CustomDataMatrix.decode(bitmap)?.let {
                return DecodeResult(it, "Data Matrix", "Custom Grid Reconstruction")
            }
        } catch (_: Exception) {}

        // 6. Soft Data Matrix Sub-pixel Reader (for blurry/soft marks)
        try {
            SoftDataMatrixReader.decode(bitmap)?.let {
                return DecodeResult(it, "Data Matrix", "Soft Sub-pixel Deconvolution")
            }
        } catch (_: Exception) {}

        return null
    }
}
