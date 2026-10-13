package org.dstretch.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK)
        )
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(color = Color(0xFF080808), modifier = Modifier.fillMaxSize()) {
                    App(viewModel())
                }
            }
        }
    }
}

@Composable private fun App(model: SessionModel) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var allowed by remember { mutableStateOf(hasPermission()) }
    var requested by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    LaunchedEffect(Unit) { if (!allowed && !requested) { requested = true; permission.launch(Manifest.permission.CAMERA) } }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) allowed = hasPermission() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    BackHandler(model.page != "camera") {
        if (model.page == "photo") { model.page = "gallery"; model.selected = null } else model.page = "camera"
    }
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        when (model.page) {
            "gallery" -> Gallery(model)
            "photo" -> Column(Modifier.fillMaxSize()) {
                Header("Session photo", null) { model.page = "gallery"; model.selected = null }
                AsyncImage(model.selected, "Processed session photo", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            else -> if (allowed) CameraScreen(model) else Column(
                Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Camera permission is required for live DStretch.")
                Spacer(Modifier.height(16.dp))
                Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
                }) { Text("Open app permissions") }
            }
        }
        model.error?.let { message ->
            Snackbar(Modifier.align(Alignment.TopCenter).padding(12.dp), action = {
                TextButton(onClick = { model.error = null }) { Text("Dismiss") }
            }) { Text(message) }
        }
    }
}

@Composable private fun CameraScreen(model: SessionModel) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember { ProcessedView(context) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var hasFrame by remember { mutableStateOf(false) }
    DisposableEffect(owner, view) {
        val controller = CameraController(context, owner, view, { capture = it }, { model.error = it }, { hasFrame = true })
        onDispose { controller.close() }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        Text("DStretch • LIVE", color = Color.White, fontSize = 14.sp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp)
                .clip(RoundedCornerShape(24.dp)).background(Color.Black.copy(alpha = .65f))
                .padding(horizontal = 16.dp, vertical = 8.dp))
        if (!hasFrame) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(120.dp)
            .background(Color.Black.copy(alpha = .75f))) {
            // Centered against the entire control area, independent of the thumbnail width.
            Box(Modifier.align(Alignment.Center).size(84.dp).border(3.dp, Color.White, CircleShape).padding(7.dp)
                .clip(CircleShape).background(Color.White.copy(alpha = if (capture != null && hasFrame && !model.busy) 1f else .45f))
                .clickable(enabled = capture != null && hasFrame && !model.busy, role = Role.Button) { capture?.let(model::takePhoto) }
                .semantics { contentDescription = "Capture DStretch photo" }, contentAlignment = Alignment.Center) {
                if (model.busy) CircularProgressIndicator(Modifier.size(28.dp), color = Color.Black, strokeWidth = 2.dp)
            }
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 20.dp).size(64.dp)
                .clip(RoundedCornerShape(12.dp)).background(Color(0xFF252525))
                .border(1.dp, Color.White.copy(alpha = .35f), RoundedCornerShape(12.dp))
                .clickable(enabled = model.photos.isNotEmpty(), role = Role.Button) { model.page = "gallery" }
                .semantics { contentDescription = if (model.photos.isEmpty()) "No session photos yet" else "Open session photos, ${model.photos.size} ${if (model.photos.size == 1) "photo" else "photos"}" }) {
                model.photos.lastOrNull()?.let {
                    AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Text("${model.photos.size}", fontSize = 12.sp, color = Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp).clip(CircleShape)
                            .background(Color.Black.copy(alpha = .85f)).padding(horizontal = 6.dp, vertical = 3.dp))
                }
            }
        }
    }
}

@Composable private fun Header(title: String, subtitle: String?, back: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = back, modifier = Modifier.size(56.dp).semantics { contentDescription = "Back" }) {
            Text("←", fontSize = 28.sp, color = Color.White)
        }
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, fontSize = 26.sp, color = Color.White)
            subtitle?.let { Text(it, fontSize = 16.sp, color = Color.LightGray) }
        }
    }
}

@Composable private fun Gallery(model: SessionModel) {
    Column(Modifier.fillMaxSize()) {
        Header("Session photos", "${model.photos.size} ${if (model.photos.size == 1) "photo" else "photos"}") { model.page = "camera" }
        LazyVerticalGrid(columns = GridCells.Fixed(3), contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(model.photos, key = { it.path }) { photo ->
                AsyncImage(photo, "Open processed photo", Modifier.fillMaxWidth().aspectRatio(.85f)
                    .clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { model.selected = photo; model.page = "photo" },
                    contentScale = ContentScale.Crop)
            }
        }
    }
}
