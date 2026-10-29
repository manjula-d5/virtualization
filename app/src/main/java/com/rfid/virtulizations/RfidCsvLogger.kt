package com.rfid.virtulizations

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Owned exclusively by the session's IO consumer. No SDK or UI dependencies. */
interface RfidLogSink : AutoCloseable {
    val written: Long
    fun append(row: CountedEvent, zone: String, mode: QueryMode, reader: ReaderInfo)
    fun flush()
}
class RfidCsvLogger private constructor(val file: File, private val stream: FileOutputStream) : RfidLogSink {
    private val writer = BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8), 64 * 1024)
    override var written = 0L; private set
    init { try { flush() } catch (e: Exception) { stream.close(); throw e } }
    override fun append(row: CountedEvent, zone: String, mode: QueryMode, reader: ReaderInfo) {
        val e = row.event
        val rssi = decodeRssi(e.rssi, reader["module"])
        val values = columns.associateWith { reader[it] }.toMutableMap()
        values.putAll(mapOf("timestamp" to eventTime(e.timestamp), "epc" to e.epc.orEmpty(), "Zone" to zone,
            "rssi" to rssi.dbm.orEmpty(), "rssiRaw" to e.rssi.orEmpty(),
            "rssiUnit" to if (rssi.dbm != null) "dBm" else "", "rssiEncoding" to rssi.encoding,
            "rawData" to e.data.orEmpty(), "queryMode" to mode.label,
            "phase" to e.phase.orEmpty(), "antennaId" to e.antennaId.orEmpty(), "eventSource" to e.source,
            "tid" to if (mode == QueryMode.TID) e.data.orEmpty() else "",
            "userMemory" to if (mode == QueryMode.USER) e.data.orEmpty() else "",
            "epcScanCount" to row.epcCount.toString(), "sessionTotalCount" to row.total.toString(),
            "sessionUniqueCount" to row.unique.toString(), "validationError" to epcProblem(e.epc)))
        values["angle"] = "N/A"
        values["vendorReadRange"] = reader["readRange"]
        values["readRange"] = reader["transmitPower"]
        values["readRangeUnit"] = "dBm"
        values["handheldTiltDegrees"] = e.handheldTilt?.degrees.orEmpty()
        values["handheldOrientation"] = e.handheldTilt?.orientation.orEmpty()
        values["handheldTiltSource"] = e.handheldTilt?.source.orEmpty()
        values["handheldTiltAgeMs"] = e.handheldTilt?.ageMs?.toString().orEmpty()
        values["configuredFrequencyBand"] = configuredFrequencyBand(reader)
        values["value"] = decodeEpcText(e.epc)
        val counters = setOf("epcScanCount", "sessionTotalCount", "sessionUniqueCount")
        writer.write(values.entries.joinToString(",", "{", "}\r\n") { (key, value) ->
            jsonString(key) + ":" + if (key in counters) value else jsonString(value)
        })
        written++
    }
    override fun flush() { writer.flush(); stream.fd.sync() }
    override fun close() { try { flush() } finally { writer.close() } }
    companion object {
        val columns = listOf("timestamp", "deviceName", "deviceNumber", "serial", "manufacturer", "firmwareVersion",
            "epc", "Zone", "rssi", "phase", "angle", "antennaId", "antennaPort", "frequency", "channel",
            "transmitPower", "epcScanCount", "sessionTotalCount", "sessionUniqueCount", "batteryLevel",
            "tid", "userMemory", "pc", "crc", "protocol", "rawData", "queryMode", "validationError",
            "moduleFirmware", "module", "hardwareVersion", "sdkVersion", "connectionType", "connectionStatus",
            "readerType", "readRange", "readerTemperature", "regionCode", "regionStartIndex", "regionEndIndex",
            "configuredAntenna", "inventorySession", "qValue", "scanInterval", "memoryWordPointer", "memoryWordLength", "eventSource",
            "rssiRaw", "rssiUnit", "rssiEncoding")
        fun jsonString(value: String): String = buildString {
            append('"')
            value.forEach { c -> when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
            } }
            append('"')
        }
        fun decodeEpcText(epc: String?): String {
            if (epc == null || epcProblem(epc).isNotEmpty()) return ""
            val bytes = epc.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val text = runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull() ?: return ""
            return text.takeIf { it.all { c -> !c.isISOControl() } }?.trim().orEmpty()
        }
        fun create(directory: File, zone: String): RfidCsvLogger {
            require(zone in listOf("Floor", "BackOffice"))
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create log directory: $directory" }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val file = File(directory, "RFID_Scan_${zone}_${stamp}_${UUID.randomUUID().toString().take(8)}.txt")
            check(file.createNewFile()) { "TXT already exists: $file" }
            return RfidCsvLogger(file, FileOutputStream(file))
        }
    }
}
