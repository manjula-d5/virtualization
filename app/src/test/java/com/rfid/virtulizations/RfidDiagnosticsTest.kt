package com.rfid.virtulizations

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RfidDiagnosticsTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun loggedReadRangeMatchesSelectedRfPower() {
        val logger = RfidCsvLogger.create(temp.root, "Floor")
        logger.append(SessionCounters().accept(event()), "Floor", QueryMode.EPC,
            ReaderInfo(mapOf("transmitPower" to "30", "readRange" to "5")))
        logger.close()
        val row = org.json.JSONObject(logger.file.readText())
        assertEquals("30", row.getString("readRange"))
        assertEquals("30", row.getString("transmitPower"))
        assertEquals("dBm", row.getString("readRangeUnit"))
        assertEquals("5", row.getString("vendorReadRange"))
    }
    @Test fun handheldTiltUsesGravityAndDoesNotInventTagAngle() {
        assertEquals("0.0", tiltFromGravity(0.0, 0.0, 9.81)!!.degrees)
        assertEquals("FLAT_FACE_UP", tiltFromGravity(0.0, 0.0, 9.81)!!.orientation)
        assertEquals("90.0", tiltFromGravity(0.0, 9.81, 0.0)!!.degrees)
        assertEquals("FLAT_FACE_DOWN", tiltFromGravity(0.0, 0.0, -9.81)!!.orientation)
        assertNull(tiltFromGravity(0.0, 0.0, 0.0))
        assertNull(tiltFromGravity(Double.NaN, 0.0, 9.81))
        val logger = RfidCsvLogger.create(temp.root, "Floor")
        logger.append(SessionCounters().accept(event().copy(handheldTilt = HandheldTilt("45.0", "TILTED", "gravitySensor", 15))),
            "Floor", QueryMode.EPC, ReaderInfo(mapOf("regionCode" to "4", "regionStartIndex" to "0", "regionEndIndex" to "14")))
        logger.close()
        val row = org.json.JSONObject(logger.file.readText())
        assertEquals("45.0", row.getString("handheldTiltDegrees"))
        assertEquals("N/A", row.getString("angle"))
        assertEquals("", row.getString("frequency"))
        assertEquals("865.1–867.9 MHz", row.getString("configuredFrequencyBand"))
        assertEquals("", configuredFrequencyBand(ReaderInfo(mapOf("regionCode" to "99"))))
    }
    @Test fun combinedZonesSaveOneFileInEitherOrderAndContinueCounters() {
        for (order in listOf(listOf("Floor", "BackOffice"), listOf("BackOffice", "Floor"))) {
            val directory = temp.newFolder()
            val saved = mutableListOf<File>()
            fun segment(zone: String): File {
                val log = RfidCsvLogger.create(directory, zone)
                val counters = SessionCounters()
                repeat(2) { log.append(counters.accept(event("AABB")), zone, QueryMode.EPC, ReaderInfo()) }
                log.close(); return log.file
            }
            val publisher = CombinedZoneLogs(directory) { saved.add(it); "DCIM/${it.name}" }
            assertNull(publisher.accept(segment(order[0])))
            assertTrue(saved.isEmpty())
            // Recreate the publisher to verify pending segments survive process recreation.
            val restored = CombinedZoneLogs(directory) { saved.add(it); "DCIM/${it.name}" }
            assertNotNull(restored.accept(segment(order[1])))
            assertEquals(1, saved.size)
            val rows = saved.single().readLines().map { org.json.JSONObject(it) }
            assertEquals(listOf(order[0], order[0], order[1], order[1]), rows.map { it.getString("Zone") })
            assertEquals(listOf(1L, 2L, 3L, 4L), rows.map { it.getLong("sessionTotalCount") })
            assertEquals(4L, rows.last().getLong("epcScanCount"))
            assertEquals(1, rows.last().getInt("sessionUniqueCount"))
            assertFalse(File(directory, ".pending-zones").exists())
            assertNull(restored.accept(segment(order[0])))
            assertEquals(1, saved.size)
        }
    }
    @Test fun combinedSaveFailureKeepsPendingSegmentsForRetry() {
        val directory = temp.newFolder()
        fun segment(zone: String): File {
            val log = RfidCsvLogger.create(directory, zone)
            log.append(SessionCounters().accept(event()), zone, QueryMode.EPC, ReaderInfo())
            log.close(); return log.file
        }
        val first = segment("Floor"); val second = segment("BackOffice")
        val failed = CombinedZoneLogs(directory) { throw java.io.IOException("disk full") }
        assertNull(failed.accept(first))
        assertThrows(java.io.IOException::class.java) { failed.accept(second) }
        assertTrue(File(directory, ".pending-zones").exists())
        assertTrue(first.exists()); assertTrue(second.exists())
        var count = 0
        val retry = CombinedZoneLogs(directory) { count = it.readLines().size; it.absolutePath }
        assertNotNull(retry.accept(second))
        assertEquals(2, count)
    }
    private fun event(epc: String? = "Aa00", data: String? = null, rssi: String? = "83") = RfidTagEvent(1793290500194, epc, data, rssi)

    @Test fun csvEscapesCommasQuotesNewlinesAndUtf8WithoutChangingRawEpc() {
        val logger = RfidCsvLogger.create(temp.root, "Floor")
        val row = SessionCounters().accept(event("aB,\"\n", "标签\r\nDATA", "-47.80"))
        logger.append(row, "Floor", QueryMode.TID, ReaderInfo())
        logger.close()
        val text = logger.file.readText(Charsets.UTF_8)
        assertTrue(text.startsWith("{"))
        assertEquals("aB,\"\n", org.json.JSONObject(text).getString("epc"))
        assertEquals("标签\r\nDATA", org.json.JSONObject(text).getString("rawData"))
        assertTrue(text.contains("Malformed hex EPC (raw preserved)"))
        assertEquals("aB,\"\n", row.event.epc)
        assertTrue(text.endsWith("\r\n"))
    }
    @Test fun countersPreserveEveryRepeatAndDistinguishRawCase() {
        val counters = SessionCounters()
        val rows = listOf("AA", "AA", "BB", "AA", "BB").map { counters.accept(event(it)) }
        assertEquals(listOf(1L, 2L, 1L, 3L, 2L), rows.map { it.epcCount })
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), rows.map { it.total })
        assertEquals(listOf(1, 1, 2, 2, 2), rows.map { it.unique })
        counters.accept(event(null)); assertEquals(2, counters.unique)
        counters.accept(event("aa")); assertEquals(3, counters.unique)
    }
    @Test fun filesNeverOverwriteAndUnsupportedMeasurementsStayEmpty() {
        val a = RfidCsvLogger.create(temp.root, "BackOffice")
        val b = RfidCsvLogger.create(temp.root, "BackOffice")
        assertNotEquals(a.file, b.file)
        a.append(SessionCounters().accept(event()), "BackOffice", QueryMode.EPC, ReaderInfo())
        a.close(); b.close()
        val fields = org.json.JSONObject(a.file.readLines()[0])
        listOf("phase", "antennaId", "frequency", "channel", "pc", "crc", "protocol", "tid").forEach {
            assertEquals("", fields.getString(it))
        }
        assertEquals("Aa00", fields.getString("epc"))
    }
    @Test fun fileCreationFailureIsReported() {
        val blocker = temp.newFile("not-a-directory")
        assertThrows(IllegalStateException::class.java) { RfidCsvLogger.create(File(blocker, "RFID"), "Floor") }
    }
    @Test fun selectedEpcDiagnosticsPreserveRawPhaseAndAntennaWithoutInventingAngle() {
        val logger = RfidCsvLogger.create(temp.root, "Floor")
        logger.append(SessionCounters().accept(event().copy(phase = "1234", antennaId = "2", source = "findEpc")),
            "Floor", QueryMode.FIND, ReaderInfo())
        logger.close()
        val fields = org.json.JSONObject(logger.file.readLines()[0])
        assertEquals("1234", fields.getString("phase"))
        assertEquals("2", fields.getString("antennaId"))
        assertEquals("N/A", fields.getString("angle"))
        assertEquals("findEpc", fields.getString("eventSource"))
    }
    @Test fun royalRayRssiConvertsObservedValuesAbove100AndPreservesSignedDbm() {
        listOf(101 to "-28", 102 to "-27", 103 to "-26", 104 to "-25", 105 to "-24", 98 to "-31").forEach { (raw, dbm) ->
            assertEquals(dbm, decodeRssi(raw.toString(), "royalRay").dbm)
        }
        assertEquals("-47.8", decodeRssi("-47.80", "royalRay").dbm)
        assertEquals("signed-dBm", decodeRssi("-47.80", "royalRay").encoding)
        listOf(null, "", "bad", "NaN", "Infinity", "129", "255", "103.5").forEach {
            assertNull(decodeRssi(it, "royalRay").dbm)
        }
        assertNull(decodeRssi("103", "unknown").dbm)
    }
    @Test fun csvContainsDbmAndOriginalRssiWithExplicitEncoding() {
        val logger = RfidCsvLogger.create(temp.root, "BackOffice")
        logger.append(SessionCounters().accept(event(rssi = "104")), "BackOffice", QueryMode.EPC,
            ReaderInfo(mapOf("module" to "royalRay")))
        logger.close()
        val fields = org.json.JSONObject(logger.file.readLines()[0])
        assertEquals("-25", fields.getString("rssi"))
        assertEquals("104", fields.getString("rssiRaw"))
        assertEquals("dBm", fields.getString("rssiUnit"))
        assertEquals("royalRay-offset129", fields.getString("rssiEncoding"))
    }
    @Test fun writeErrorStopsAcceptanceDrainsQueueAndClosesSink() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            var closed = false; var error: String? = null; var unlogged = 0L
            val sink = object : RfidLogSink {
                override val written = 0L
                override fun append(row: CountedEvent, zone: String, mode: QueryMode, reader: ReaderInfo) { throw java.io.IOException("disk full") }
                override fun flush() {}
                override fun close() { closed = true }
            }
            val session = RfidSessionManager(scope, sink, "Floor", QueryMode.EPC, ReaderInfo(),
                { _, _, _, lost, _, _ -> unlogged = lost }, { error = it })
            session.offer(event())
            session.finish()
            assertTrue(closed); assertEquals(1L, unlogged)
            assertTrue(error!!.contains("disk full"))
        } finally { scope.cancel() }
    }
    @Test fun closeErrorNeverReportsDurabilityAsSuccessful() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            var error: String? = null
            val sink = object : RfidLogSink {
                override val written = 0L
                override fun append(row: CountedEvent, zone: String, mode: QueryMode, reader: ReaderInfo) {}
                override fun flush() {}
                override fun close() { throw java.io.IOException("flush failed") }
            }
            val session = RfidSessionManager(scope, sink, "Floor", QueryMode.EPC, ReaderInfo(), { _, _, _, _, _, _ -> }, { error = it })
            session.finish()
            assertTrue(error!!.contains("Durability is unconfirmed"))
            assertNotNull(session.failure)
        } finally { scope.cancel() }
    }
    @Test(timeout = 15000) fun queueOverloadIsExplicitAndAcceptedEventsAreStillDrained() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val unblock = java.util.concurrent.CountDownLatch(1)
        try {
            val logger = RfidCsvLogger.create(temp.root, "Floor")
            var error: String? = null; var unlogged = 0L; var total = 0L
            // Hold one append in flight, then fill the bounded queue deterministically.
            val entered = java.util.concurrent.CountDownLatch(1)
            val sink = object : RfidLogSink {
                override val written get() = logger.written
                override fun append(row: CountedEvent, zone: String, mode: QueryMode, reader: ReaderInfo) {
                    entered.countDown(); unblock.await(); logger.append(row, zone, mode, reader)
                }
                override fun flush() = logger.flush()
                override fun close() = logger.close()
            }
            val session = RfidSessionManager(scope, sink, "Floor", QueryMode.EPC, ReaderInfo(),
                { t, _, _, lost, _, _ -> total = t; unlogged = lost }, { error = it })
            session.offer(event())
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            repeat(8193) { session.offer(event()) }
            unblock.countDown()
            session.finish()
            assertTrue(error!!.contains("8192"))
            assertEquals(1L, unlogged); assertEquals(8194L, total)
            assertEquals(8193L, logger.written)
            assertEquals(8193, logger.file.readLines().size)
        } finally { unblock.countDown(); scope.cancel() }
    }
    @Test fun sessionDrainsQueueAndClearOnlyRemovesDisplay() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val logger = RfidCsvLogger.create(temp.root, "Floor")
            var total = 0L; var unique = 0; var rows = emptyList<CountedEvent>()
            val session = RfidSessionManager(scope, logger, "Floor", QueryMode.EPC, ReaderInfo(),
                { t, u, _, _, _, r -> total = t; unique = u; rows = r }, { error(it) })
            repeat(1000) { session.offer(event(if (it % 2 == 0) "AA" else "BB")) }
            session.finish()
            assertEquals(1000L, total); assertEquals(2, unique)
            assertEquals(200, rows.size)
            assertEquals(1000, logger.file.readLines().size)
            session.offer(event("CC"))
            assertEquals(1000, logger.file.readLines().size)
        } finally { scope.cancel() }
    }
    private class Reader : RfidReader {
        @Volatile var connected = false
        var starts = 0; var stops = 0
        var throwOnStart = false; var throwOnStop = false
        var onTag: ((RfidTagEvent) -> Unit)? = null
        override fun isConnected() = connected
        override suspend fun connect(): ReaderInfo { connected = true; return info() }
        override fun info() = ReaderInfo(mapOf("deviceName" to "TEST ONLY"))
        override fun configure(mode: QueryMode) = info()
        override fun power(value: Int) = info()
        override fun range(value: Int) = info()
        override fun start(targetEpc: String?, onTag: (RfidTagEvent) -> Unit, onUnexpectedEnd: () -> Unit) {
            starts++; this.onTag = onTag
            if (throwOnStart) error("start failure")
        }
        override suspend fun stop() {
            stops++
            onTag?.invoke(RfidTagEvent(0, "CC", null, null)) // An event in flight during stop.
            if (throwOnStop) error("stop failure")
        }
        override fun disconnect() { connected = false }
    }
    private suspend fun RfidRepository.await(predicate: (RfidUiState) -> Boolean): RfidUiState =
        withTimeout(7000) { state.first(predicate) }

    @Test fun stopPublishesCompleteCsvAfterDrainAndReportsDestination() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            var publishedRows = 0
            val reader = Reader()
            val repo = RfidRepository(reader, temp.root, scope, publishFile = { file ->
                publishedRows = file.readLines().size
                "DCIM/${file.name}"
            })
            repo.connect(); repo.await { it.connected && !it.busy }
            repo.zone("Floor"); repo.start(); repo.await { it.scanning && !it.busy }
            repeat(100) { reader.onTag?.invoke(event()) }
            repo.stop(); val done = repo.await { !it.scanning && !it.busy && it.endedAt != null }
            assertEquals(101, publishedRows)
            assertTrue(done.savedLocation!!.startsWith("DCIM/"))
            assertNull(done.error)
        } finally { scope.cancel() }
    }
    @Test fun dcimFailureRetainsRecoveryCsvAndReportsError() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val reader = Reader()
            val repo = RfidRepository(reader, temp.root, scope, publishFile = { throw java.io.IOException("permission revoked") })
            repo.connect(); repo.await { it.connected && !it.busy }
            repo.zone("Floor"); repo.start(); repo.await { it.scanning && !it.busy }
            reader.onTag?.invoke(event())
            repo.stop(); val done = repo.await { !it.scanning && !it.busy && it.endedAt != null }
            assertTrue(done.error!!.contains("DCIM save failed"))
            assertNull(done.savedLocation)
            assertEquals(2, File(done.filePath!!).readLines().size)
        } finally { scope.cancel() }
    }

    @Test fun repositoryRequiresZonePreventsDuplicateStartAndDrainsStop() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val reader = Reader(); val repo = RfidRepository(reader, temp.root, scope)
            repo.connect(); repo.await { it.connected && !it.busy }
            repo.start(); repo.await { !it.busy && it.error?.contains("Select") == true }
            assertEquals(0, reader.starts)
            repo.zone("Floor"); repo.start(); repo.await { it.scanning && !it.busy }
            repo.start(); delay(100)
            assertEquals(1, reader.starts)
            repeat(100) { reader.onTag?.invoke(event()) }
            repo.clearDisplay()
            repo.zone("BackOffice"); assertEquals("Floor", repo.state.value.zone)
            repo.stop(); val done = repo.await { !it.scanning && !it.busy && it.endedAt != null }
            assertEquals(101L, done.total); assertEquals(101L, done.written); assertEquals(2, done.unique)
            assertEquals(101, File(done.filePath!!).readLines().size)
            reader.onTag?.invoke(event("DD")); delay(50); assertEquals(101L, repo.state.value.total)
            repo.clearDisplay(); assertEquals(101L, repo.state.value.total)
            assertTrue(repo.state.value.rows.isEmpty())
            repo.zone("BackOffice"); repo.start(); repo.await { it.scanning && !it.busy }
            assertEquals(0L, repo.state.value.total)
            repo.stop(); repo.await { !it.scanning && !it.busy }
            assertEquals(2, temp.root.listFiles()!!.size)
        } finally { scope.cancel() }
    }
    @Test fun connectionLossClosesCsvAndRequiresReconnect() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val reader = Reader(); val repo = RfidRepository(reader, temp.root, scope)
            repo.connect(); repo.await { it.connected && !it.busy }
            repo.zone("Floor"); repo.start(); repo.await { it.scanning && !it.busy }
            repeat(50) { reader.onTag?.invoke(event()) }
            reader.connected = false
            val done = repo.await { !it.connected && !it.scanning && it.error?.contains("lost") == true }
            assertEquals(51L, done.written)
            assertEquals(51, File(done.filePath!!).readLines().size)
        } finally { scope.cancel() }
    }
    @Test fun bothZonesScanInEitherOrderWithTheSameEpcRetainedInEachZone() = runBlocking {
        for (order in listOf(listOf("BackOffice", "Floor"), listOf("Floor", "BackOffice"))) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val reader = Reader()
                val destination = temp.newFolder()
                val published = mutableListOf<File>()
                val repo = RfidRepository(reader, destination, scope, publishFile = {
                    published.add(it); it.absolutePath
                })
                repo.connect(); repo.await { it.connected && !it.busy }
                for (zone in order) {
                    repo.zone(zone); repo.start(); repo.await { it.scanning && !it.busy }
                    repeat(3) { reader.onTag?.invoke(event("AABB")) }
                    repo.stop(); val done = repo.await { !it.scanning && !it.busy && it.endedAt != null }
                    assertTrue(done.connected)
                    assertNull(done.error)
                    val records = File(done.filePath!!).readLines().map { org.json.JSONObject(it) }
                    assertEquals(4, records.size) // Includes the reader's in-flight stop event.
                    assertTrue(records.all { it.getString("Zone") == zone })
                    assertEquals(3, records.count { it.getString("epc") == "AABB" })
                }
                assertEquals(2, reader.starts)
                assertEquals(2, published.size)
                assertNotEquals(published[0], published[1])
            } finally { scope.cancel() }
        }
    }
    @Test fun startAndStopFailuresRetainCsvAndExposeErrors() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val reader = Reader(); val repo = RfidRepository(reader, temp.root, scope)
            repo.connect(); repo.await { it.connected && !it.busy }
            repo.zone("Floor"); reader.throwOnStart = true
            repo.start(); val failed = repo.await { !it.busy && it.error == "start failure" }
            assertFalse(failed.scanning); assertTrue(File(failed.filePath!!).exists())
            reader.throwOnStart = false; reader.throwOnStop = true
            repo.start(); repo.await { it.scanning && !it.busy }
            reader.onTag?.invoke(event())
            repo.stop(); val stopped = repo.await { !it.busy && !it.scanning && it.error == "stop failure" }
            assertFalse(stopped.connected); assertEquals(2L, stopped.written)
        } finally { scope.cancel() }
    }
    @Test fun backgroundSavesAndDisconnects() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val reader = Reader(); val repo = RfidRepository(reader, temp.root, scope)
            repo.connect(); repo.await { it.connected && !it.busy }
            repo.zone("Floor"); repo.start(); repo.await { it.scanning && !it.busy }
            reader.onTag?.invoke(event()); repo.foreground(false)
            val done = repo.await { !it.connected && !it.scanning && !it.busy }
            assertEquals(2L, done.written); assertTrue(File(done.filePath!!).readText().contains("Aa00"))
        } finally { scope.cancel() }
    }
}
