package com.rfid.virtulizations

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.zebra.rfid.api3.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Zebra API3 adapter; all configuration calls are serialized by the repository. */
class ZebraRfidReader(private val context: Context, private val transport: ENUM_TRANSPORT) : RfidReader {
    private var readers: Readers? = null
    private var device: RFIDReader? = null
    private val sound = RfidScanSound(context)
    private val drainLock = Any()
    @Volatile private var onTag: ((RfidTagEvent) -> Unit)? = null
    @Volatile private var onEnd: (() -> Unit)? = null
    @Volatile private var stopping = false
    private var stopped = CompletableDeferred<Unit>()
    private var target: String? = null
    private var mode = QueryMode.EPC
    private var connectedTransport = transport
    private var phaseSupported = false
    private val listener = object : RfidEventsListener {
        override fun eventReadNotify(event: RfidReadEvents?) { drain() }
        override fun eventStatusNotify(event: RfidStatusEvents) {
            when (event.StatusEventData.statusEventType) {
                STATUS_EVENT_TYPE.INVENTORY_STOP_EVENT -> {
                    drain(); stopped.complete(Unit)
                    if (!stopping) onEnd?.invoke()
                }
                STATUS_EVENT_TYPE.DISCONNECTION_EVENT, STATUS_EVENT_TYPE.BUFFER_FULL_EVENT,
                STATUS_EVENT_TYPE.READER_EXCEPTION_EVENT -> onEnd?.invoke()
            }
        }
    }
    override fun isConnected() = runCatching { device?.isConnected == true }.getOrDefault(false)
    override suspend fun connect(): ReaderInfo {
        disconnect()
        try {
            val available = discover()
            check(!available.isNullOrEmpty()) { "No Zebra reader found. Pair/power the reader and select its connection type." }
            check(available.size == 1) { "Multiple Zebra readers found. Leave only the intended reader paired/attached." }
            val r = available.single().rfidReader.also { device = it }
            r.connect()
            r.Events.addEventsListener(listener)
            r.Events.setTagReadEvent(true)
            r.Events.setAttachTagDataWithReadEvent(false)
            r.Events.setInventoryStopEvent(true)
            r.Events.setReaderDisconnectEvent(true)
            r.Events.setBufferFullEvent(true)
            r.Events.setReaderExceptionEvent(true)
            phaseSupported = r.ReaderCapabilities.isTagPhaseReportingSupported()
            configure(QueryMode.EPC)
            return info()
        } catch (e: Exception) { disconnect(); throw IllegalStateException("Zebra connection failed: ${e.message}", e) }
    }
    private suspend fun discover(): List<ReaderDevice> {
        val transports = if (transport == ENUM_TRANSPORT.ALL) listOf(ENUM_TRANSPORT.BLUETOOTH,
            ENUM_TRANSPORT.SERVICE_USB, ENUM_TRANSPORT.SERVICE_SERIAL, ENUM_TRANSPORT.QC_SERIAL,
            ENUM_TRANSPORT.RE_SERIAL, ENUM_TRANSPORT.RE_USB) else listOf(transport)
        val failures = mutableListOf<String>()
        for (candidate in transports) {
            try {
                android.util.Log.i("RfidConnection", "Discovering $candidate on ${Build.MANUFACTURER} ${Build.MODEL}")
                val discovery = Readers(context, candidate).also { readers = it }
                var available = discovery.GetAvailableRFIDReaderList()
                repeat(4) {
                    if (available.isNullOrEmpty()) { delay(500); available = discovery.GetAvailableRFIDReaderList() }
                }
                if (!available.isNullOrEmpty()) {
                    connectedTransport = candidate
                    android.util.Log.i("RfidConnection", "Found ${available.size} reader(s) on $candidate")
                    return available
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { failures.add("$candidate: ${e.message}") }
            catch (e: LinkageError) { failures.add("$candidate: required hardware service unavailable") }
            runCatching { readers?.Dispose() }; readers = null
        }
        error("No Zebra reader found. Power on/attach the reader or pair it in Android Bluetooth settings. ${failures.joinToString("; ")}")
    }
    private fun levels() = checkNotNull(device).ReaderCapabilities.transmitPowerLevelValues
    override fun info(): ReaderInfo {
        val r = checkNotNull(device)
        val caps = r.ReaderCapabilities
        val rf = r.Config.Antennas.getAntennaRfConfig(1)
        val power = levels().getOrNull(rf.transmitPowerIndex)?.let(::zebraPowerDbm).orEmpty()
        return ReaderInfo(mapOf("deviceName" to (caps.modelName ?: Build.MODEL), "manufacturer" to "Zebra",
            "deviceNumber" to Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty(),
            "serial" to caps.serialNumber.orEmpty(), "firmwareVersion" to caps.firwareVersion.orEmpty(),
            "module" to "Zebra", "sdkVersion" to "2.0.5.275", "readerType" to "Zebra API3",
            "connectionType" to "Zebra $connectedTransport", "connectionStatus" to if (isConnected()) "Connected" else "Disconnected",
            "transmitPower" to power, "supportedPowers" to zebraSupportedPowers(levels()).joinToString(","),
            "configuredAntenna" to "1", "inventorySession" to "S0"))
    }
    override fun configure(mode: QueryMode): ReaderInfo {
        require(mode == QueryMode.EPC || mode == QueryMode.FIND) { "Zebra currently supports EPC inventory and selected EPC diagnostics" }
        this.mode = mode
        val r = checkNotNull(device)
        val triggers = TriggerInfo()
        triggers.StartTrigger.setTriggerType(START_TRIGGER_TYPE.START_TRIGGER_TYPE_IMMEDIATE)
        triggers.StopTrigger.setTriggerType(STOP_TRIGGER_TYPE.STOP_TRIGGER_TYPE_IMMEDIATE)
        r.Config.setStartTrigger(triggers.StartTrigger)
        r.Config.setStopTrigger(triggers.StopTrigger)
        r.Config.setUniqueTagReport(false)
        val storage = r.Config.tagStorageSettings
        val fields = mutableListOf(TAG_FIELD.ANTENNA_ID, TAG_FIELD.PEAK_RSSI)
        if (phaseSupported) fields.add(TAG_FIELD.PHASE_INFO)
        storage.setTagFields(fields.toTypedArray())
        storage.discardTagsOnInventoryStop(false)
        r.Config.setTagStorageSettings(storage)
        val singulation = r.Config.Antennas.getSingulationControl(1)
        singulation.setSession(SESSION.SESSION_S0)
        singulation.Action.setInventoryState(INVENTORY_STATE.INVENTORY_STATE_A)
        singulation.Action.setSLFlag(SL_FLAG.SL_ALL)
        r.Config.Antennas.setSingulationControl(1, singulation)
        r.Actions.PreFilters.deleteAll()
        return info()
    }
    override fun power(value: Int): ReaderInfo {
        val index = zebraPowerIndex(levels(), value)
        val r = checkNotNull(device)
        val config = r.Config.Antennas.getAntennaRfConfig(1)
        config.setTransmitPowerIndex(index)
        r.Config.Antennas.setAntennaRfConfig(1, config)
        check(r.Config.Antennas.getAntennaRfConfig(1).transmitPowerIndex == index) { "Zebra power readback did not match" }
        return info()
    }
    override fun range(value: Int): ReaderInfo = throw UnsupportedOperationException("Use RF power in dBm for Zebra")
    override fun start(targetEpc: String?, onTag: (RfidTagEvent) -> Unit, onUnexpectedEnd: () -> Unit) {
        val r = checkNotNull(device)
        r.Actions.purgeTags()
        target = if (mode == QueryMode.FIND) targetEpc?.takeIf { it.isNotBlank() } else null
        this.onTag = onTag; onEnd = onUnexpectedEnd; stopping = false; stopped = CompletableDeferred()
        sound.start()
        try { r.Actions.Inventory.perform() }
        catch (e: Exception) { this.onTag = null; onEnd = null; sound.stop(); throw e }
    }
    private fun drain() = synchronized(drainLock) {
        val r = device ?: return@synchronized
        while (true) {
            val tags = r.Actions.getReadTags(1000) ?: break
            if (tags.isEmpty()) break
            for (tag in tags) {
                if (target != null && !tag.tagID.equals(target, true)) continue
                onTag?.invoke(RfidTagEvent(System.currentTimeMillis(), tag.tagID, null, tag.peakRSSI.toString(),
                    phase = if (phaseSupported) tag.phase.toString() else null,
                    antennaId = tag.antennaID.toString(), source = "zebraInventoryCallback"))
                sound.beep()
            }
        }
    }
    override suspend fun stop() {
        if (onTag == null) return
        stopping = true
        try {
            checkNotNull(device).Actions.Inventory.stop()
            check(withTimeoutOrNull(3000) { stopped.await(); true } == true) { "Zebra inventory stop was not confirmed; session may be incomplete" }
            drain()
        } finally { synchronized(drainLock) { onTag = null; onEnd = null }; sound.stop() }
    }
    override fun disconnect() {
        stopping = true; onTag = null; onEnd = null
        sound.stop()
        device?.let { r ->
            runCatching { r.Events.removeEventsListener(listener) }
            runCatching { if (r.isConnected) r.disconnect() }
        }
        device = null
        runCatching { readers?.Dispose() }; readers = null
    }
}

/** API3 power tables contain tenths of dBm; the index itself is not dBm. */
fun zebraPowerDbm(value: Int): String = java.math.BigDecimal(value).movePointLeft(1).stripTrailingZeros().toPlainString()
fun zebraSupportedPowers(levels: IntArray): List<Int> = levels.filter { it % 10 == 0 }
    .map { it / 10 }.filter { it in 5..30 }.distinct().sorted()
fun zebraPowerIndex(levels: IntArray, dbm: Int): Int {
    require(dbm in 5..30) { "Select power between 5 and 30 dBm" }
    return levels.indexOf(dbm * 10).also { require(it >= 0) { "This Zebra reader does not support $dbm dBm" } }
}
