package com.rfid.virtulizations

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.math.BigDecimal

data class RssiReading(val dbm: String?, val encoding: String)

/** RoyalRay reports an unsigned strength code, not a signed dBm measurement. */
fun decodeRssi(raw: String?, module: String): RssiReading {
    val value = raw?.trim()?.toDoubleOrNull()
        ?.takeIf { it.isFinite() } ?: return RssiReading(null, "unrecognized")
    fun number(value: Double) = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    if (value < 0 && value >= -129) return RssiReading(number(value), "signed-dBm")
    if (module.equals("royalRay", ignoreCase = true) && value in 0.0..128.0 && value % 1.0 == 0.0) {
        return RssiReading(number(value - 129.0), "royalRay-offset129")
    }
    return RssiReading(null, "unrecognized")
}

enum class QueryMode(val sdkValue: Int, val label: String) {
    EPC(0, "EPC"), TID(1, "EPC + TID"), USER(2, "EPC + USER"), FIND(0, "Selected EPC diagnostics")
}

data class ReaderInfo(val fields: Map<String, String> = emptyMap()) {
    operator fun get(key: String): String = fields[key].orEmpty()
}

data class RfidTagEvent(val timestamp: Long, val epc: String?, val data: String?, val rssi: String?,
    val phase: String? = null, val antennaId: String? = null, val source: String = "inventoryCallback",
    val handheldTilt: HandheldTilt? = null)
interface RfidReader {
    fun isConnected(): Boolean
    suspend fun connect(): ReaderInfo
    fun info(): ReaderInfo
    fun configure(mode: QueryMode): ReaderInfo
    fun power(value: Int): ReaderInfo
    fun range(value: Int): ReaderInfo
    fun start(targetEpc: String?, onTag: (RfidTagEvent) -> Unit, onUnexpectedEnd: () -> Unit)
    suspend fun stop()
    fun disconnect()
}
data class CountedEvent(val event: RfidTagEvent, val epcCount: Long, val total: Long, val unique: Int)

class SessionCounters {
    private val counts = mutableMapOf<String?, Long>()
    var total = 0L; private set
    var unique = 0; private set
    fun accept(event: RfidTagEvent): CountedEvent {
        total++
        if (!event.epc.isNullOrBlank() && !counts.containsKey(event.epc)) unique++
        val count = (counts[event.epc] ?: 0L) + 1
        counts[event.epc] = count
        return CountedEvent(event, count, total, unique)
    }
}

fun eventTime(time: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(time))
fun epcProblem(epc: String?): String = when {
    epc.isNullOrBlank() -> "Missing EPC"
    epc.length % 2 != 0 || epc.any { it !in "0123456789abcdefABCDEF" } -> "Malformed hex EPC (raw preserved)"
    else -> ""
}

data class RfidUiState(
    val connected: Boolean = false, val busy: Boolean = false, val scanning: Boolean = false,
    val reader: ReaderInfo = ReaderInfo(), val zone: String? = null, val mode: QueryMode = QueryMode.EPC,
    val total: Long = 0, val unique: Int = 0, val written: Long = 0, val unlogged: Long = 0,
    val invalid: Long = 0, val rows: List<CountedEvent> = emptyList(), val filePath: String? = null,
    val message: String = "Connect the XR2 reader to begin.", val error: String? = null,
    val startedAt: Long? = null, val endedAt: Long? = null, val savedFiles: List<String> = emptyList(),
    val targetEpc: String = "", val savedLocation: String? = null
)
