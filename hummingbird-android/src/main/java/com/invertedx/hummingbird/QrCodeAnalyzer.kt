package com.invertedx.hummingbird

import android.graphics.ImageFormat
import android.os.Build
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.FormatException
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader

class QrCodeAnalyzer(
    private val onQrCodesDetected: (qrCode: Result) -> Unit
) : ImageAnalysis.Analyzer {

    private val yuvFormats = mutableListOf(ImageFormat.YUV_420_888)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            yuvFormats.addAll(listOf(ImageFormat.YUV_422_888, ImageFormat.YUV_444_888))
        }
    }

    private val reader = QRCodeMultiReader()

    override fun analyze(image: ImageProxy) {
        // #71: count the entry, then log only on the rare periodic boundary.
        val logNow = QrPerf.noteAttempt()
        if (image.format !in yuvFormats) {
            Log.e(TAG, "Format non pris en charge: ${image.format}")
            closeImage(image)
            if (logNow) QrPerf.logPeriodic()
            return
        }

        val data = image.toNV21ByteArray()

        val source = PlanarYUVLuminanceSource(
            data,
            image.width,
            image.height,
            0,
            0,
            image.width,
            image.height,
            false
        )

        var result: Result? = null

        try {
            result = reader.decode(BinaryBitmap(HybridBinarizer(source)))
        } catch (e: NotFoundException) {
        } catch (e: FormatException) {
        } catch (e: ChecksumException) {
        } catch (t: Throwable) {
        }

        if (result == null) {
            try {
                result = reader.decode(BinaryBitmap(HybridBinarizer(source.invert())))
            } catch (e: NotFoundException) {
            } catch (e: FormatException) {
            } catch (e: ChecksumException) {
            } catch (t: Throwable) {
            }
        }

        if (result != null) {
            QrPerf.noteDecoded()
            Log.i(TAG, "Success QR code")
            onQrCodesDetected(result)
        }

        closeImage(image)
        if (logNow) QrPerf.logPeriodic()
    }

    private fun closeImage(image: ImageProxy) {
        image.close()
    }

    private fun ImageProxy.toNV21ByteArray(): ByteArray {
        val yBuffer = planes[0].buffer // Y plane
        val uBuffer = planes[1].buffer // U plane
        val vBuffer = planes[2].buffer // V plane

        val ySize = yBuffer.remaining()
        val uvSize = uBuffer.remaining()

        val nv21 = ByteArray(ySize + uvSize * 2)

        // copy Y plane
        yBuffer.get(nv21, 0, ySize)

        // Interleave the U and V planes (in the NV21 order, i.e., VU)
        val uvIndex = ySize
        var i = 0
        while (i < uvSize) {
            nv21[uvIndex + 2 * i] = vBuffer[i]
            nv21[uvIndex + 2 * i + 1] = uBuffer[i]
            i++
        }

        return nv21
    }

    companion object {
        private const val TAG = "QrCodeAnalyzer"
    }
}
