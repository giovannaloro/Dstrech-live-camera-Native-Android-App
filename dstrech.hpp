// Shared declarations: the image type, PPM I/O (helpers.cpp) and the
// decorrelation stretch itself (dstrech.cpp).
#pragma once

#include <cstdint>
#include <string>
#include <vector>

// An 8-bit RGB image, row major, 3 bytes per pixel.
struct Image {
    int w = 0;
    int h = 0;
    std::vector<uint8_t> rgb;  // w * h * 3

    uint8_t &at(int x, int y, int c) { return rgb[(y * w + x) * 3 + c]; }
};

// helpers.cpp -- binary PPM (P6), 8-bit.  Both throw std::runtime_error.
Image readPPM(const std::string &path);
void writePPM(const std::string &path, const Image &img);

// dstrech.cpp -- applies the decorrelation stretch, returning a new image of
// the same size.  stretch scales the two gains (1.0 = calibrated strength).
Image dstretch(const Image &in, double stretch);
