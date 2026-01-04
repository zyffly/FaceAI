package com.felix.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * Helper class used to efficiently convert a [ImageProxy] object from
 * [ImageFormat.YUV_420_888] format to an RGB [Bitmap] object using libyuv via NDK.
 */
class YuvToRgbConverter(context: Context) {
    
    // Load the native library
    init {
        System.loadLibrary("faceai-jni")
    }

    private var yuvBuffer: ByteBuffer? = null
    private var argbBuffer: ByteBuffer? = null
    private var pixelCount: Int = -1

    /**
     * Converts Image to Bitmap using native libyuv.
     * Note: This method expects the input Bitmap to be mutable and ARGB_8888.
     */
    @Synchronized
    fun yuvToRgb(image: Image, output: Bitmap) {
        val width = image.width
        val height = image.height
        
        // Prepare intermediate buffers if necessary
        // For direct YUV to ARGB conversion we might pass pointers directly if possible,
        // but Image planes are separate. libyuv handles separate planes.
        
        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        
        // Ensure buffers are direct for JNI access
        if (!yBuffer.isDirect || !uBuffer.isDirect || !vBuffer.isDirect) {
            // Fallback or error: CameraX usually provides direct buffers
            return 
        }

        // Lock the bitmap pixels
        // In Kotlin/JNI we can pass the Bitmap object and lock pixels in C++,
        // or we can copy to a buffer and then to Bitmap.
        // For efficiency, locking pixels in JNI is best.
        
        nativeConvertAndroid420ToBitmap(
            yBuffer, yPlane.rowStride,
            uBuffer, uPlane.rowStride, uPlane.pixelStride,
            vBuffer, vPlane.rowStride, vPlane.pixelStride,
            width, height,
            output
        )
    }

    // Convert ImageProxy to Bitmap
    @OptIn(ExperimentalGetImage::class)
    fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val image = imageProxy.image ?: return null
        
        // Ensure the bitmap is created with the correct dimensions
        val bitmap = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
        
        yuvToRgb(image, bitmap)
        return bitmap
    }

    // Native method declaration
    private external fun nativeConvertAndroid420ToBitmap(
        yBuffer: ByteBuffer, yStride: Int,
        uBuffer: ByteBuffer, uStride: Int, uPixelStride: Int,
        vBuffer: ByteBuffer, vStride: Int, vPixelStride: Int,
        width: Int, height: Int,
        outputBitmap: Bitmap
    )
}
