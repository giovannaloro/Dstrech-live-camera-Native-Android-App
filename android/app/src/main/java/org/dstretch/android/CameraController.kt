package org.dstretch.android

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraState
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

class CameraController(
    context: Context, private val owner: LifecycleOwner, private val view: ProcessedView,
    private val onReady: (ImageCapture?) -> Unit,
    private val onError: (String) -> Unit,
    private val onFrame: () -> Unit
) {
    private val stopped = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var capture: ImageCapture? = null
    private var cameraInfo: CameraInfo? = null
    private var camera: Camera? = null
    private var focusGeneration = 0
    private var captureRotation: Int? = null
    private var stateObserver: Observer<CameraState>? = null
    private var lastFrame = 0L
    private var lastError = 0L
    private var samples = 0
    private var elapsed = 0L
    private val main = ContextCompat.getMainExecutor(context)
    private val orientation = object : OrientationEventListener(context, SensorManager.SENSOR_DELAY_NORMAL) {
        override fun onOrientationChanged(degrees: Int) {
            if (degrees == ORIENTATION_UNKNOWN) return
            val rotation = when (degrees) {
                in 45..134 -> Surface.ROTATION_270
                in 135..224 -> Surface.ROTATION_180
                in 225..314 -> Surface.ROTATION_90
                else -> Surface.ROTATION_0
            }
            // The portrait viewfinder uses its fixed display rotation. Only
            // still metadata follows physical orientation; controls stay put.
            captureRotation = rotation
            capture?.targetRotation = rotation
        }
    }
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> orientation.enable()
            Lifecycle.Event.ON_STOP -> orientation.disable()
            else -> Unit
        }
    }
    init {
        owner.lifecycle.addObserver(lifecycleObserver)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!stopped.get()) try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val initialRotation = view.display?.rotation ?: Surface.ROTATION_0
                val frameAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(selector(Size(640, 480)))
                    .setTargetRotation(initialRotation)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    // CameraX converts device YUV planes to documented RGBA byte order.
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                val still = ImageCapture.Builder()
                    .setResolutionSelector(selector(Size(1600, 1200)))
                    .setTargetRotation(captureRotation ?: initialRotation)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                analysis = frameAnalysis; capture = still
                frameAnalysis.setAnalyzer(executor) { image ->
                    var output: android.graphics.Bitmap? = null
                    try {
                        val now = SystemClock.elapsedRealtime()
                        if (!stopped.get() && now - lastFrame >= 100) {
                            lastFrame = now
                            check(image.format == PixelFormat.RGBA_8888) { "Unexpected camera format" }
                            val rotation = image.imageInfo.rotationDegrees
                            val w = if (rotation % 180 == 0) image.width else image.height
                            val h = if (rotation % 180 == 0) image.height else image.width
                            output = view.acquire(w, h)
                            output?.let { bitmap ->
                                val start = SystemClock.elapsedRealtimeNanos()
                                val plane = image.planes[0]
                                // Slice respects ByteBuffer position/limit; native capacity bounds the read.
                                NativeProcessor.frame(plane.buffer.slice(), image.width, image.height,
                                    plane.rowStride, plane.pixelStride, rotation, bitmap)
                                elapsed += SystemClock.elapsedRealtimeNanos() - start
                                samples++
                                if (samples == 60) {
                                    Log.d("DStretch", "Native preview mean: ${elapsed / samples / 1_000_000.0} ms, ${image.width}x${image.height}")
                                    samples = 0; elapsed = 0
                                }
                                view.present(bitmap, PreviewGeometry(image.width, image.height, rotation), onFrame)
                                output = null
                            }
                        }
                    } catch (failure: Throwable) {
                        Log.e("DStretch", "Preview processing failed", failure)
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastError > 5000) {
                            lastError = now
                            main.execute { if (!stopped.get()) onError("Live processing failed. Retrying…") }
                        }
                    } finally {
                        output?.let(view::release)
                        image.close()
                    }
                }
                val camera = cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, frameAnalysis, still)
                this.camera = camera
                cameraInfo = camera.cameraInfo
                view.onFocusRequested = ::focus
                val observer = Observer<CameraState> { state ->
                    if (!stopped.get()) {
                        if (state.error != null) {
                            onReady(null)
                            onError("Camera is unavailable. Close other camera apps, then reopen DStretch.")
                        } else {
                            onReady(if (state.type == CameraState.Type.OPEN) still else null)
                        }
                    }
                }
                stateObserver = observer
                camera.cameraInfo.cameraState.observe(owner, observer)
            } catch (failure: Throwable) {
                Log.e("DStretch", "Camera initialization failed", failure)
                onReady(null)
                onError("Camera could not start. Close and reopen the app to retry.")
            }
        }, main)
    }
    private fun focus(point: FocusPoint) {
        val camera = camera ?: return
        val analysis = analysis ?: return
        if (stopped.get()) return
        val generation = ++focusGeneration
        try {
            // The view has already undone bitmap rotation and center cropping.
            // The use case supplies the actual analysis surface aspect ratio.
            val factory = SurfaceOrientedMeteringPointFactory(1f, 1f, analysis)
            val metering = factory.createPoint(point.x, point.y)
            val action = FocusMeteringAction.Builder(metering,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(3, TimeUnit.SECONDS).build()
            if (!camera.cameraInfo.isFocusMeteringSupported(action)) {
                view.finishFocus(false)
                return
            }
            val result = camera.cameraControl.startFocusAndMetering(action)
            result.addListener({
                if (!stopped.get() && generation == focusGeneration) {
                    try {
                        view.finishFocus(result.get().isFocusSuccessful)
                    } catch (failure: Exception) {
                        // Replaced taps and lifecycle stops cancel focus normally.
                        if (failure.cause !is CameraControl.OperationCanceledException) {
                            Log.w("DStretch", "Tap focus failed", failure)
                            view.finishFocus(false)
                        }
                    }
                }
            }, main)
        } catch (failure: Exception) {
            Log.w("DStretch", "Tap focus could not start", failure)
            view.finishFocus(false)
        }
    }
    fun close() {
        stopped.set(true)
        focusGeneration++
        view.onFocusRequested = null
        owner.lifecycle.removeObserver(lifecycleObserver)
        orientation.disable()
        stateObserver?.let { cameraInfo?.cameraState?.removeObserver(it) }
        analysis?.clearAnalyzer()
        val uses = listOfNotNull(analysis, capture).toTypedArray()
        if (uses.isNotEmpty()) provider?.unbind(*uses)
        onReady(null)
        executor.shutdown() // queued analyzer closes proxies; native scratch dies with worker
        view.close()
    }
    private fun selector(size: Size) = ResolutionSelector.Builder()
        .setResolutionStrategy(ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
        .build()
}
