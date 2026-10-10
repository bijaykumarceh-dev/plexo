package com.plexo.android

import android.content.ContentValues
import android.content.Context
import android.net.Network
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

data class ProbeInfo(
    val url: String,
    val fileName: String,
    val totalBytes: Long,
    val supportsRanges: Boolean,
    val etag: String?,
    val lastModified: String?
)

data class DownloadUiState(
    val status: String = "idle",
    val fileName: String = "",
    val totalBytes: Long = 0,
    val bytesDownloaded: Long = 0,
    val speedBytesPerSecond: Long = 0,
    val blockProgress: List<Float> = emptyList(),
    val error: String? = null,
    val networks: List<NetworkProgress> = emptyList()
)

data class NetworkProgress(
    val id: String,
    val label: String,
    val bytesDownloaded: Long = 0
)

class DownloadEngine(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(DownloadUiState())
    val state: StateFlow<DownloadUiState> = mutableState.asStateFlow()
    private var job: Job? = null
    private var request: RunRequest? = null
    private var completedBlocks = mutableSetOf<Int>()
    private var stageFile: File? = null
    // Serializes start/pause/resume/run so rapid taps can't orphan a job and
    // leave two perform() writers interleaving on the same RandomAccessFile.
    private val controlMutex = Mutex()

    /** Desktop parity sanitize: one component, never a path; strips RTL
     * overrides, control chars and reserved names; caps length. */
    private fun sanitizeFileName(raw: String): String {
        var safe = raw
            .replace(Regex("[\u202A-\u202E\u200E\u200F\u2066-\u2069]"), "")
            .replace(Regex("[\\\\/]"), "_")
            .replace(Regex("\\p{Cc}"), "_")
            .trim()
        safe = safe.replace(Regex("[<>:\"|?*]"), "_").trimEnd(' ', '.')
        if (safe.isEmpty() || safe.matches(Regex("^\\.+$")) || safe == ".." || safe == ".") return "download"
        if (safe.matches(Regex("(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?$"))) safe = "_$safe"
        if (safe.length > 180) safe = safe.take(180).trim().ifEmpty { "download" }
        return safe
    }

    private fun isHttpUrl(url: URL): Boolean = url.protocol == "http" || url.protocol == "https"

    private fun AtomicLongArray.sumBytes(): Long = (0 until length()).sumOf { get(it) }

    private fun blockProgressSnapshot(
        blocks: AtomicLongArray,
        blockSize: Long,
        totalBytes: Long
    ): List<Float> {
        val visibleCells = minOf(blocks.length(), 72)
        if (visibleCells == 0) return emptyList()
        return List(visibleCells) { cell ->
            val first = cell * blocks.length() / visibleCells
            val afterLast = ((cell + 1) * blocks.length() / visibleCells).coerceAtLeast(first + 1)
            val range = first until afterLast
            val completed = range.count { index ->
                val expected = if (totalBytes > 0) minOf(blockSize, totalBytes - index * blockSize) else blockSize
                blocks.get(index) >= expected
            }
            val active = range.any { index ->
                val expected = if (totalBytes > 0) minOf(blockSize, totalBytes - index * blockSize) else blockSize
                blocks.get(index) in 1 until expected
            }
            when {
                completed == afterLast - first -> 1f
                active || completed > 0 -> 0.5f
                else -> 0f
            }
        }
    }

    private data class RunRequest(
        val probe: ProbeInfo,
        val networks: List<AndroidNetwork>,
        val streamsPerNetwork: Int
    )

    suspend fun probe(urlText: String, network: Network?): ProbeInfo = withContext(Dispatchers.IO) {
        val url = URL(urlText.trim())
        require(url.protocol == "https" || url.protocol == "http") { "Enter a valid HTTP or HTTPS URL." }
        require(urlText.trim().length <= 8000) { "That URL is too long." }
        var current = url
        var connection: HttpURLConnection? = null
        try {
            repeat(6) { hop ->
                require(isHttpUrl(current)) { "The server redirected to an unsupported address." }
                val opened = (network?.openConnection(current) ?: current.openConnection()) as HttpURLConnection
                connection = opened
                opened.instanceFollowRedirects = false
                opened.connectTimeout = 15_000
                opened.readTimeout = 15_000
                opened.setRequestProperty("Range", "bytes=0-0")
                opened.setRequestProperty("User-Agent", "Plexo Android")
                val code = opened.responseCode
                if (code in 300..399) {
                    val location = opened.getHeaderField("Location")
                        ?: error("The server returned a redirect without a destination.")
                    if (hop == 5) error("Too many redirects.")
                    val next = URL(current, location)
                    require(isHttpUrl(next)) { "The server redirected to an unsupported address." }
                    if (current.protocol == "https" && next.protocol != "https") {
                        error("The server tried to redirect a secure download to an insecure address.")
                    }
                    current = next
                    opened.disconnect()
                    connection = null
                    return@repeat
                }
                if (code !in 200..299) {
                    if (code == 401) error("This link needs a login (401). Open it in a browser first.")
                    if (code == 404) error("The file was not found (404). The link may have expired.")
                    error("Server returned HTTP $code.")
                }
                val contentRange = opened.getHeaderField("Content-Range")
                val rangeSize = Regex("/([0-9]+)$").find(contentRange.orEmpty())?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it >= 0 }
                val rawLength = opened.getHeaderField("Content-Length")?.trim()?.toLongOrNull()?.takeIf { it >= 0 } ?: 0L
                val total = rangeSize ?: rawLength
                val disposition = opened.getHeaderField("Content-Disposition").orEmpty()
                val fromHeader = Regex("filename\\*=UTF-8''([^;]+)|filename=\"?([^\";]+)", RegexOption.IGNORE_CASE)
                    .find(disposition)?.let { it.groups[1]?.value ?: it.groups[2]?.value }
                val decoded = try {
                    fromHeader?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                } catch (_: Exception) { fromHeader }
                val fileName = sanitizeFileName(
                    decoded ?: current.path.substringAfterLast('/').ifBlank { "download" }
                )
                return@withContext ProbeInfo(
                    url = current.toString(),
                    fileName = fileName,
                    totalBytes = total,
                    supportsRanges = code == HttpURLConnection.HTTP_PARTIAL && rangeSize != null,
                    etag = opened.getHeaderField("ETag")?.take(500),
                    lastModified = opened.getHeaderField("Last-Modified")?.take(200)
                )
            }
            error("Could not check this URL.")
        } finally {
            connection?.disconnect()
        }
    }

    fun start(probe: ProbeInfo, networks: List<AndroidNetwork>, streamsPerNetwork: Int) {
        require(networks.isNotEmpty()) { "Select at least one network." }
        require(isHttpUrl(URL(probe.url))) { "Only HTTP and HTTPS links are supported." }
        scope.launch {
            controlMutex.withLock {
                job?.cancelAndJoin()
                val next = RunRequest(probe, networks, streamsPerNetwork.coerceIn(0, 32))
                request = next
                // Always start fresh: reusing an old stage file with stale
                // completedBlocks corrupts offsets when the new file differs.
                stageFile?.delete()
                val safeName = sanitizeFileName(probe.fileName.ifBlank { "download" })
                val cleanProbe = probe.copy(fileName = safeName)
                stageFile = File(context.getExternalFilesDir(null) ?: context.filesDir, "$safeName.plexo")
                completedBlocks.clear()
                // Upfront disk-space guard (desktop parity): fail fast, not mid-write.
                if (cleanProbe.totalBytes > 0) {
                    try {
                        val dir = stageFile!!.parentFile ?: context.filesDir
                        val stat = android.os.StatFs(dir.absolutePath)
                        val free = stat.availableBytes
                        if (free < cleanProbe.totalBytes) {
                            mutableState.value = mutableState.value.copy(
                                status = "error",
                                error = "Not enough disk space for this download."
                            )
                            request = next.copy(probe = cleanProbe)
                            return@withLock
                        }
                    } catch (_: Exception) { /* best-effort: download will surface ENOSPC */ }
                }
                request = next.copy(probe = cleanProbe)
                run(next.copy(probe = cleanProbe))
            }
        }
    }

    fun pause() {
        scope.launch {
            controlMutex.withLock {
                job?.cancelAndJoin()
                mutableState.value = mutableState.value.copy(status = "paused", speedBytesPerSecond = 0)
            }
        }
    }

    fun resume() {
        val saved = request ?: return
        scope.launch {
            controlMutex.withLock {
                job?.cancelAndJoin()
                try {
                    val current = probe(saved.probe.url, saved.networks.firstOrNull()?.network)
                    val etagChanged = saved.probe.etag != null && current.etag != saved.probe.etag
                    val dateChanged = saved.probe.lastModified != null && current.lastModified != saved.probe.lastModified
                    val sizeChanged = saved.probe.totalBytes > 0 && current.totalBytes > 0 &&
                        current.totalBytes != saved.probe.totalBytes
                    if (etagChanged || dateChanged || sizeChanged) {
                        error("The remote file changed. Start a new download to avoid a corrupted file.")
                    }
                    // Both validators missing + unknown size: nothing proves it is the same file.
                    if (saved.probe.etag == null && saved.probe.lastModified == null && saved.probe.totalBytes <= 0) {
                        error("Cannot safely resume: the server gave no version info. Start the download over.")
                    }
                } catch (error: Exception) {
                    mutableState.value = mutableState.value.copy(status = "error", error = error.message)
                    return@withLock
                }
                run(saved)
            }
        }
    }

    fun cancel() {
        scope.launch {
            controlMutex.withLock {
                job?.cancelAndJoin()
                stageFile?.delete()
                stageFile = null
                completedBlocks.clear()
                request = null
                mutableState.value = DownloadUiState()
            }
        }
    }

    private suspend fun run(runRequest: RunRequest) {
        val downloadJob = scope.launch { perform(runRequest) }
        job = downloadJob
        downloadJob.join()
    }

    private suspend fun perform(runRequest: RunRequest) {
        val probe = runRequest.probe
        val stage = stageFile ?: return
        val rangeMode = probe.supportsRanges && probe.totalBytes > 0
        val blockSize = 8L * 1024 * 1024
        val blockCount = if (rangeMode) ((probe.totalBytes + blockSize - 1) / blockSize).toInt() else 1
        val file = java.io.RandomAccessFile(stage, "rw")
        if (rangeMode && file.length() != probe.totalBytes) file.setLength(probe.totalBytes)
        file.close()

        val bytesByNetwork = runRequest.networks.associate { it.id to AtomicLong(0) }
        val completedBytes = synchronized(completedBlocks) {
            completedBlocks.sumOf { index -> minOf(blockSize, probe.totalBytes - index * blockSize) }
        }
        val progressByBlock = AtomicLongArray(blockCount).apply {
            synchronized(completedBlocks) {
                completedBlocks.forEach { index ->
                    set(index, minOf(blockSize, probe.totalBytes - index * blockSize))
                }
            }
        }
        val nextBlock = AtomicInteger(0)
        val writeMutex = Mutex()
        mutableState.value = DownloadUiState(
            status = "downloading",
            fileName = probe.fileName,
            totalBytes = probe.totalBytes,
            bytesDownloaded = completedBytes,
            blockProgress = blockProgressSnapshot(progressByBlock, blockSize, probe.totalBytes),
            networks = runRequest.networks.map { NetworkProgress(it.id, it.label, 0) }
        )

        val runner = scope.async {
            coroutineScope {
                val workers = runRequest.networks.flatMap { network ->
                    val streamsForNetwork = if (!rangeMode) 1 else if (runRequest.streamsPerNetwork == 0) {
                        maxOf(1, minOf(8, (blockCount + runRequest.networks.size - 1) / runRequest.networks.size))
                    } else runRequest.streamsPerNetwork
                    List(streamsForNetwork) {
                        launch {
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val index = nextBlock.getAndIncrement()
                                if (index >= blockCount) break
                                if (synchronized(completedBlocks) { index in completedBlocks }) continue
                                val start = if (rangeMode) index * blockSize else 0L
                                val end = if (rangeMode) minOf(start + blockSize, probe.totalBytes) - 1 else null
                                var attempt = 0
                                while (true) {
                                    try {
                                        fetchBlock(
                                            runRequest, network, stage, index, start, end, blockSize,
                                            writeMutex, progressByBlock, bytesByNetwork
                                        )
                                        synchronized(completedBlocks) { completedBlocks.add(index) }
                                        break
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: Exception) {
                                        progressByBlock.set(index, 0)
                                        mutableState.value = mutableState.value.copy(
                                            bytesDownloaded = progressByBlock.sumBytes(),
                                            blockProgress = blockProgressSnapshot(progressByBlock, blockSize, probe.totalBytes)
                                        )
                                        attempt++
                                        if (attempt >= 5) throw error
                                        kotlinx.coroutines.delay(1000L shl (attempt - 1))
                                    }
                                }
                                mutableState.value = mutableState.value.copy(
                                    bytesDownloaded = progressByBlock.sumBytes(),
                                    blockProgress = blockProgressSnapshot(progressByBlock, blockSize, probe.totalBytes),
                                    networks = networkProgress(runRequest.networks, bytesByNetwork)
                                )
                            }
                        }
                    }
                }
                workers.forEach { it.join() }
            }
        }
        job = runner
        try {
            runner.await()
            publish(stage, probe.fileName)
            stageFile = null
            request = null
            mutableState.value = mutableState.value.copy(
                status = "completed",
                bytesDownloaded = if (probe.totalBytes > 0) probe.totalBytes else progressByBlock.sumBytes(),
                speedBytesPerSecond = 0
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(status = "error", error = error.message)
        }
    }

    private suspend fun fetchBlock(
        runRequest: RunRequest,
        network: AndroidNetwork,
        stage: File,
        index: Int,
        start: Long,
        end: Long?,
        blockSize: Long,
        writeMutex: Mutex,
        progressByBlock: AtomicLongArray,
        bytesByNetwork: Map<String, AtomicLong>
    ) = withContext(Dispatchers.IO) {
        val url = URL(runRequest.probe.url)
        val connection = (network.network.openConnection(url) as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "Plexo Android")
            if (end != null) setRequestProperty("Range", "bytes=$start-$end")
            runRequest.probe.etag?.let { setRequestProperty("If-Range", it) }
        }
        try {
            val code = connection.responseCode
            if (code == 401) error("This link needs a login (401). Open it in a browser first.")
            if (code == 404) error("The file was not found (404). The link may have expired.")
            if ((end != null && code != HttpURLConnection.HTTP_PARTIAL) || code !in 200..299) {
                val retryAfter = retryAfterMillis(connection.getHeaderField("Retry-After"))
                if (retryAfter != null && retryAfter > 0) {
                    try { kotlinx.coroutines.delay(retryAfter.coerceAtMost(60_000L)) } catch (_: Exception) { }
                }
                error("${network.label}: server returned HTTP $code.")
            }
            if (end == null && code == HttpURLConnection.HTTP_PARTIAL) {
                // A server that supports ranges is handled by the parallel path instead.
            }
            connection.inputStream.use { input ->
                java.io.RandomAccessFile(stage, "rw").use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var offset = start
                    var lastPublishedAt = 0L
                    var lastSpeedAt = System.currentTimeMillis()
                    var lastSpeedBytes = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        writeMutex.withLock {
                            output.seek(offset)
                            output.write(buffer, 0, count)
                        }
                        offset += count
                        val networkTotal = bytesByNetwork.getValue(network.id).addAndGet(count.toLong())
                        val blockProgress = if (end == null) offset - start else (offset - start).coerceAtMost(blockSize)
                        progressByBlock.set(index, blockProgress)
                        val now = System.currentTimeMillis()
                        if (now - lastPublishedAt >= 200) {
                            val elapsed = (now - lastSpeedAt).coerceAtLeast(1)
                            val speed = (networkTotal - lastSpeedBytes) * 1000 / elapsed
                            mutableState.value = mutableState.value.copy(
                                bytesDownloaded = progressByBlock.sumBytes(),
                                blockProgress = blockProgressSnapshot(progressByBlock, blockSize, runRequest.probe.totalBytes),
                                speedBytesPerSecond = speed,
                                networks = networkProgress(runRequest.networks, bytesByNetwork)
                            )
                            lastPublishedAt = now
                            lastSpeedAt = now
                            lastSpeedBytes = networkTotal
                        }
                    }
                    if (end != null && offset != end + 1) error("The server sent an incomplete file range.")
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun retryAfterMillis(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val trimmed = header.trim()
        trimmed.toLongOrNull()?.let { if (it >= 0) return it * 1000L else return null }
        trimmed.toDoubleOrNull()?.let { if (it >= 0) return (it * 1000).toLong() else return null }
        return try {
            val date = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US)
                .parse(trimmed)?.time ?: return null
            maxOf(0L, date - System.currentTimeMillis())
        } catch (_: Exception) { null }
    }

    private fun networkProgress(
        networks: List<AndroidNetwork>,
        bytesByNetwork: Map<String, AtomicLong>
    ): List<NetworkProgress> = networks.map { network ->
        NetworkProgress(network.id, network.label, bytesByNetwork[network.id]?.get() ?: 0)
    }

    private suspend fun publish(file: File, fileName: String) = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            publishLegacy(file, fileName)
            return@withContext
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Plexo")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create the file in Downloads.")
        try {
            resolver.openOutputStream(uri, "w")?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: error("Could not write the completed file.")
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            file.delete()
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun publishLegacy(file: File, fileName: String) {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val folder = File(downloads, "Plexo")
        if (!folder.exists() && !folder.mkdirs()) error("Could not create the Plexo folder in Downloads.")

        val safeName = sanitizeFileName(fileName.ifBlank { "download" })
        // Never escape the Plexo folder, even if a stale probe carried "..".
        require(!safeName.contains('/') && !safeName.contains('\\')) { "Invalid file name." }
        val extension = safeName.substringAfterLast('.', "").takeIf { it.isNotEmpty() }
        val stem = if (extension == null) safeName else safeName.removeSuffix(".$extension")
        var destination = File(folder, safeName)
        var suffix = 1
        while (destination.exists()) {
            val numbered = if (extension == null) "$stem ($suffix)" else "$stem ($suffix).$extension"
            destination = File(folder, numbered)
            suffix++
        }

        val temporary = File(folder, ".${destination.name}.plexo")
        try {
            file.inputStream().use { input -> temporary.outputStream().use { output -> input.copyTo(output) } }
            if (!temporary.renameTo(destination)) error("Could not move the completed file into Downloads/Plexo.")
            file.delete()
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }
}