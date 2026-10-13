# DStretch for Android

A native Kotlin/Compose camera app backed by the repository's original C++
`dstrech.cpp`. All UI text is English. No OpenCV or image-processing dependency
was added. The desktop sources and Makefile retain their original behavior.

## Build and run

Open this `android/` directory in Android Studio, or use the included Gradle
wrapper from this directory. Requirements:

- JDK 17 (JDK 21 is also supported by this Gradle version).
- Android SDK platform 35 and build-tools 35.0.0.
- NDK 27.2.12479018 and SDK CMake 3.22.1.
- Android 8.0/API 26 or newer; rear camera; arm64-v8a or x86_64.
- Gradle 8.11.1, AGP 8.9.2, Kotlin/Compose compiler 2.1.20. Dependency versions
  are pinned in the Gradle files; the wrapper distribution has a SHA-256 pin.

```sh
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0" \
  "ndk;27.2.12479018" "cmake;3.22.1"
# Set ANDROID_HOME to the SDK, or put sdk.dir=/absolute/sdk/path in local.properties.
./gradlew :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n org.dstretch.android/.MainActivity
```

Grant camera permission on first launch. The camera screen has a live processed
feed, a centered white shutter, and a session thumbnail/count at bottom right.
The thumbnail opens the three-column gallery; a photo opens a fitted full-screen
viewer. System back and the on-screen back arrow return through these screens.
Tap anywhere in the camera image to focus and meter exposure at that point. A
brief square marks the tap; green indicates focus success and amber indicates
that focus could not lock or is unsupported. CameraX restores automatic metering
after three seconds. The bottom control strip does not request focus.

The interface stays in portrait so the shutter and thumbnail remain in place as
the phone turns. Only ImageCapture follows physical orientation; landscape stills
are saved with the correct orientation. The launcher icon uses the supplied
[Altamira image](../docs/images/altamira.jpg), preserved as an unmodified image
inside the adaptive icon mask.

No storage or network permission is requested. Insets protect the controls from
system bars. Permission-denial actions are shown only while access is denied.

The [AGP compatibility table](https://developer.android.com/build/releases/agp-8-9-0-release-notes)
defines the Gradle/SDK baseline. CameraX's
[RGBA contract](https://developer.android.com/reference/androidx/camera/core/ImageAnalysis#OUTPUT_IMAGE_FORMAT_RGBA_8888())
specifies R, G, B, A bytes in the analyzer's first plane.

## Processing and ownership

- `../dstrech.cpp` / `../dstrech.hpp`: unmodified desktop RGB algorithm. Gains
  are 2.4/5.0 at strength 1.0; statistics are computed in 8-bit encoded RGB,
  without linearization or an alternate color filter.
- `../native/rgba_adapter.*`: platform-independent strided RGBA-to-RGB adapter,
  core invocation, and output rotation. Also compiled by host validation.
- `app/src/main/cpp/bridge.cpp`: two JNI calls, direct-buffer/bitmap validation,
  RAII pixel locks, and conversion of C++ exceptions to application exceptions.
- `CameraController.kt`: rear-camera CameraX ImageAnalysis and ImageCapture,
  lifecycle binding, still orientation updates, CameraX autofocus/exposure
  metering, and executor teardown.
- `PreviewGeometry.kt`: shared center-crop geometry and inverse rotation for
  tap-to-focus coordinates, covered by JVM unit tests.
- `ProcessedView.kt`: center-cropped output drawn from three bounded bitmap
  slots; a slot is never changed by the analyzer while it is displayed.
- `SessionModel.kt`: process-owned session/capture work, private storage, EXIF
  orientation, atomic publication after processing, and Compose state.

Live analysis requests 640x480, with closest-lower-then-higher resolution fallback,
`KEEP_ONLY_LATEST`, a single worker, and at most 10 processed frames per second.
The native target uses `-O2` even in debug builds, matching the desktop Makefile.
CameraX performs the device YUV-plane/stride conversion to RGBA. JNI reads the
plane through a direct sliced ByteBuffer, honoring its actual row/pixel strides.
No Java pixel array or preview JPEG is created. One JNI call processes each
accepted frame. A native RGB scratch vector and output bitmaps are reused;
the original core still allocates its returned RGB image once per frame.
Every ImageProxy is closed in `finally`, including skipped and failed frames.
The analysis target rotation remains aligned with the fixed portrait display.
The full analysis image is rotated by `rotationDegrees` and center-cropped by the
view. No CameraX viewport crop is configured. The rear image is not mirrored.
The still stores the whole selected frame, so it can include edges cropped out
of the screen preview. Its independent exposure, resolution, and statistics can
also make its colors differ slightly from the live frame.

The still use case requests 1600x1200 with the same resolution fallback policy.
Its JPEG is saved first, decoded at its complete selected resolution into a
mutable sRGB bitmap, and processed by the same core at strength 1.0. All eight
EXIF orientation variants are baked into the output pixels. Output is JPEG
quality 95; comparisons of the algorithm must happen before this lossy encoding.
Captures over six megapixels are rejected before decode to bound memory on
unexpected device fallback configurations. Such originals remain available in
private storage. The intended 1600x1200 path needs roughly 27 MB of processing
buffers in the worst rotated case, plus codecs/CameraX/UI overhead.

Only one capture can be pending or processing. The shutter stays disabled until
it succeeds or fails. JPEG decode, native processing, orientation, and encoding
run on an IO dispatcher. A temporary output is renamed within the session
directory before the gallery list and badge change. The original is deleted only
after this commit succeeds. Failed/cancelled capture originals are retained.

A session belongs to the Application process, rather than an Activity or saved
instance state. Rotation, Activity recreation, and temporary backgrounding retain
photos and pending processing. A process restart creates a fresh UUID directory
and empty in-memory list. Previous files are never loaded into the session gallery.
Files are under `files/sessions/<UUID>/`; they remain app-private, are excluded
from backup, and are removed by clearing app data or uninstalling. There is no
public export, previous-session browser, or automatic deletion policy yet.

The CameraX binding stops with its lifecycle; leaving the camera removes its
analyzer, unbinds its use cases, and shuts down its worker. Bitmap slots are
released on teardown or when an in-flight frame returns. Still buffers have
`finally` cleanup and native bitmap locks have destructors. There are no persistent
JNI handles to leak. Native preview scratch storage dies with the analyzer thread.

## Flicker and performance

The original algorithm estimates mean/covariance/eigenvectors for each image.
Even a stationary pixel can change output when the rest of the frame changes.
A host experiment held a patch at RGB (112,110,105) while increasing the rest of
a 64x48 image by one level per frame: the patch output moved from (114,106,85)
to (103,96,76) over ten frames, with a maximum adjacent change of 2/255 per channel.
Reproduce with `python3 native/tests/flicker.py ./dstrech` from the root.
This demonstrates temporal sensitivity; it is not a substitute for live device
footage. Exposure/white-balance changes and near-degenerate principal axes can
increase visible flicker. No smoothing is enabled: smoothing means/covariance
would introduce lag, state, and a difference from the standalone desktop result.

`adb logcat -s DStretch` reports mean native preview time every 60 processed
frames. This excludes CameraX conversion and drawing, so use Android Studio CPU,
memory, and frame profilers as well. On a real arm64 device, exercise a moving
scene and repeated captures for several minutes. Measure UI jank, memory plateau,
thermal behavior, capture latency, and flicker. Reduce analysis resolution or the
10 fps processing limit if needed; do not tune the core's gains to hide slowness.

## Validation

Host checks, from the repository root:

```sh
make
cmake -S native -B build-native -G Ninja \
  -DCMAKE_BUILD_TYPE=RelWithDebInfo -DSANITIZE=ON
cmake --build build-native
ctest --test-dir build-native --output-on-failure
```

The host suite compares the adapter with the desktop CLI using the same P6
inputs/strength, tests padded row and pixel strides, all four right-angle
rotations, grayscale/constant/color inputs, alpha, aliasing, and invalid buffers.
AddressSanitizer/UndefinedBehaviorSanitizer cover native memory access and cleanup.
Host comparisons are byte-exact (zero tolerance). A crop of the supplied GUI
reference also matched across 1,039,344 channel values with zero difference.

Device/emulator JNI and file-pipeline tests:

```sh
cd android
./gradlew :app:connectedDebugAndroidTest
```

`ProcessingTest` includes a desktop-generated RGB golden image, strided direct
buffers, rotations, repeated calls, recovery after native rejection, in-place
still processing, JPEG commit/original preservation, and all EXIF orientations.
It allows one channel level (1/255) in cross-architecture golden comparisons for
floating-point eigen-decomposition and rounding differences. This tolerance does
not apply to JPEG compression differences.

Manual camera acceptance checks:

1. Deny permission, then grant it in app permissions; verify the processed feed.
2. Capture three photos, including while rotating the phone. Check the shutter
   blocks overlapping captures and count increments only after completed processing.
3. Open the gallery and each image; verify three columns, full-size still detail,
   correct orientation, thumbnail selection, and both back-navigation paths.
4. Rotate and background/resume on all screens and during capture. Verify the
   session remains and the camera restarts without duplicate workers or growing memory.
5. Force-stop and relaunch: badge/gallery start empty despite old private files.
6. Tap near/far objects and off-center points in the preview. Check the focus
   indicator, rapid repeated taps, and automatic metering restoration. Rotate the
   phone: controls remain fixed, and captured landscape images have correct orientation.
7. Exercise camera-in-use, low-storage, and invalid-processing failures. Verify
   errors are controlled and no incomplete output enters the session gallery.

Run `./gradlew :app:testDebugUnitTest` from `android/` for center-crop and rotation
mapping checks. Metering uses CameraX's
[SurfaceOrientedMeteringPointFactory](https://developer.android.com/reference/androidx/camera/core/SurfaceOrientedMeteringPointFactory)
with the bound ImageAnalysis use case to preserve the correct surface aspect ratio.

See `VALIDATION.md` for the checks actually run in this workspace. Hardware camera
behavior and real-device performance must be distinguished from host/emulator tests.
