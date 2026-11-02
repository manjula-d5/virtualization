package com.rfid.virtulizations

import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

/** Retains closed scan segments until both zones have real observations. */
class CombinedZoneLogs(
    private val directory: File,
    private val saveZone: (File) -> Unit = {},
    private val save: (File) -> String
) {
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
        val powers = sortedSetOf<Int>()
        for (path in paths) {
            val file = File(path)
            check(file.isFile) { "Pending zone log is missing: $path" }
            file.forEachLine { line -> if (line.isNotBlank()) {
                val record = JSONObject(line)
                zones.add(record.getString("Zone"))
                record.optString("transmitPower").toIntOrNull()?.let { powers.add(it) }
            } }
        }
        if (!zones.containsAll(listOf("Floor", "BackOffice"))) return null
        val powerLabel = if (powers.isEmpty()) "unknown" else powers.joinToString("-") + "dbm"
        val baseName = "RFID_Scan_BothZones_$powerLabel"
        var combined = File(directory, "$baseName.txt")
        var suffix = 2
        while (!combined.createNewFile()) {
            combined = File(directory, "${baseName}_${suffix++}.txt")
        }
        FileOutputStream(combined).use { stream ->
            val writer = stream.bufferedWriter(Charsets.UTF_8)
            for ((index, path) in paths.withIndex()) {
                val counts = mutableMapOf<String, Long>()
                var total = 0L
                val unique = mutableSetOf<String>()
                File(path).forEachLine { line ->
                    if (line.isNotBlank()) {
                        val record = JSONObject(line)
                        record.put("sessionNumber", index + 1)
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
            }
            writer.flush(); stream.fd.sync()
        }
        for (zone in listOf("BackOffice", "Floor")) {
            val zoneFile = File(directory, combined.name.replace("BothZones", zone))
            check(zoneFile.createNewFile()) { "Zone file already exists: $zoneFile" }
            FileOutputStream(zoneFile).use { stream ->
                val writer = stream.bufferedWriter(Charsets.UTF_8)
                combined.forEachLine { line ->
                    if (JSONObject(line).getString("Zone") == zone) {
                        writer.write(line); writer.write("\r\n")
                    }
                }
                writer.flush(); stream.fd.sync()
            }
            saveZone(zoneFile)
        }
        val destination = save(combined)
        check(journal.delete()) { "Combined file saved at $destination, but pending state could not be reset" }
        return destination
    }
}
