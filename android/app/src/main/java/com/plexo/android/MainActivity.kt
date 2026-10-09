package com.plexo.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var service by mutableStateOf<DownloadService.LocalBinder?>(null)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder as? DownloadService.LocalBinder
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bindService(Intent(this, DownloadService::class.java), connection, Context.BIND_AUTO_CREATE)
        setContent {
            val bound = service
            val download by bound?.engine?.state?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(DownloadUiState()) }
            PlexoScreen(
                state = download,
                engine = bound?.engine,
                onResume = { bound?.resume() },
                onStart = { probe, networks, streams ->
                    startForegroundService(Intent(this, DownloadService::class.java))
                    service?.start(probe, networks, streams)
                }
            )
        }
    }

    override fun onDestroy() {
        unbindService(connection)
        super.onDestroy()
    }
}

@Composable
private fun PlexoScreen(
    state: DownloadUiState,
    engine: DownloadEngine?,
    onResume: () -> Unit,
    onStart: (ProbeInfo, List<AndroidNetwork>, Int) -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val networksRepository = remember { AndroidNetworks(context) }
    var networks by remember { mutableStateOf(emptyList<AndroidNetwork>()) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var url by remember { mutableStateOf("") }
    var probe by remember { mutableStateOf<ProbeInfo?>(null) }
    var probing by remember { mutableStateOf(false) }
    var probeError by remember { mutableStateOf<String?>(null) }
    var streams by remember { mutableStateOf(0) }
    val settings = remember { context.getSharedPreferences("plexo", Context.MODE_PRIVATE) }
    var dark by remember { mutableStateOf(settings.getBoolean("darkTheme", false)) }
    var initializedNetworks by remember { mutableStateOf(false) }
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    var pendingStart by remember { mutableStateOf<(() -> Unit)?>(null) }
    val legacyStoragePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) pendingStart?.invoke()
        else permissionMessage = "Allow storage access to save downloads on Android 8 or 9."
        pendingStart = null
    }

    LaunchedEffect(Unit) {
        while (true) {
            val current = networksRepository.list()
            val previousIds = networks.map { it.id }.toSet()
            val currentIds = current.map { it.id }.toSet()
            selected = selected.intersect(currentIds) + current
                .filter { !initializedNetworks || it.id !in previousIds }
                .map { it.id }
            networks = current
            initializedNetworks = true
            delay(2000)
        }
    }
    LaunchedEffect(url) {
        probe = null
        probeError = null
        if (url.startsWith("http://") || url.startsWith("https://")) {
            delay(500)
            probing = true
            try {
                probe = engine?.probe(url, null)
            } catch (error: Exception) {
                probeError = error.message ?: "Could not check this URL."
            } finally {
                probing = false
            }
        }
    }

    MaterialTheme(colorScheme = if (dark) darkColorScheme(
        primary = Color(0xFFD8A44C), onPrimary = Color(0xFF1B1408),
        secondary = Color(0xFF4EA89A), surface = Color(0xFF1B1E20),
        background = Color(0xFF1B1E20), onSurface = Color(0xFFEAE7E2),
        surfaceVariant = Color(0xFF202325), outline = Color(0xFF33383C)
    ) else lightColorScheme(
        primary = Color(0xFFD97706), onPrimary = Color.White,
        secondary = Color(0xFF1F8A70), surface = Color.White,
        background = Color(0xFFF4F5F7), onSurface = Color(0xFF1D1D1F),
        surfaceVariant = Color(0xFFFAFAFA), outline = Color(0xFFE0E0E2)
    )) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("PLEXO", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                        Text("Downloads, together.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                    Text("Dark")
                    Switch(checked = dark, onCheckedChange = {
                        dark = it
                        settings.edit().putBoolean("darkTheme", it).apply()
                    })
                }

                if (state.status == "downloading" || state.status == "paused" || state.status == "error") {
                    DownloadPanel(state, engine, onResume)
                } else if (state.status == "completed") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Download complete", style = MaterialTheme.typography.titleLarge)
                            Text("${state.fileName} is in Downloads/Plexo.")
                            Button(onClick = { engine?.cancel() }) { Text("New download") }
                        }
                    }
                } else {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("NEW DOWNLOAD", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                            OutlinedTextField(
                                value = url,
                                onValueChange = { url = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("File URL") },
                                placeholder = { Text("https://example.com/file.zip") },
                                singleLine = true
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    val pasted = clipboard.getText()?.text.orEmpty()
                                    if (pasted.isNotBlank()) url = pasted.trim()
                                }) { Text("Paste link") }
                                if (probing) CircularProgressIndicator(modifier = Modifier.height(36.dp))
                                else Text(probe?.let { "${it.fileName} · ${formatBytes(it.totalBytes)}" } ?: "")
                            }
                            probeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            Text("SAVE TO  Downloads/Plexo", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("NETWORKS", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                            if (networks.isEmpty()) Text("No internet networks are available.")
                            networks.forEach { network ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = network.id in selected,
                                        onCheckedChange = { checked ->
                                            selected = if (checked) selected + network.id else selected - network.id
                                        }
                                    )
                                    Column {
                                        Text(network.label, fontWeight = FontWeight.Medium)
                                        Text(network.kind, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            Text("Connections per network", style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                (listOf(0, 4, 8, 16, 32)).forEach { count ->
                                    val label = if (count == 0) "Auto" else "$count"
                                    if (streams == count) Button(onClick = { streams = count }) { Text(label) }
                                    else OutlinedButton(onClick = { streams = count }) { Text(label) }
                                }
                            }
                            Button(
                                enabled = probe != null && selected.isNotEmpty() && state.status != "downloading",
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    val start: () -> Unit = {
                                        val info = probe
                                        if (info != null) onStart(info, networks.filter { it.id in selected }, streams)
                                    }
                                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                                        context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                                    ) {
                                        pendingStart = start
                                        legacyStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                    } else start()
                                }
                            ) { Text("Start download") }
                            permissionMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                    Text("Different networks can combine bandwidth. Wi-Fi and mobile data are separate routes.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun DownloadPanel(state: DownloadUiState, engine: DownloadEngine?, onResume: () -> Unit) {
    val percent = if (state.totalBytes > 0) state.bytesDownloaded.toDouble() / state.totalBytes else 0.0
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.status.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
            Text(state.fileName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(formatBytes(state.speedBytesPerSecond), style = MaterialTheme.typography.displaySmall,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                Text("/s  TOTAL SPEED", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 7.dp))
            }
            ThroughputLine(state.speedBytesPerSecond, MaterialTheme.colorScheme.secondary)
            Text("${formatBytes(state.bytesDownloaded)} of ${formatBytes(state.totalBytes)}  ·  ${(percent * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            LinearProgressIndicator(progress = { percent.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
            Text("CHUNK MAP", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                state.blockProgress.forEach { progress ->
                    val cellColor = when {
                        progress >= 1f -> Color(0xFF1F8A70)
                        progress > 0f -> Color(0xFFD97706)
                        else -> MaterialTheme.colorScheme.outline
                    }
                    Box(
                        modifier = Modifier.weight(1f).height(12.dp)
                            .background(cellColor, RoundedCornerShape(2.dp))
                    )
                }
            }
            Text("NETWORK  /  SPEED  /  RECEIVED", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            state.networks.forEachIndexed { index, network ->
                val networkColor = when (index % 3) {
                    0 -> Color(0xFF1F8A70)
                    1 -> Color(0xFFD97706)
                    else -> Color(0xFF4F64A5)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Surface(color = networkColor, modifier = Modifier.size(8.dp), shape = MaterialTheme.shapes.small) {}
                    Text(network.label, modifier = Modifier.weight(1f).padding(start = 8.dp), maxLines = 1)
                    Text(formatBytes(network.bytesDownloaded), fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.status == "paused" || state.status == "error") Button(onClick = onResume) { Text("Resume") }
                else Button(onClick = { engine?.pause() }) { Text("Pause") }
                OutlinedButton(onClick = { engine?.cancel() }) { Text("Cancel") }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "Unknown size"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
    return String.format(Locale.ROOT, "%.1f %s", value, units[unit])
}

@Composable
private fun ThroughputLine(speed: Long, color: Color) {
    val samples = remember { mutableListOf<Float>() }
    LaunchedEffect(speed) {
        samples.add(speed.toFloat())
        if (samples.size > 36) samples.removeAt(0)
    }
    Canvas(modifier = Modifier.fillMaxWidth().height(54.dp)) {
        if (samples.size < 2) return@Canvas
        val peak = (samples.maxOrNull() ?: 1f).coerceAtLeast(1f)
        val path = Path()
        samples.forEachIndexed { index, sample ->
            val x = size.width * index / (samples.lastIndex).coerceAtLeast(1)
            val y = size.height - (sample / peak * size.height * 0.82f) - size.height * 0.08f
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
    }
}