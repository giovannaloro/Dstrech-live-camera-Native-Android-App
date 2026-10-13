package org.dstretch.android

import android.graphics.Bitmap
import java.nio.ByteBuffer

object NativeProcessor {
    init { System.loadLibrary("dstretch") }
    external fun frame(rgba: ByteBuffer, width: Int, height: Int, rowStride: Int,
                       pixelStride: Int, rotation: Int, output: Bitmap)
    external fun still(bitmap: Bitmap)
}
