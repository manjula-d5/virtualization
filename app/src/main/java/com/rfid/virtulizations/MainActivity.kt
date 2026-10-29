package com.rfid.virtulizations

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.widget.Toast
import android.provider.DocumentsContract
import com.rfid.virtulizations.ui.theme.VirtulizationsTheme

class MainActivity : ComponentActivity() {
    private val model: RfidViewModel by viewModels()
    private var exportSource: String? = null
    private val legacyStoragePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.storage.refreshPermission()
    }
    private val storageSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.storage.refreshPermission()
    }
    private fun requestStorage() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.parse("package:$packageName"))
            runCatching { storageSettings.launch(intent) }.onFailure {
                storageSettings.launch(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else legacyStoragePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val path = exportSource
        if (uri != null && path != null) lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                val output = checkNotNull(contentResolver.openOutputStream(uri)) { "Cannot open export destination" }
                output.use { target -> File(path).inputStream().use { it.copyTo(target) } }
            } }
            Toast.makeText(this@MainActivity, result.exceptionOrNull()?.let { "Export failed: ${it.message}" } ?: "TXT exported", Toast.LENGTH_LONG).show()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportSource = savedInstanceState?.getString("exportSource")
        val permissionPreferences = getSharedPreferences("permission_prompts", MODE_PRIVATE)
        if (savedInstanceState == null && !model.storage.ready.value &&
            !permissionPreferences.getBoolean("storage_requested", false)) {
            permissionPreferences.edit().putBoolean("storage_requested", true).apply()
            requestStorage()
        }
        enableEdgeToEdge()
        setContent { VirtulizationsTheme { RfidScreen(model, onAllowStorage = {
            requestStorage()
        }, onExport = { path ->
            exportSource = path
            export.launch(File(path).name)
        }) } }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("exportSource", exportSource)
        super.onSaveInstanceState(outState)
    }
    override fun onStart() {
        super.onStart()
        model.storage.refreshPermission()
        (application as RfidApplication).tiltSensor.start()
        model.repository.foreground(true)
        model.repository.connect()
    }
    override fun onStop() {
        (application as RfidApplication).tiltSensor.stop()
        if (!isChangingConfigurations) model.repository.foreground(false)
        super.onStop()
    }
}
