package com.rfid.virtulizations

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import java.io.File

class RfidApplication : Application() {
    val dcimStorage by lazy { DcimLogStorage(this) }
    val tiltSensor by lazy { HandheldTiltSensor(this) }
    val repository by lazy {
        val directory = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir, "RFID")
        val combined = CombinedZoneLogs(directory, dcimStorage::save)
        RfidRepository(RfidSdkManager(this), directory,
            publishFile = combined::accept, checkStorage = dcimStorage::checkPermission,
            enrichEvent = { it.copy(handheldTilt = tiltSensor.snapshot()) })
    }
}
class RfidViewModel(application: Application) : AndroidViewModel(application) {
    val repository = (application as RfidApplication).repository
    val state = repository.state
    val storage = (application as RfidApplication).dcimStorage
}
