package org.dstretch.android

internal data class FocusPoint(val x: Float, val y: Float)
internal data class PreviewCrop(val left: Float, val top: Float, val width: Float, val height: Float)

/** Maps the rendered center crop back to unrotated ImageAnalysis surface coordinates. */
internal data class PreviewGeometry(val frameWidth: Int, val frameHeight: Int, val rotation: Int) {
    init {
        require(frameWidth > 0 && frameHeight > 0)
        require(rotation in listOf(0, 90, 180, 270))
    }
    fun crop(viewWidth: Int, viewHeight: Int): PreviewCrop? {
        if (viewWidth <= 0 || viewHeight <= 0) return null
        val rotatedWidth = if (rotation % 180 == 0) frameWidth else frameHeight
        val rotatedHeight = if (rotation % 180 == 0) frameHeight else frameWidth
        val scale = maxOf(viewWidth.toFloat() / rotatedWidth, viewHeight.toFloat() / rotatedHeight)
        val width = rotatedWidth * scale; val height = rotatedHeight * scale
        return PreviewCrop((viewWidth - width) / 2, (viewHeight - height) / 2, width, height)
    }
    fun focusPoint(viewWidth: Int, viewHeight: Int, x: Float, y: Float): FocusPoint? {
        if (!x.isFinite() || !y.isFinite() || x < 0 || y < 0 || x > viewWidth || y > viewHeight) return null
        val crop = crop(viewWidth, viewHeight) ?: return null
        val u = ((x - crop.left) / crop.width).coerceIn(0f, 1f)
        val v = ((y - crop.top) / crop.height).coerceIn(0f, 1f)
        return when (rotation) {
            90 -> FocusPoint(v, 1f - u)
            180 -> FocusPoint(1f - u, 1f - v)
            270 -> FocusPoint(1f - v, u)
            else -> FocusPoint(u, v)
        }
    }
}
