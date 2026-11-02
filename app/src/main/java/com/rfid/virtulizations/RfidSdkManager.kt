package com.rfid.virtulizations

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import com.ubx.usdk.RFIDSDKManager
import com.ubx.usdk.rfid.RfidManager
import com.ubx.usdk.rfid.aidl.IRfidCallback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.*

/** Call only from serialized IO operations. Callback runs on vendor threads. */
class RfidSdkManager(private val context: Context) : RfidReader {
    private val scanSound = RfidScanSound(context)
    // Lazy: unsupported devices must show an error, not crash when the screen opens.
    private val sdk by lazy { RFIDSDKManager.getInstance() }
    private var manager: RfidManager? = null
    @Volatile private var callback: IRfidCallback? = null
    private var configuredMode = QueryMode.EPC
    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var probe: Job? = null
    @Volatile private var stopping = false
    private var ended = CompletableDeferred<Unit>()
    override fun isConnected(): Boolean = runCatching { manager?.isConnected == true }.getOrDefault(false)
    override suspend fun connect(): ReaderInfo {
        try {
            check(sdk.power(true) >= 0) { "SDK could not power the UHF module" }
            delay(1500)
            check(sdk.connect()) { "XR2 UHF connection failed. Check reader hardware and close other RFID apps." }
            manager = checkNotNull(sdk.rfidManager) { "SDK returned no RFID manager" }
            check(isConnected()) { "RFID module is not connected" }
            val m = checkNotNull(manager)
            m.enableLog(false)
            val maxPower = if (m.readerType == 80) 26 else 30
            runCatching { m.setOutputPower(maxPower) }
            runCatching { m.setRange(100) }
            return info()
        } catch (e: Throwable) { disconnect(); throw e }
    }
    override fun info(): ReaderInfo {
        val m = checkNotNull(manager)
        fun text(block: () -> String?): String = runCatching { block()?.takeUnless { it.isBlank() || it == "-1" || it == "-2" || it == "-3" }.orEmpty() }.getOrDefault("")
        fun number(block: () -> Int): String = runCatching { block().takeIf { it >= 0 }?.toString().orEmpty() }.getOrDefault("")
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val params = runCatching { m.inventoryParameter }.getOrNull()
        val region = runCatching { m.frequencyRegion }.getOrNull()
        return ReaderInfo(mapOf(
            "deviceName" to Build.MODEL.orEmpty(), "manufacturer" to Build.MANUFACTURER.orEmpty(),
            "deviceNumber" to text { Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) },
            "serial" to text { m.deviceId }, "firmwareVersion" to text { m.firmwareVersion },
            "moduleFirmware" to text { m.moduleFirmware }, "module" to text { m.module },
            "connectionType" to "Built-in UHF / vendor serial SDK", "connectionStatus" to if (isConnected()) "Connected" else "Disconnected",
            "readerType" to number { m.readerType }, "transmitPower" to number { m.outputPower },
            "readRange" to number { m.range }, "readerTemperature" to text { m.readerTemperature },
            "batteryLevel" to if (level >= 0 && scale > 0) (100 * level / scale).toString() else "",
            "regionCode" to (region?.btRegion?.toInt()?.and(255)?.toString().orEmpty()),
            "regionStartIndex" to (region?.btFrequencyStart?.toInt()?.and(255)?.toString().orEmpty()),
            "regionEndIndex" to (region?.btFrequencyEnd?.toInt()?.and(255)?.toString().orEmpty()),
            "configuredAntenna" to params?.Antenna?.toString().orEmpty(),
            "inventorySession" to params?.Session?.toString().orEmpty(), "qValue" to params?.QValue?.toString().orEmpty(),
            "scanInterval" to params?.Interval?.toString().orEmpty(),
            "memoryWordPointer" to params?.WordPtr?.toString().orEmpty(), "memoryWordLength" to params?.Length?.toString().orEmpty()
        ))
    }
    override fun configure(mode: QueryMode): ReaderInfo {
        val m = checkNotNull(manager)
        m.setQueryMode(mode.sdkValue)
        check(m.queryMode == mode.sdkValue) { "Reader did not confirm query mode ${mode.label}" }
        configuredMode = mode
        val maxPower = if (m.readerType == 80) 26 else 30
        val targetPower = m.outputPower.takeIf { it in 5..maxPower } ?: maxPower
        runCatching { m.setOutputPower(targetPower) }
        runCatching { m.setRange(100) }
        return info()
    }
    override fun power(value: Int): ReaderInfo {
        val m = checkNotNull(manager)
        val max = if (m.readerType == 80) 26 else 30
        require(value in 5..max) { "Power must be 5..$max dBm for this reader" }
        check(m.setOutputPower(value) == 0) { "Reader rejected transmit power" }
        check(m.outputPower == value) { "Power readback did not match" }
        runCatching { m.setRange(100) }
        return info()
    }
    override fun range(value: Int): ReaderInfo {
        val m = checkNotNull(manager)
        require(value in 0..100)
        check(m.setRange(value) == 0) { "Read-range setting is unsupported or was rejected" }
        check(m.range == value) { "Read-range readback did not match" }
        return info()
    }
    override fun start(targetEpc: String?, onTag: (RfidTagEvent) -> Unit, onUnexpectedEnd: () -> Unit) {
        val m = checkNotNull(manager)
        val maxPower = if (m.readerType == 80) 26 else 30
        val targetPower = m.outputPower.takeIf { it in 5..maxPower } ?: maxPower
        runCatching { m.setOutputPower(targetPower) }
        runCatching { m.setRange(100) }
        runCatching { scanSound.start() }
        val emit: (RfidTagEvent) -> Unit = { event ->
            onTag(event)
            runCatching { scanSound.beep() }
        }
        stopping = false
        if (configuredMode == QueryMode.FIND) {
            val target = checkNotNull(targetEpc)
            require(epcProblem(target).isEmpty()) { "Enter a valid raw hex target EPC" }
            probe = probeScope.launch {
                try {
                    while (isActive) {
                        check(m.isConnected) { "Reader disconnected" }
                        val tag = m.findEpc(target)
                        if (tag != null) emit(RfidTagEvent(System.currentTimeMillis(), tag.epcId, tag.memId,
                            tag.rssi.toString(), tag.phase.toString(), tag.antId.toString(), "findEpc"))
                        delay(100)
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (!stopping) onUnexpectedEnd() }
            }
            return
        }
        ended = CompletableDeferred()
        val acknowledgement = ended
        val listener = object : IRfidCallback {
            override fun onInventoryTag(epc: String?, data: String?, rssi: String?) {
                emit(RfidTagEvent(System.currentTimeMillis(), epc, data, rssi))
            }
            override fun onInventoryTagEnd() {
                acknowledgement.complete(Unit)
                if (callback === this && !stopping) onUnexpectedEnd()
            }
        }
        callback = listener
        m.registerCallback(listener)
        check(m.startRead() == 0) { "Reader rejected startRead(); stop/disconnect and reconnect" }
    }
    override suspend fun stop() {
        runCatching { scanSound.stop() }
        val m = manager ?: return
        stopping = true
        probe?.let { it.cancelAndJoin(); probe = null }
        try {
            if (callback != null) {
                check(m.stopInventory() == 0) { "stopInventory failed; reconnect required" }
                check(withTimeoutOrNull(3000) { ended.await(); true } == true) { "Inventory stop acknowledgement timed out; reconnect required" }
            }
        } finally {
            callback?.let { m.unregisterCallback(it) }
            callback = null
        }
    }
    override fun disconnect() {
        runCatching { scanSound.stop() }
        stopping = true
        callback?.let { runCatching { manager?.unregisterCallback(it) } }
        callback = null
        runCatching { sdk.disConnect() }
        runCatching { sdk.power(false) }
        manager = null
    }
}
