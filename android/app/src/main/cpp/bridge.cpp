#include <jni.h>
#include <android/bitmap.h>
#include "rgba_adapter.hpp"
#include <stdexcept>

namespace {
struct LockedBitmap {
    JNIEnv* env; jobject bitmap; AndroidBitmapInfo info{}; void* pixels = nullptr;
    LockedBitmap(JNIEnv* e, jobject b) : env(e), bitmap(b) {
        if (!b || AndroidBitmap_getInfo(e, b, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
            info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
            (info.flags & ANDROID_BITMAP_FLAGS_IS_HARDWARE))
            throw std::invalid_argument("A software RGBA_8888 bitmap is required");
        if (AndroidBitmap_lockPixels(e, b, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
            throw std::runtime_error("Cannot lock bitmap pixels");
    }
    ~LockedBitmap() { if (pixels) AndroidBitmap_unlockPixels(env, bitmap); }
};
template<class F> void controlled(JNIEnv* env, F f) {
    try { f(); }
    catch (const std::exception& e) {
        if (!env->ExceptionCheck()) {
            jclass type = env->FindClass("java/lang/IllegalStateException");
            if (type) env->ThrowNew(type, e.what());
        }
    } catch (...) {
        if (!env->ExceptionCheck()) {
            jclass type = env->FindClass("java/lang/IllegalStateException");
            if (type) env->ThrowNew(type, "Native image processing failed");
        }
    }
}
}
extern "C" JNIEXPORT void JNICALL
Java_org_dstretch_android_NativeProcessor_frame(JNIEnv* env, jobject, jobject buffer,
    jint w, jint h, jint row, jint pixel, jint rotation, jobject bitmap) {
    controlled(env, [&] {
        if (!buffer || !bitmap) throw std::invalid_argument("Buffer and bitmap are required");
        auto* src = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
        jlong capacity = env->GetDirectBufferCapacity(buffer);
        if (!src || capacity < 0 || row <= 0 || pixel <= 0)
            throw std::invalid_argument("Direct RGBA buffer required");
        LockedBitmap out(env, bitmap);
        int ow = rotation % 180 ? h : w, oh = rotation % 180 ? w : h;
        if (ow <= 0 || oh <= 0 || out.info.width != static_cast<unsigned>(ow) ||
            out.info.height != static_cast<unsigned>(oh))
            throw std::invalid_argument("Output bitmap size does not match rotation");
        thread_local Image scratch;
        processRgba(src, capacity, w, h, row, pixel, rotation,
            static_cast<uint8_t*>(out.pixels), static_cast<size_t>(out.info.stride) * out.info.height,
            out.info.stride, scratch);
    });
}
extern "C" JNIEXPORT void JNICALL
Java_org_dstretch_android_NativeProcessor_still(JNIEnv* env, jobject, jobject bitmap) {
    controlled(env, [&] {
        LockedBitmap image(env, bitmap);
        Image scratch; // still buffers released at end of this call
        processRgba(static_cast<uint8_t*>(image.pixels), static_cast<size_t>(image.info.stride) * image.info.height,
            image.info.width, image.info.height, image.info.stride, 4, 0,
            static_cast<uint8_t*>(image.pixels), static_cast<size_t>(image.info.stride) * image.info.height,
            image.info.stride, scratch);
    });
}
