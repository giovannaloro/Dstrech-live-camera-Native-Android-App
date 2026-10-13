#include "rgba_adapter.hpp"
#include <cmath>
#include <stdexcept>

void processRgba(const uint8_t* src, size_t capacity, int w, int h,
                 size_t row, size_t pixel, int rotation,
                 uint8_t* dst, size_t dstCapacity, size_t dstRow,
                 Image& scratch, double stretch) {
    if (!src || !dst || w <= 0 || h <= 0 || w > 8192 || h > 8192 ||
        static_cast<size_t>(w) * h > 24000000 ||
        pixel < 4 || pixel > 64 || row > 8192 * 64 ||
        row < static_cast<size_t>(w - 1) * pixel + 4 ||
        capacity < static_cast<size_t>(h - 1) * row + (w - 1) * pixel + 4 ||
        (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) ||
        !std::isfinite(stretch) || stretch <= 0)
        throw std::invalid_argument("Invalid RGBA input dimensions, strides, rotation or strength");
    int ow = rotation % 180 ? h : w, oh = rotation % 180 ? w : h;
    if (dstRow < static_cast<size_t>(ow) * 4 || dstRow > 8192 * 64 ||
        dstCapacity < static_cast<size_t>(oh - 1) * dstRow + ow * 4)
        throw std::invalid_argument("Invalid RGBA output dimensions or stride");
    scratch.w = w; scratch.h = h;
    scratch.rgb.resize(static_cast<size_t>(w) * h * 3);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x)
            for (int c = 0; c < 3; ++c)
                scratch.rgb[(static_cast<size_t>(y) * w + x) * 3 + c] = src[y * row + x * pixel + c];
    const Image output = dstretch(scratch, stretch); // actual, unchanged desktop core
    for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x) {
        int ox = x, oy = y;
        if (rotation == 90) { ox = h - 1 - y; oy = x; }
        if (rotation == 180) { ox = w - 1 - x; oy = h - 1 - y; }
        if (rotation == 270) { ox = y; oy = w - 1 - x; }
        auto* p = dst + oy * dstRow + ox * 4;
        const auto* rgb = output.rgb.data() + (static_cast<size_t>(y) * w + x) * 3;
        p[0] = rgb[0]; p[1] = rgb[1]; p[2] = rgb[2]; p[3] = 255;
    }
}
