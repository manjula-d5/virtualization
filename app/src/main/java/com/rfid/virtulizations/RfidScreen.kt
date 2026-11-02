package com.rfid.virtulizations

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

@Composable
fun RfidScreen(model: RfidViewModel, onAllowStorage: () -> Unit, onExport: (String) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val uploadStatus by model.uploadStatus.collectAsStateWithLifecycle()
    val storageReady by model.storage.ready.collectAsStateWithLifecycle()
    val repo = model.repository
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(state.endedAt) {
        if (state.endedAt != null) android.widget.Toast.makeText(context,
            state.error ?: state.message, android.widget.Toast.LENGTH_LONG).show()
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.scanning) { while (state.scanning) { now = System.currentTimeMillis(); delay(1000) } }
    Scaffold { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("RFID Reader Diagnostics", style = MaterialTheme.typography.headlineMedium)
                Text("XR2  /  LIVE INVENTORY & TXT", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            item {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            item {
                Panel {
                    Text("Session setup", style = MaterialTheme.typography.titleMedium)
                    Text(uploadStatus, style = MaterialTheme.typography.bodySmall)
                    if (!state.scanning && state.endedAt != null) Text(state.message, style = MaterialTheme.typography.bodySmall)
                    if (state.scanning) Text("● SCANNING", color = MaterialTheme.colorScheme.primary)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SelectionDropdown("Zone", when (state.zone) {
                            "BackOffice" -> "Back Office"
                            "Floor" -> "Floor"
                            else -> "Select zone"
                        }, listOf("BackOffice" to "Back Office", "Floor" to "Floor"),
                            enabled = !state.scanning && !state.busy, modifier = Modifier.weight(1f), onSelect = repo::zone)
                        val power = state.reader["transmitPower"].takeIf { it.isNotEmpty() }?.let { "$it dBm" } ?: "Select power"
                        SelectionDropdown("RF Power", power, (5..30).map { it.toString() to "$it dBm" },
                            enabled = state.connected && !state.scanning && !state.busy,
                            modifier = Modifier.weight(1f),
                            optionEnabled = { state.reader["readerType"] != "80" || it.toInt() <= 26 },
                            onSelect = { repo.power(it.toInt()) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { if (storageReady) repo.start() else onAllowStorage() }, enabled = state.connected && !state.scanning && !state.busy && state.zone != null) { Text("Start Scan") }
                        Button(onClick = repo::stop, enabled = state.scanning && !state.busy,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Stop Scan") }
                    }
                    Text("Configured RF band: ${configuredFrequencyBand(state.reader).ifEmpty { "N/A (region ${state.reader["regionCode"].ifEmpty { "unknown" }})" }}", style = MaterialTheme.typography.bodySmall)
                    var diagnostics by remember { mutableStateOf(false) }
                    TextButton(onClick = { diagnostics = !diagnostics }) { Text("Optional phase / antenna diagnostics") }
                    if (diagnostics || state.mode == QueryMode.FIND) {
                        Row {
                            Checkbox(checked = state.mode == QueryMode.FIND, enabled = !state.scanning && !state.busy,
                                onCheckedChange = { repo.mode(if (it) QueryMode.FIND else QueryMode.EPC) })
                            Text("Probe one EPC for raw phase and antenna", style = MaterialTheme.typography.bodySmall)
                        }
                        if (state.mode == QueryMode.FIND) OutlinedTextField(value = state.targetEpc,
                            onValueChange = repo::target, label = { Text("Target EPC (hex)") },
                            enabled = !state.scanning && !state.busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text("A separate targeted read; raw phase is not tag direction. RF band is configuration, not per-read frequency.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Panel {
                    Text("Session statistics", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Stat("Events", state.total.toString()); Stat("Unique EPCs", state.unique.toString()); Stat("TXT rows", state.written.toString())
                    }
                    val elapsed = state.startedAt?.let { ((state.endedAt ?: now).coerceAtLeast(it) - it) / 1000 } ?: 0
                    Text("Elapsed ${elapsed}s  ·  Invalid EPCs ${state.invalid}  ·  Unlogged ${state.unlogged}", style = MaterialTheme.typography.bodySmall)
                    Text("TXT rows = writes accepted by the buffer; a flush/close error means durability is unconfirmed.", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Live events (${state.rows.size}/200)", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = repo::clearDisplay) { Text("Clear Display") }
                }
                if (state.rows.isEmpty()) Text("Waiting for tag events…", Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(state.rows, key = { it.total }) { row ->
                Panel {
                    SelectionContainer { Text(row.event.epc ?: "N/A (missing EPC)", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) }
                    Text("${eventTime(row.event.timestamp)}  ·  #${row.total}", style = MaterialTheme.typography.labelSmall)
                    val rssi = decodeRssi(row.event.rssi, state.reader["module"])
                    Text("RSSI: ${rssi.dbm?.let { "$it dBm" } ?: "N/A"}  ·  EPC reads: ${row.epcCount}", style = MaterialTheme.typography.bodySmall)
                    Text("RSSI raw: ${row.event.rssi ?: "N/A"}", style = MaterialTheme.typography.labelSmall)
                    Text("Handheld tilt: ${row.event.handheldTilt?.let { "${it.degrees}° · ${it.orientation}" } ?: "N/A"} (not tag angle)", style = MaterialTheme.typography.bodySmall)
                    Text("Phase (raw): ${row.event.phase ?: "N/A"}  ·  Antenna: ${row.event.antennaId ?: "N/A"}  ·  Angle: N/A", style = MaterialTheme.typography.bodySmall)
                    Text("${state.mode.label} data: ${row.event.data?.takeIf { it.isNotEmpty() } ?: "N/A"}", style = MaterialTheme.typography.bodySmall)
                    epcProblem(row.event.epc).takeIf { it.isNotEmpty() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@Composable private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
}
@Composable private fun Stat(label: String, value: String) {
    Column { Text(value, style = MaterialTheme.typography.headlineSmall); Text(label, style = MaterialTheme.typography.labelSmall) }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SelectionDropdown(
    label: String, value: String, options: List<Pair<String, String>>, enabled: Boolean,
    modifier: Modifier = Modifier, optionEnabled: (String) -> Boolean = { true }, onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    ExposedDropdownMenuBox(expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it }, modifier = modifier) {
        OutlinedTextField(value = value, onValueChange = {}, readOnly = true,
            label = { Text(label) }, singleLine = true, enabled = enabled,
            textStyle = MaterialTheme.typography.bodySmall,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth())
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 280.dp)) {
            options.forEach { (key, text) ->
                DropdownMenuItem(text = { Text(text) }, enabled = optionEnabled(key),
                    onClick = { expanded = false; onSelect(key) })
            }
        }
    }
}
