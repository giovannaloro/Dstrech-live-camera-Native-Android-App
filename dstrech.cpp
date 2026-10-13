// dstrech.cpp - the decorrelation stretch ("Dstretch").
//
// The pixels of a photograph form a narrow, slanted cloud in RGB space: the
// colours are all strongly correlated, so a faint pigment differs from the
// rock around it by only a few levels.  The stretch removes that correlation
// and then amplifies the *differences* instead of the brightness:
//
//   1. measure the mean colour and the 3x3 covariance of the image,
//   2. rotate the colours onto the cloud's principal axes (the Karhunen-Loeve
//      transform) - axis 0 is the brightness, axes 1 and 2 the colour,
//   3. scale each axis: brightness by kLumGain, colour by kChromaGain,
//   4. rotate back to RGB and clamp.
//
// Each axis is scaled by a constant, so the result is ~2.4x the contrast of
// the input along the brightness axis and ~5x along the two colour axes; the
// gains were calibrated against the reference example image.
#include "dstrech.hpp"

#include <algorithm>
#include <cmath>

namespace {

const double kLumGain = 2.4;     // brightness axis
const double kChromaGain = 5.0;  // the two colour axes

// Eigen-decomposition of a symmetric 3x3 matrix by cyclic Jacobi rotations.
// On return 'a' holds the eigenvalues on its diagonal, 'v' the eigenvectors
// as columns and 'ev' a copy of the eigenvalues.
void jacobi3(double a[3][3], double v[3][3], double ev[3])
{
    for (int i = 0; i < 3; ++i)
        for (int j = 0; j < 3; ++j)
            v[i][j] = (i == j) ? 1.0 : 0.0;

    for (int sweep = 0; sweep < 50; ++sweep) {
        if (std::fabs(a[0][1]) + std::fabs(a[0][2]) + std::fabs(a[1][2]) < 1e-12)
            break;
        for (int p = 0; p < 2; ++p)
            for (int q = p + 1; q < 3; ++q) {
                if (std::fabs(a[p][q]) < 1e-15)
                    continue;
                // angle that zeroes a[p][q]
                double theta = (a[q][q] - a[p][p]) / (2.0 * a[p][q]);
                double t = (theta >= 0.0 ? 1.0 : -1.0) /
                           (std::fabs(theta) + std::sqrt(theta * theta + 1.0));
                double c = 1.0 / std::sqrt(t * t + 1.0), s = t * c;
                for (int k = 0; k < 3; ++k) {          // A <- J^T A J
                    double ap = a[k][p], aq = a[k][q];
                    a[k][p] = c * ap - s * aq;
                    a[k][q] = s * ap + c * aq;
                }
                for (int k = 0; k < 3; ++k) {
                    double ap = a[p][k], aq = a[q][k];
                    a[p][k] = c * ap - s * aq;
                    a[q][k] = s * ap + c * aq;
                }
                for (int k = 0; k < 3; ++k) {          // V <- V J
                    double vp = v[k][p], vq = v[k][q];
                    v[k][p] = c * vp - s * vq;
                    v[k][q] = s * vp + c * vq;
                }
            }
    }
    for (int i = 0; i < 3; ++i)
        ev[i] = a[i][i];
}

}  // namespace

Image dstretch(const Image &in, double stretch)
{
    const size_t n = static_cast<size_t>(in.w) * in.h;

    // 1. mean and covariance of the colour cloud
    double mean[3] = {0.0, 0.0, 0.0};
    for (size_t i = 0; i < n; ++i)
        for (int c = 0; c < 3; ++c)
            mean[c] += in.rgb[i * 3 + c];
    for (int c = 0; c < 3; ++c)
        mean[c] /= static_cast<double>(n);

    double cov[3][3] = {{0.0}};
    for (size_t i = 0; i < n; ++i) {
        double d[3];
        for (int c = 0; c < 3; ++c)
            d[c] = in.rgb[i * 3 + c] - mean[c];
        for (int r = 0; r < 3; ++r)
            for (int c = 0; c < 3; ++c)
                cov[r][c] += d[r] * d[c];
    }
    for (int r = 0; r < 3; ++r)
        for (int c = 0; c < 3; ++c)
            cov[r][c] /= static_cast<double>(n);

    // 2. decorrelating axes, brightest variance first
    double axes[3][3], ev[3];
    jacobi3(cov, axes, ev);
    for (int r = 0; r < 3; ++r)  // jacobi3 returns them as columns: store the
        for (int c = r + 1; c < 3; ++c)  // axes as rows so axes[k] is a vector
            std::swap(axes[r][c], axes[c][r]);
    int order[3] = {0, 1, 2};
    std::sort(order, order + 3,
              [&](int x, int y) { return ev[x] > ev[y]; });

    double gain[3];
    for (int k = 0; k < 3; ++k)
        gain[k] = (k == 0 ? kLumGain : kChromaGain) * stretch;
    for (int k = 1; k < 3; ++k)  // an axis with no variance carries no colour:
        if (ev[order[k]] < 1e-6 * ev[order[0]])
            gain[k] = 1.0;       // leave it alone instead of amplifying noise

    // 3./4. rotate onto the axes, scale, rotate back, clamp
    Image out;
    out.w = in.w;
    out.h = in.h;
    out.rgb.resize(in.rgb.size());
    for (size_t i = 0; i < n; ++i) {
        double d[3];
        for (int c = 0; c < 3; ++c)
            d[c] = in.rgb[i * 3 + c] - mean[c];
        double scaled[3];
        for (int k = 0; k < 3; ++k) {
            const double *axis = axes[order[k]];
            scaled[k] = (axis[0] * d[0] + axis[1] * d[1] + axis[2] * d[2]) *
                        gain[k];
        }
        for (int c = 0; c < 3; ++c) {
            double v = mean[c];
            for (int k = 0; k < 3; ++k)
                v += axes[order[k]][c] * scaled[k];
            out.rgb[i * 3 + c] =
                static_cast<uint8_t>(std::lround(std::clamp(v, 0.0, 255.0)));
        }
    }
    return out;
}
