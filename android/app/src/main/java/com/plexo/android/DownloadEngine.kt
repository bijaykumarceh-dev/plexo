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
        var current = url
        var connection: HttpURLConnection? = null
        try {
            repeat(6) { hop ->
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
                    current = URL(current, location)
                    opened.disconnect()
                    connection = null
                    return@repeat
                }
                if (code !in 200..299) error("Server returned HTTP $code.")
                val contentRange = opened.getHeaderField("Content-Range")
                val rangeSize = Regex("/([0-9]+)$").find(contentRange.orEmpty())?.groupValues?.get(1)?.toLongOrNull()
                val total = rangeSize ?: opened.getHeaderFieldLong("Content-Length", 0L)
                val disposition = opened.getHeaderField("Content-Disposition").orEmpty()
                val fromHeader = Regex("filename\\*=UTF-8''([^;]+)|filename=\"?([^\";]+)", RegexOption.IGNORE_CASE)
                    .find(disposition)?.let { it.groups[1]?.value ?: it.groups[2]?.value }
                val fileName = (fromHeader?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                    ?: current.path.substringAfterLast('/').ifBlank { "download" })
                    .substringAfterLast('/').replace(Regex("[\\\\/:*?\"<>|]"), "_")
                return@withContext ProbeInfo(
                    url = current.toString(),
                    fileName = fileName,
                    totalBytes = total,
                    supportsRanges = code == HttpURLConnection.HTTP_PARTIAL && rangeSize != null,
                    etag = opened.getHeaderField("ETag"),
                    lastModified = opened.getHeaderField("Last-Modified")
                )
            }
            error("Could not check this URL.")
        } finally {
            connection?.disconnect()
        }
    }

    fun start(probe: ProbeInfo, networks: List<AndroidNetwork>, streamsPerNetwork: Int) {
        require(networks.isNotEmpty()) { "Select at least one network." }
        scope.launch {
            job?.cancelAndJoin()
            val next = RunRequest(probe, networks, streamsPerNetwork.coerceIn(0, 32))
            request = next
            if (stageFile == null) {
                val safeName = probe.fileName.ifBlank { "download" }
                stageFile = File(context.getExternalFilesDir(null) ?: context.filesDir, "$safeName.plexo")
                completedBlocks.clear()
            }
            run(next)
        }
    }

    fun pause() {
        scope.launch {
            job?.cancelAndJoin()
            mutableState.value = mutableState.value.copy(status = "paused", speedBytesPerSecond = 0)
        }
    }

    fun resume() {
        val saved = request ?: return
        scope.launch {
            job?.cancelAndJoin()
            if (saved.probe.etag != null || saved.probe.lastModified != null) {
                try {
                    val current = probe(saved.probe.url, saved.networks.firstOrNull()?.network)
                    if ((saved.probe.etag != null && current.etag != saved.probe.etag) ||
                        (saved.probe.lastModified != null && current.lastModified != saved.probe.lastModified)
                    ) error("The remote file changed. Start a new download to avoid a corrupted file.")
                } catch (error: Exception) {
                    mutableState.value = mutableState.value.copy(status = "error", error = error.message)
                    return@launch
                }
            }
            run(saved)
        }
    }

    fun cancel() {
        scope.launch {
            job?.cancelAndJoin()
            stageFile?.delete()
            stageFile = null
            completedBlocks.clear()
            request = null
            mutableState.value = DownloadUiState()
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
            if ((end != null && code != HttpURLConnection.HTTP_PARTIAL) || code !in 200..299) {
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

        val safeName = fileName.ifBlank { "download" }
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