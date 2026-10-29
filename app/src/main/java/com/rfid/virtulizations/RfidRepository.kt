package com.rfid.virtulizations

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.selects.onTimeout
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/** One session, one ordered consumer, one writer. SDK callbacks never perform file I/O. */
@OptIn(ExperimentalCoroutinesApi::class)
class RfidSessionManager(
    private val scope: CoroutineScope, private val logger: RfidLogSink,
    private val zone: String, private val mode: QueryMode, private val reader: ReaderInfo,
    private val publish: (Long, Int, Long, Long, Long, List<CountedEvent>) -> Unit,
    private val fatal: (String) -> Unit
) {
    private val gate = Any()
    private val queue = Channel<RfidTagEvent>(8192)
    private var accepting = true
    private val overflow = AtomicLong()
    private val displayGeneration = AtomicLong()
    private var generation = 0L
    private val counters = SessionCounters()
    private val recent = ArrayDeque<CountedEvent>()
    private var failed = 0L
    private var invalid = 0L
    @Volatile var failure: String? = null; private set
    private var lastPublish = 0L
    private var lastFlush = System.nanoTime()
    private val worker = scope.launch(Dispatchers.IO) {
        try {
            while (true) {
                // Atomic selection avoids consuming an event in a timeout cancellation race.
                val result = select {
                    queue.onReceiveCatching { it }
                    onTimeout(250) { null }
                }
                if (result != null && result.isClosed) break
                result?.getOrNull()?.let { event ->
                    val row = counters.accept(event)
                    if (epcProblem(event.epc).isNotEmpty()) invalid++
                    if (failure == null) {
                        try { logger.append(row, zone, mode, reader) }
                        catch (e: Exception) { failed++; fail("TXT write failed: ${e.message}. This session is incomplete.") }
                    } else failed++
                    if (generation != displayGeneration.get()) { recent.clear(); generation = displayGeneration.get() }
                    recent.addFirst(row)
                    if (recent.size > 200) recent.removeLast()
                }
                val now = System.nanoTime()
                if (now - lastFlush >= 1_000_000_000 && failure == null) {
                    try { logger.flush() } catch (e: Exception) { fail("TXT flush failed: ${e.message}. Durability is unconfirmed.") }
                    lastFlush = now
                }
                if (now - lastPublish >= 100_000_000) { snapshot(); lastPublish = now }
            }
        } finally {
            try { logger.close() } catch (e: Exception) { fail("TXT close failed: ${e.message}. Durability is unconfirmed.") }
            snapshot()
        }
    }
    private fun fail(message: String) {
        if (failure != null) return
        failure = message
        synchronized(gate) { accepting = false }
        fatal(message)
    }
    fun offer(event: RfidTagEvent) {
        synchronized(gate) {
            if (!accepting) return
            if (queue.trySend(event).isFailure) {
                overflow.incrementAndGet()
                accepting = false
                fatal("Logging queue exceeded 8192 events. At least one event was not logged; scan stopped.")
            }
        }
    }
    fun clearDisplay() { displayGeneration.incrementAndGet() }
    private fun snapshot() {
        if (generation != displayGeneration.get()) { recent.clear(); generation = displayGeneration.get() }
        publish(counters.total + overflow.get(), counters.unique, logger.written,
            failed + overflow.get(), invalid, recent.toList())
    }
    suspend fun finish() {
        synchronized(gate) { accepting = false; queue.close() }
        worker.join()
    }
}

/** Application-owned scope lets background-stop finish even if the activity is destroyed. */
class RfidRepository(private val sdk: RfidReader, private val directory: File,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val publishFile: (File) -> String? = { it.absolutePath },
    private val checkStorage: () -> Unit = {},
    private val enrichEvent: (RfidTagEvent) -> RfidTagEvent = { it }) {
    private val operations = Mutex()
    private val mutable = MutableStateFlow(RfidUiState())
    val state = mutable.asStateFlow()
    private var session: RfidSessionManager? = null
    private var monitor: Job? = null
    @Volatile private var foreground = true
    init { scope.launch { operations.withLock { refreshFiles() } } }
    private fun refreshFiles() {
        val files = directory.listFiles()?.filter { it.isFile && it.extension.lowercase() in setOf("txt", "csv") }
            ?.sortedByDescending { it.lastModified() }?.map { it.absolutePath }.orEmpty()
        mutable.update { it.copy(savedFiles = files) }
    }
    fun foreground(value: Boolean) {
        foreground = value
        if (!value) action { stopLocked("App backgrounded; session saved."); disconnectLocked() }
    }
    private fun action(block: suspend () -> Unit) {
        scope.launch {
            // Conflicting user operations are ignored while one is in progress.
            operations.withLock {
                mutable.update { it.copy(busy = true) }
                try { block() }
                catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    mutable.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
                } finally { mutable.update { it.copy(busy = false) } }
            }
        }
    }
    fun connect() = action {
        if (mutable.value.connected || !foreground) return@action
        mutable.update { it.copy(message = "Powering and connecting the UHF module...", error = null) }
        val info = sdk.connect()
        if (!foreground) { sdk.disconnect(); return@action }
        mutable.update { it.copy(connected = true, reader = info, message = "Reader connected.") }
        monitor?.cancel()
        monitor = scope.launch {
            while (isActive) {
                delay(1000)
                operations.withLock {
                    if (mutable.value.connected && !sdk.isConnected()) {
                        stopLocked("Reader disconnected unexpectedly; TXT preserved.")
                        disconnectLocked()
                        mutable.update { it.copy(error = "Reader connection lost. Reconnect before scanning.") }
                    }
                }
            }
        }
    }
    fun disconnect() = action { stopLocked(); disconnectLocked() }
    private fun disconnectLocked() {
        sdk.disconnect()
        mutable.update { it.copy(connected = false) }
    }
    fun zone(zone: String) {
        require(zone in listOf("Floor", "BackOffice"))
        mutable.update { if (it.scanning || it.busy) it else it.copy(zone = zone) }
    }
    fun mode(mode: QueryMode) { mutable.update { if (it.scanning || it.busy) it else it.copy(mode = mode) } }
    fun target(value: String) { mutable.update { if (it.scanning || it.busy) it else it.copy(targetEpc = value) } }
    fun power(value: Int) = action {
        check(!mutable.value.scanning && mutable.value.connected) { "Connect and stop scanning before changing power" }
        try { val info = sdk.power(value); mutable.update { it.copy(reader = info, error = null, message = "Transmit power confirmed.") } }
        catch (e: Exception) { val info = sdk.info(); mutable.update { it.copy(reader = info) }; throw e }
    }
    fun range(value: Int) = action {
        check(!mutable.value.scanning && mutable.value.connected) { "Connect and stop scanning before changing range" }
        try { val info = sdk.range(value); mutable.update { it.copy(reader = info, error = null, message = "Range confirmed (vendor scale; not metres).") } }
        catch (e: Exception) { val info = sdk.info(); mutable.update { it.copy(reader = info) }; throw e }
    }
    fun clearDisplay() {
        session?.clearDisplay()
        mutable.update { it.copy(rows = emptyList()) }
    }
    fun start() = action {
        if (session != null || !foreground) return@action
        checkStorage()
        check(sdk.isConnected()) { "Reader is not connected" }
        val current = mutable.value
        val zone = checkNotNull(current.zone) { "Select Floor or BackOffice" }
        if (current.mode == QueryMode.FIND) require(epcProblem(current.targetEpc).isEmpty()) { "Enter a valid target EPC" }
        val info = sdk.configure(current.mode)
        val logger = RfidCsvLogger.create(directory, zone)
        mutable.update { it.copy(scanning = true, total = 0, unique = 0, written = 0, unlogged = 0, invalid = 0,
            rows = emptyList(), reader = info, filePath = logger.file.absolutePath, startedAt = System.currentTimeMillis(),
            endedAt = null, savedLocation = null, error = null, message = "Scanning - each callback is appended to TXT.") }
        val active = RfidSessionManager(scope, logger, zone, current.mode, info,
            publish = { total, unique, written, unlogged, invalid, rows ->
                mutable.update { it.copy(total = total, unique = unique, written = written, unlogged = unlogged, invalid = invalid, rows = rows) }
            }, fatal = { message ->
                scope.launch { operations.withLock { if (session != null) {
                    mutable.update { it.copy(error = message, busy = true) }
                    stopLocked("Scan stopped after a logging error.")
                    mutable.update { it.copy(busy = false) }
                } } }
            })
        session = active
        try {
            sdk.start(current.targetEpc, { active.offer(enrichEvent(it)) }) {
                scope.launch { operations.withLock {
                    if (session === active) {
                        mutable.update { it.copy(error = "Inventory ended unexpectedly", busy = true) }
                        stopLocked("Inventory ended; TXT closed.")
                        mutable.update { it.copy(busy = false) }
                    }
                } }
            }
        } catch (e: Throwable) {
            stopLocked("Inventory failed to start; partial TXT retained.")
            throw e
        }
    }
    fun stop() = action { stopLocked() }
    private suspend fun stopLocked(message: String = "Session saved automatically.") {
        val active = session ?: return
        var stopError: String? = null
        try { sdk.stop() } catch (e: Throwable) {
            stopError = e.message ?: "Inventory stop failed"
            disconnectLocked()
        } finally {
            active.finish()
            session = null
            var destination: String? = null
            var saveError: String? = null
            try { mutable.value.filePath?.let { destination = publishFile(File(it)) } }
            catch (e: Exception) { saveError = "DCIM save failed: ${e.message}. Original TXT retained: ${mutable.value.filePath}" }
            mutable.update {
                val error = listOfNotNull(active.failure, stopError, it.error, saveError).distinct().joinToString("\n").ifEmpty { null }
                it.copy(scanning = false, endedAt = System.currentTimeMillis(),
                    savedLocation = destination,
                    message = if (error != null) "Session ended with errors. TXT retained: ${it.filePath}"
                        else if (destination == null) "Reads kept safely. Scan the other zone and press Stop to save one combined TXT file."
                        else "$message File: $destination",
                    error = error)
            }
            refreshFiles()
        }
    }
}
