package org.dstretch.android

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class DStretchApplication : Application() {
    internal val session by lazy { SessionStore(this) }
}

class SessionModel(application: Application) : AndroidViewModel(application) {
    private val store = (application as DStretchApplication).session
    val photos get() = store.photos
    val busy get() = store.busy
    var error: String?
        get() = store.error
        set(value) { store.error = value }
    var page by mutableStateOf("camera")
    var selected by mutableStateOf<File?>(null)
    fun takePhoto(camera: ImageCapture) = store.takePhoto(camera)
}

/** Process-owned session and capture job; survives even an Activity finish/recreation. */
internal class SessionStore(private val application: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // No SavedStateHandle or disk enumeration: a process restart always starts a new session.
    private val root = File(application.filesDir, "sessions")
    private val directory = File(root, UUID.randomUUID().toString())
    var photos by mutableStateOf<List<File>>(emptyList()); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    private val initialization = scope.async(Dispatchers.IO) {
        runCatching {
            check(directory.mkdirs()) { "Cannot create session storage" }
            // Previous sessions are never loaded into the gallery. Retain their
            // files, especially originals from interrupted/failed processing.
        }
    }

    fun takePhoto(camera: ImageCapture) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try {
                initialization.await().getOrThrow()
                check(directory.isDirectory) { "Session storage unavailable" }
                val id = UUID.randomUUID().toString()
                val original = File(directory, "$id-original.jpg")
                camera.takePicture(ImageCapture.OutputFileOptions.Builder(original).build(),
                    ContextCompat.getMainExecutor(application), object : ImageCapture.OnImageSavedCallback {
                        override fun onError(exception: ImageCaptureException) {
                            busy = false
                            error = "Photo capture failed. Please try again."
                            Log.e("DStretch", "Capture failed; original retained if present", exception)
                        }
                        override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                            scope.launch {
                                try {
                                    val processed = withContext(Dispatchers.IO) { processPhoto(original, directory, id) }
                                    photos = photos + processed // publish only after atomic file commit
                                } catch (failure: Throwable) {
                                    Log.e("DStretch", "Still processing failed; original retained", failure)
                                    error = "Photo processing failed. The original remains in private storage."
                                } finally { busy = false }
                            }
                        }
                    })
            } catch (failure: Throwable) {
                busy = false
                error = "Photo capture could not start. Please try again."
                Log.e("DStretch", "Capture could not start", failure)
            }
        }
    }
}

/** Entire selected JPEG resolution, never an analysis frame. EXIF orientation is baked into output. */
internal fun processPhoto(original: File, directory: File, id: String): File {
    val options = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        inMutable = true
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(original.path, bounds)
    require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192 &&
        bounds.outWidth.toLong() * bounds.outHeight <= 6_000_000) { "Capture resolution exceeds processing limits" }
    val bitmap = BitmapFactory.decodeFile(original.path, options) ?: error("Cannot decode captured JPEG")
    var oriented: Bitmap? = null
    val temporary = File(directory, "$id-processed.tmp")
    val destination = File(directory, "$id-processed.jpg")
    try {
        NativeProcessor.still(bitmap)
        val exif = ExifInterface(original)
        val matrix = orientationMatrix(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
        val output = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
        oriented = output
        temporary.outputStream().use { stream ->
            check(output.compress(Bitmap.CompressFormat.JPEG, 95, stream)) { "Cannot encode processed photo" }
        }
        check(temporary.renameTo(destination)) { "Cannot commit processed photo" }
        original.delete() // preserve original through every failure before commit
        return destination
    } finally {
        if (oriented != null && oriented !== bitmap) oriented.recycle()
        bitmap.recycle()
        temporary.delete()
    }
}

internal fun orientationMatrix(orientation: Int) = Matrix().apply {
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
    }
}
