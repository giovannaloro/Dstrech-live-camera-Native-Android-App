// main.cpp - command line front end:  dstretch in.ppm out.ppm [stretch]
#include "dstrech.hpp"

#include <cstdio>
#include <cstdlib>
#include <exception>

int main(int argc, char **argv)
{
    if (argc < 3 || argc > 4) {
        std::fprintf(stderr, "usage: %s input.ppm output.ppm [stretch]\n",
                     argv[0]);
        return 2;
    }
    try {
        Image in = readPPM(argv[1]);
        const double stretch = (argc == 4) ? std::atof(argv[3]) : 1.0;
        writePPM(argv[2], dstretch(in, stretch));
    } catch (const std::exception &e) {
        std::fprintf(stderr, "dstretch: %s\n", e.what());
        return 1;
    }
    return 0;
}
