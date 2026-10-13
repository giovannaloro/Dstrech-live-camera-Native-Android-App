#include "rgba_adapter.hpp"
#include <iostream>
#include <stdexcept>
#include <limits>

void check(bool result) { if (!result) throw std::runtime_error("Assertion failed"); }
int main(int argc, char** argv) {
    try {
        Image scratch;
        if (argc == 3) {
            Image input = readPPM(argv[1]);
            const size_t stride = input.w * 8 + 13;
            std::vector<uint8_t> rgba(stride * input.h, 97), result(input.w * input.h * 4);
            for (int y = 0; y < input.h; ++y) for (int x = 0; x < input.w; ++x)
                for (int c = 0; c < 3; ++c) rgba[y * stride + x * 8 + c] = input.rgb[(y * input.w + x) * 3 + c];
            processRgba(rgba.data(), rgba.size(), input.w, input.h, stride, 8, 0,
                result.data(), result.size(), input.w * 4, scratch);
            for (size_t i = 0; i < input.rgb.size() / 3; ++i)
                for (int c = 0; c < 3; ++c) input.rgb[i * 3 + c] = result[i * 4 + c];
            writePPM(argv[2], input);
            return 0;
        }
        for (int mode = 0; mode < 4; ++mode) {
            Image input; input.w = 7; input.h = 5; input.rgb.resize(105);
            for (size_t i = 0; i < input.rgb.size(); ++i)
                input.rgb[i] = mode == 0 ? (i * 47 + 23) % 256 : mode == 1 ? 42 : mode == 2 ? (i / 3 * 5) % 256 : (i % 3 == 0 ? 255 : 0);
            Image expected = dstretch(input, 1.0);
            for (size_t pixel : {size_t(4), size_t(8)}) for (int rotation : {0, 90, 180, 270}) {
                size_t row = input.w * pixel + 11;
                std::vector<uint8_t> src(row * input.h, 91);
                for (int y = 0; y < input.h; ++y) for (int x = 0; x < input.w; ++x)
                    for (int c = 0; c < 3; ++c) src[y * row + x * pixel + c] = input.rgb[(y * input.w + x) * 3 + c];
                int ow = rotation % 180 ? input.h : input.w, oh = rotation % 180 ? input.w : input.h;
                size_t outRow = ow * 4 + 9;
                std::vector<uint8_t> out(outRow * oh, 91);
                processRgba(src.data(), src.size(), input.w, input.h, row, pixel, rotation,
                    out.data(), out.size(), outRow, scratch);
                for (int y = 0; y < input.h; ++y) for (int x = 0; x < input.w; ++x) {
                    int ox = x, oy = y;
                    if (rotation == 90) { ox = input.h - 1 - y; oy = x; }
                    if (rotation == 180) { ox = input.w - 1 - x; oy = input.h - 1 - y; }
                    if (rotation == 270) { ox = y; oy = input.w - 1 - x; }
                    for (int c = 0; c < 3; ++c) check(out[oy * outRow + ox * 4 + c] == expected.rgb[(y * input.w + x) * 3 + c]);
                    check(out[oy * outRow + ox * 4 + 3] == 255);
                }
                for (int y = 0; y < oh; ++y) for (size_t i = ow * 4; i < outRow; ++i) check(out[y * outRow + i] == 91);
            }
        }
        std::vector<uint8_t> memory(24, 70);
        processRgba(memory.data(), 24, 3, 2, 12, 4, 0, memory.data(), 24, 12, scratch);
        for (auto v : memory) check(v == 70 || v == 255); // safe in-place bitmap processing
        auto reject = [&](int w, int h, size_t cap, size_t row, size_t pixel, int rotation, size_t outCap, size_t outRow, double strength) {
            bool threw = false;
            try { processRgba(memory.data(), cap, w, h, row, pixel, rotation, memory.data(), outCap, outRow, scratch, strength); }
            catch (const std::invalid_argument&) { threw = true; }
            check(threw);
        };
        reject(0,2,24,12,4,0,24,12,1);
        reject(-1,2,24,12,4,0,24,12,1);
        reject(3,2,23,12,4,0,24,12,1);
        reject(3,2,24,11,4,0,24,12,1);
        reject(3,2,24,12,3,0,24,12,1);
        reject(3,2,24,12,4,45,24,12,1);
        reject(3,2,24,12,4,0,23,12,1);
        reject(3,2,24,12,4,0,24,11,1);
        reject(3,2,24,12,4,0,24,12,std::numeric_limits<double>::quiet_NaN());
        reject(8193,2,24,12,4,0,24,12,1);
        reject(3,2,24,std::numeric_limits<size_t>::max(),4,0,24,12,1);
        std::cout << "PASS: exact RGB parity, padded rows/pixels, all rotations, alpha, in-place processing, invalid buffers\n";
    } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
