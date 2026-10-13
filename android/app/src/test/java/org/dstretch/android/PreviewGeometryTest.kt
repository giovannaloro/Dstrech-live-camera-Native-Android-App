package org.dstretch.android

import org.junit.Assert.*
import org.junit.Test

class PreviewGeometryTest {
    private fun checkPoint(geometry: PreviewGeometry, vw: Int, vh: Int, x: Float, y: Float, expectedX: Float, expectedY: Float) {
        val point = geometry.focusPoint(vw, vh, x, y)!!
        assertEquals(expectedX, point.x, .00001f)
        assertEquals(expectedY, point.y, .00001f)
    }
    @Test fun portraitCropMapsVisibleEdgesToActualSensorRegion() {
        // 480x640 upright frame fills 1080x1920; 180px are cropped from each side.
        val geometry = PreviewGeometry(640, 480, 90)
        checkPoint(geometry, 1080, 1920, 540f, 960f, .5f, .5f)
        checkPoint(geometry, 1080, 1920, 0f, 960f, .5f, .875f)
        checkPoint(geometry, 1080, 1920, 1080f, 960f, .5f, .125f)
        checkPoint(geometry, 1080, 1920, 540f, 0f, 0f, .5f)
        checkPoint(geometry, 1080, 1920, 540f, 1920f, 1f, .5f)
    }
    @Test fun allRotationsMapUpperLeftBackToTheCorrectSourceCorner() {
        checkPoint(PreviewGeometry(640, 480, 0), 640, 480, 0f, 0f, 0f, 0f)
        checkPoint(PreviewGeometry(640, 480, 90), 480, 640, 0f, 0f, 0f, 1f)
        checkPoint(PreviewGeometry(640, 480, 180), 640, 480, 0f, 0f, 1f, 1f)
        checkPoint(PreviewGeometry(640, 480, 270), 480, 640, 0f, 0f, 1f, 0f)
    }
    @Test fun horizontalCropMapsOffCenterTap() {
        // 640x480 fits a 1280x720 area; 120px are cropped from top and bottom.
        val geometry = PreviewGeometry(640, 480, 0)
        checkPoint(geometry, 1280, 720, 320f, 0f, .25f, .125f)
        checkPoint(geometry, 1280, 720, 960f, 720f, .75f, .875f)
    }
    @Test fun invalidTouchesOrUnmeasuredViewsAreRejected() {
        val geometry = PreviewGeometry(640, 480, 90)
        assertNull(geometry.focusPoint(0, 0, 0f, 0f))
        assertNull(geometry.focusPoint(1080, 1920, -1f, 20f))
        assertNull(geometry.focusPoint(1080, 1920, 1081f, 20f))
        assertNull(geometry.focusPoint(1080, 1920, Float.NaN, 20f))
        assertNull(geometry.focusPoint(1080, 1920, 20f, Float.POSITIVE_INFINITY))
    }
}
