package com.rfid.virtulizations

import java.io.File
import java.io.FileOutputStream
import java.util.UUID
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
        val pendingPaths = if (journal.exists()) journal.readLines().filter { it.isNotBlank() }.distinct().toMutableList() else mutableListOf()
        if (segment.absolutePath !in pendingPaths) {
            FileOutputStream(journal, true).use { stream ->
                stream.write((segment.absolutePath + "\n").toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            pendingPaths.add(segment.absolutePath)
        }
        fun powerOf(file: File): Int? {
            val powers = mutableSetOf<Int?>()
            file.forEachLine { line -> if (line.isNotBlank()) {
                powers.add(JSONObject(line).optString("transmitPower").toIntOrNull())
            } }
            check(powers.size <= 1) { "A scan segment contains multiple RF powers: ${file.name}" }
            return powers.firstOrNull()
        }
        val selectedPower = powerOf(segment)
        val paths = pendingPaths.filter { path ->
            val file = File(path)
            check(file.isFile) { "Pending zone log is missing: $path" }
            powerOf(file) == selectedPower
        }
        val zones = mutableSetOf<String>()
        for (path in paths) {
            val file = File(path)
            check(file.isFile) { "Pending zone log is missing: $path" }
            file.forEachLine { line -> if (line.isNotBlank()) {
                val record = JSONObject(line)
                zones.add(record.getString("Zone"))
            } }
        }
        if (!zones.containsAll(listOf("Floor", "BackOffice"))) return null
        val powerLabel = selectedPower?.let { "${it}dbm" } ?: "unknown"
        val batchId = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val combined = File(directory, "RFID_Scan_BothZones_${powerLabel}_$batchId.txt")
        check(combined.createNewFile()) { "Combined file already exists: $combined" }
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
        val remaining = pendingPaths.filter { it !in paths }
        if (remaining.isEmpty()) {
            check(journal.delete()) { "Combined file saved at $destination, but pending state could not be reset" }
        } else {
            val staged = File(directory, ".pending-zones-next")
            FileOutputStream(staged).use { stream ->
                stream.write((remaining.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            java.nio.file.Files.move(staged.toPath(), journal.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        return destination
    }
}
