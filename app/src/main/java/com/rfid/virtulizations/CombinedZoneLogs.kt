package com.rfid.virtulizations

import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.json.JSONObject

/** Retains closed scan segments until both zones have real observations. */
class CombinedZoneLogs(private val directory: File, private val save: (File) -> String) {
    private val journal get() = File(directory, ".pending-zones")
    fun accept(segment: File): String? {
        check(directory.isDirectory || directory.mkdirs())
        val paths = if (journal.exists()) journal.readLines().filter { it.isNotBlank() }.distinct().toMutableList() else mutableListOf()
        if (segment.absolutePath !in paths) {
            FileOutputStream(journal, true).use { stream ->
                stream.write((segment.absolutePath + "\n").toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            paths.add(segment.absolutePath)
        }
        val zones = mutableSetOf<String>()
        for (path in paths) {
            val file = File(path)
            check(file.isFile) { "Pending zone log is missing: $path" }
            file.forEachLine { line -> if (line.isNotBlank()) zones.add(JSONObject(line).getString("Zone")) }
        }
        if (!zones.containsAll(listOf("Floor", "BackOffice"))) return null
        val combined = File(directory, "RFID_Scan_BothZones_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.txt")
        check(combined.createNewFile())
        val counts = mutableMapOf<String, Long>()
        var total = 0L
        val unique = mutableSetOf<String>()
        FileOutputStream(combined).use { stream ->
            val writer = stream.bufferedWriter(Charsets.UTF_8)
            for (path in paths) File(path).forEachLine { line ->
                if (line.isNotBlank()) {
                    val record = JSONObject(line)
                    val epc = record.optString("epc", "")
                    total++
                    val count = (counts[epc] ?: 0L) + 1
                    counts[epc] = count
                    if (epc.isNotBlank()) unique.add(epc)
                    record.put("epcScanCount", count)
                    record.put("sessionTotalCount", total)
                    record.put("sessionUniqueCount", unique.size)
                    writer.write(record.toString()); writer.write("\r\n")
                }
            }
            writer.flush(); stream.fd.sync()
        }
        val destination = save(combined)
        check(journal.delete()) { "Combined file saved at $destination, but pending state could not be reset" }
        return destination
    }
}
