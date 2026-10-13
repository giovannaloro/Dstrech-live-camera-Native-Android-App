# Validation record

Builds and host tests completed on 2026-10-12; final APK integrity verified on 2026-10-13.

| Check | Result |
| --- | --- |
| Original desktop `make` | Passed with GCC 15.2, C++17; desktop processing sources and Makefile unchanged |
| Host native CMake build | Passed, RelWithDebInfo + AddressSanitizer + UndefinedBehaviorSanitizer |
| CTest | 2/2 passed: adapter buffer/rotation/error tests and desktop CLI parity |
| Synthetic P6 parity | 1x1, 17x11, 640x480 inputs, same strength 1.0; byte-exact output |
| Supplied GUI reference crop parity | 472x734 RGB crop, 1,039,344 channel values; maximum difference 0 |
| Temporal diagnostic | Fixed input patch changed by at most 2/255 per channel between adjacent frames in the documented background-ramp experiment |
| Gradle wrapper and distribution | Verified against official SHA-256 checksums |
| CameraX 1.5.3 artifact metadata | minCompileSdk 35, minimum AGP 8.6.0; project uses SDK 35 / AGP 8.9.2 |
| Android NDK JNI/core cross-build | Passed for arm64-v8a and x86_64, API 26; verified 16 KiB ELF LOAD alignment |
| Android APK, lint, test APK | Passed: assembleDebug, lintDebug, assembleDebugAndroidTest; lint has 0 errors and 14 non-blocking warnings (version-update and resource/backup suggestions) |
| Final APK integrity | Signature verified; ZIP and all bundled native ELF libraries support 16 KiB alignment |
| Android instrumentation execution | Not run: no Android device or emulator connected |
| Real-device camera/performance | Not verified: no physical Android device connected |

The host reference comparison used the camera portion of the supplied GUI image
(crop coordinates 225,56–697,790) as an input, not as an expected DStretch result.
Pillow converted it to P6, then `./dstrech input.ppm desktop.ppm 1.0` and
`build-native/adapter_test input.ppm adapter.ppm` processed identical bytes.
The comparison checks reuse of the existing algorithm; it does not assess the
photographic quality of applying DStretch a second time to a GUI reference.

The Android SDK/NDK/Gradle were initially absent. An isolated toolchain was installed under `~/.cache/dstretch-build-tools`; system Java was not replaced.
The local SDK path is in ignored `android/local.properties`. The project itself
uses the standard Gradle wrapper and pinned SDK/NDK versions, so the temporary
build-tool setup is not a project dependency.

`ProcessingTest` covers the actual JNI/Android Bitmap boundary, all EXIF
orientations, JPEG commit, failed-original preservation, and repeated native
calls. `CameraFlowTest` covers a real CameraX capture, publication in the session
gallery, photo viewer/back navigation, and Activity recreation. These tests need
a device or emulator with a working rear camera. Compiling these tests does not
constitute executing them.

Outstanding hardware acceptance checks are listed in README.md: permission and
camera failures, all physical orientations, background/resume during capture,
process-restart session isolation, memory/resource plateau, sustained frame rate,
thermal behavior, and temporal flicker with actual camera auto-exposure.

## Build artifacts and local commands

- App: `app/build/outputs/apk/debug/app-debug.apk`
- Tests: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
- Lint: `app/build/reports/lint-results-debug.html`

App APK SHA-256: `8a354601d17309c63464261ffde46d57f43a0f5ff69b55bdf74eb1848d6c5790`

From the repository root, use the isolated toolchain already prepared here:

```sh
export JAVA_HOME="$HOME/.cache/dstretch-build-tools/jdk21"
export ANDROID_HOME="$HOME/.cache/dstretch-build-tools/sdk"
./android/gradlew -p android :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest
"$ANDROID_HOME/platform-tools/adb" install -r android/app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/platform-tools/adb" shell am start -n org.dstretch.android/.MainActivity
```

These commands require a connected device for installation/run. Android Studio can
also open `android/` and use its own JDK 17/21 and the documented SDK/NDK versions.

## Version 1.1 improvements — 2026-10-13

- Altamira photo launcher icon; verified the packaged JPEG equals the supplied
  image byte for byte.
- Tap-to-focus/exposure with inverse preview crop/rotation mapping, a transient
  indicator, superseded-request handling, and automatic cancellation after 3s.
- Portrait UI keeps controls fixed; sensor orientation still updates ImageCapture
  metadata, including when the orientation event arrives before camera binding.
- `assembleDebug`, `lintDebug`, `testDebugUnitTest`, and
  `assembleDebugAndroidTest` passed. Four JVM geometry tests passed.
- Final APK signature and 16 KiB ZIP alignment verified.
- 0 errors, 18 warnings (includes the intentional portrait-orientation preference).
- No adb device connected: physical autofocus behavior, launcher appearance,
  and turning the phone while capturing require device verification.
