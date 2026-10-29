package com.rfid.virtulizations

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Direct public DCIM output; runtime grant is required in addition to the manifest. */
class DcimLogStorage(private val context: Context) {
    private fun permitted(): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    private val mutableReady = MutableStateFlow(permitted())
    val ready = mutableReady.asStateFlow()
    fun refreshPermission() { mutableReady.value = permitted() }
    fun checkPermission() {
        refreshPermission()
        check(mutableReady.value) { "Allow storage access before scanning" }
    }
    @Suppress("DEPRECATION")
    fun save(file: File): String {
        checkPermission()
        val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create DCIM folder" }
        val target = File(directory, file.name)
        check(target.createNewFile()) { "Log already exists in DCIM: ${target.name}" }
        java.io.FileOutputStream(target).use { output ->
            file.inputStream().use { it.copyTo(output) }
            output.flush()
            output.fd.sync()
        }
        android.media.MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf("text/plain"), null)
        return target.absolutePath
    }
}
