// helpers.cpp - binary PPM (P6, 8 bits per sample) reading and writing.
#include "dstrech.hpp"

#include <cctype>
#include <fstream>
#include <stdexcept>

namespace {

// Reads the next blank delimited header token, skipping '#' comments.  The
// delimiting blank is left in the stream (callers consume exactly one).
std::string nextToken(std::ifstream &f)
{
    int c;
    for (;;) {                              // skip blanks and '# comments'
        c = f.peek();
        if (c == '#') {
            while ((c = f.get()) != EOF && c != '\n') { }
        } else if (!std::isspace(c)) {
            break;
        } else if (c != EOF) {
            f.get();
        }
    }
    std::string token;
    while ((c = f.peek()) != EOF && !std::isspace(c))
        token.push_back(static_cast<char>(f.get()));
    return token;
}

}  // namespace

Image readPPM(const std::string &path)
{
    std::ifstream f(path, std::ios::binary);
    if (!f)
        throw std::runtime_error("cannot open '" + path + "'");

    Image img;
    std::string magic = nextToken(f);
    std::string w = nextToken(f), h = nextToken(f), maxval = nextToken(f);
    if (magic != "P6" || w.empty() || h.empty() ||
        (maxval != "255" && maxval != "65535"))
        throw std::runtime_error("'" + path +
                                 "' is not a binary PPM (P6, 8 or 16 bit)");
    img.w = std::stoi(w);
    img.h = std::stoi(h);
    if (img.w <= 0 || img.h <= 0)
        throw std::runtime_error("bad image size in '" + path + "'");

    f.get();  // exactly one whitespace byte separates the header from the data
    img.rgb.resize(static_cast<size_t>(img.w) * img.h * 3);
    if (maxval == "255") {
        f.read(reinterpret_cast<char *>(img.rgb.data()),
               static_cast<std::streamsize>(img.rgb.size()));
    } else {  // 16 bit samples, big endian: keep the high byte
        std::vector<uint8_t> wide(img.rgb.size() * 2);
        f.read(reinterpret_cast<char *>(wide.data()),
               static_cast<std::streamsize>(wide.size()));
        for (size_t i = 0; i < img.rgb.size(); ++i)
            img.rgb[i] = wide[2 * i];
    }
    if (!f)
        throw std::runtime_error("truncated PPM file '" + path + "'");
    return img;
}

void writePPM(const std::string &path, const Image &img)
{
    std::ofstream f(path, std::ios::binary);
    if (!f)
        throw std::runtime_error("cannot write '" + path + "'");
    f << "P6\n" << img.w << ' ' << img.h << "\n255\n";
    f.write(reinterpret_cast<const char *>(img.rgb.data()),
            static_cast<std::streamsize>(img.rgb.size()));
    if (!f)
        throw std::runtime_error("failed while writing '" + path + "'");
}
