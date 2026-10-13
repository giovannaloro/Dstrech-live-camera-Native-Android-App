#pragma once
#include "dstrech.hpp"
#include <cstddef>

// Platform-independent adapter. Bytes are R,G,B,A (not uint32_t ARGB).
// Output must have rotated dimensions. Input/output may alias for rotation 0.
void processRgba(const uint8_t* src, size_t capacity, int width, int height,
                 size_t rowStride, size_t pixelStride, int rotation,
                 uint8_t* dst, size_t dstCapacity, size_t dstStride,
                 Image& scratch, double stretch = 1.0);
