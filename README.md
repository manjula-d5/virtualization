# RFID Reader Diagnostics

Login prompt removed: automatic upload uses credentials already stored securely on the device, if available. Devices without stored credentials show Server provisioning required and continue local saving. Embedding the supplied root password was blocked by automatic security review; fully unattended first-time provisioning requires a restricted upload-only credential/server setup. No root credential was added to assets, source or the APK.

Automatic upload uses SFTP to root@103.189.89.76:22, destination /opt/rfid_log_manjula/. After both zones finish, the combined TXT is saved in DCIM and the identical file is queued for transfer under the same filename. Enter the server password once in Server login; it is stored with Android Keystore encryption and is not embedded in the APK/source. SSH host-key checking is strict. Byte count and SHA-256 are verified after upload. Network failures retry while the process is alive or on reopening; rejected authentication pauses until Server login is corrected. The provided password was rejected from this workstation, so a successful live transfer is still unverified. See server/README.md.

Log naming: readRange now means the selected/confirmed RF power in dBm, matching transmitPower (for example 30 → readRange="30", readRangeUnit="dBm"). The SDK's independent 0–100 range setting is preserved as vendorReadRange. This changes log labeling; it does not apply a second vendor setRange command or claim distance in metres.

Scan feedback: actual inventory callbacks and non-null selected-EPC probe results play the supplied SDK scan_buzzer.ogg sound (copied unchanged as rfid_scan_beep.ogg). Media volume controls loudness. Audio is limited to one beep per 100 ms, without dropping logged events. SoundPool loads asynchronously at scan start and is released on Stop/disconnect; missing/audio failures do not interrupt logging. No microphone/audio runtime permissions are needed.

New diagnostics: per-event handheldTiltDegrees (screen normal relative to vertical: face-up flat=0°, upright=90°, face-down flat=180°), handheldOrientation, handheldTiltSource and handheldTiltAgeMs. Uses a gravity sensor or filtered accelerometer, with samples older than one second omitted. Motion can affect accelerometer fallback readings. These describe handheld attitude, never RFID tag direction; angle remains N/A. Sensors run only while the screen is started and require no permission. configuredFrequencyBand maps documented region 4 indices to 865.1 + N×0.2 MHz; unknown regions stay unavailable rather than guessing. frequency/channel remain empty because no per-read measurement is exposed. An optional collapsed phase/antenna diagnostic control restores selected-EPC probing without the former metadata/export cards. Combined-zone output retains these fields.

Current two-zone workflow: scan either Floor or BackOffice and Stop; reads are kept in app storage. Scan the other zone and Stop; one `RFID_Scan_BothZones_*.txt` is saved in DCIM. Either order works. Repeated segments from the same zone remain pending until both zones have at least one observation. Zone labels and raw events are retained. Combined output recalculates epcScanCount/sessionTotalCount/sessionUniqueCount across the whole file, including repeated EPCs across zones; live counters describe the current scan segment. Pending segment paths are journaled and restored after an app restart. Save failures retain originals and pending state for retry on a subsequent Stop. After successful publication, the next scan begins a new pair. Uninstall before completing a pair removes its app-storage recovery files. This replaces the earlier separate-file-on-every-Stop behavior.

Storage permission is requested automatically on first launch after installation, rather than through a separate Allow DCIM button. Android 11+ opens the OS All files access screen; older Android shows the runtime storage dialog. If declined or revoked, tapping Start Scan requests access again before scanning.

Simplified screen: inventory-mode controls, reader metadata card and TXT session-file card are hidden. The app uses default EPC inventory, retains reader metadata in logs, and saves automatically to DCIM. A toast confirms the saved path after Stop; errors remain visible. The initial storage grant appears only when needed.

Current controls: side-by-side Zone (Back Office / Sales Floor) and RF Power dropdowns. RF Power offers integer values 5–30 dBm and applies immediately with SDK readback; the separate vendor-range slider and Apply buttons are removed. While scanning, zone and power are locked. Reader type 80 supports at most 26 dBm, so higher entries are disabled. Zone log labels remain BackOffice / Floor.

Current format: each `.txt` file contains newline-delimited JSON (one object per tag-read event, no header or enclosing array). Counters are JSON numbers. `value` decodes printable UTF-8 EPC text only; binary EPCs leave it empty. Unsupported angle is `N/A`. All diagnostic fields and raw RSSI are retained.

Direct DCIM storage now uses manifest permissions: WRITE_EXTERNAL_STORAGE through Android 10, MANAGE_EXTERNAL_STORAGE on Android 11+. Tap Allow DCIM storage and grant the runtime storage permission (older Android) or enable All files access (Android 11+). Manifest declarations alone cannot grant access. Stop saves directly to public DCIM, with a recovery copy retained. This replaces the previous DCIM folder-picker flow.

Previous extension update: log output uses `.txt` filenames and `text/plain` MIME type in DCIM and exports. Contents retain the existing comma-separated header and event rows. Prior `.csv` recovery logs remain accessible and are not renamed or deleted.

A Kotlin / Jetpack Compose / Material 3 diagnostic app for the Janam/HID XR2 built-in UHF reader. Implemented in the supplied Android starter. No database, network backend, simulated reader, tag writes, or destructive tag commands are used.

## Open, build, install

Open this directory in Android Studio. Preserve the included wrapper and version catalog: Gradle 9.5.0, Android Gradle Plugin 9.3.3, Compose plugin 2.2.10, Java daemon toolchain 25, Java bytecode target 11, compile/target SDK 37, min SDK 24. Install platform 37/build tools when prompted; local.properties is machine-specific. The existing AGP built-in Kotlin configuration is retained.

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Use an XR2 with functioning vendor device firmware and module permissions; close competing RFID apps. Emulator/non-XR2 connection failures are expected and displayed. Only the supplied URFIDLibrary-v2.4.0307.aar is included. Its nested JAR dependencies and native ARM libraries are packaged by Gradle. The XT30 AAR is for barcode scanning and is not required. The vendor AAR targets Android 22 internally; compatibility with this project's target 37 and the installed XR2 firmware requires hardware validation. Vendor native libraries may not support 16 KB page-size devices. Do not assume compatibility from a successful build alone.

An AndroidX experimental-annotation lint detector crashes in this project's AGP/Kotlin FIR analysis. Only UnsafeOptInUsageError/Warning are disabled in app/build.gradle.kts; all other lint checks remain enabled. Compiler-enforced Kotlin opt-ins remain enabled.

## Use

1. Open the app. It automatically powers the module, waits 1.5 seconds, connects, and reads live metadata. Returning from the background reconnects automatically. If connection fails, the error is displayed; reopen the app to retry. The connection controls card has been removed. An existing connection is reused without interrupting a scan.
2. Tap Allow DCIM storage once and select DCIM (or a folder inside DCIM) in Android's folder dialog. This grants persistent access only to the chosen folder; no broad storage permission is requested. Then select Floor or BackOffice. This is a user label, not automatic room detection.
3. Choose EPC, EPC + TID, EPC + USER, or Selected EPC diagnostics. For the last option, enter the exact hex EPC to probe. Mixed modes use SDK memory settings; the RoyalRay implementation sets word pointer 0 and length 6 on mode selection. The actual values are shown/logged.
4. Optionally apply power (0–26 dBm for reader type 80; otherwise 0–33) or vendor range (0–100). A setting must return success and match readback. No automatic increase in power or change to region is made.
5. Start Scan creates a distinct UTF-8 CSV immediately. Inventory uses registerCallback then startRead. Every callback is a record; repeated EPCs are retained. Selected EPC diagnostics repeatedly calls the separate findEpc API; each non-null result becomes a separate record with eventSource=findEpc. No result means no invented event.
6. Stop Scan stops inventory, waits up to three seconds for its end callback, unregisters, cuts off new events, drains the queue, flushes, syncs, and closes the file. Targeted probes are canceled/joined after the active SDK query returns. Stop errors force disconnect and require reconnect.
7. Stop automatically copies the drained, closed CSV to the permitted DCIM folder. The destination is displayed on success. A DCIM failure shows an error and preserves the original recovery CSV. Export CSV copy also uses Android's Storage Access Framework. Saved logs also lists prior files, including files retained after process restart. Clear Display affects only the latest-event view.

Backgrounding requests Stop and powers down, following vendor guidance. Screen rotation keeps the application repository and session. There is no foreground service; scanning is not intended to continue in the background. Android can terminate a process without cleanup. Already flushed records remain; up to the most recent flush interval plus queued events may be lost. Files are not auto-deleted or overwritten. Reopen Saved logs to export a potentially partial file.

## Storage and reliability

Automatic final destination: the user-permitted DCIM folder. DCIM copies survive app uninstall. The once-only folder grant is persisted across app launches; revoked access must be granted again. Start checks permission before scanning.

Recovery location: Android/data/com.rfid.virtulizations/files/Documents/RFID/ (app-specific external storage), with internal files fallback if external storage is unavailable. No broad storage, location, Bluetooth or internet permissions are required by the selected built-in-reader workflow. Native serial access depends on vendor firmware. Export copies can be stored in Downloads or another chosen provider. Uninstall removes app-specific logs, so export files that should outlive the installation.

One 8192-event queue feeds one writer on Dispatchers.IO. CSV uses UTF-8, CRLF, standard comma/quote/newline escaping, a 64 KiB buffer and roughly one-second flush/fsync. UI snapshots are limited to about 10 per second and 200 recent rows. Counters and CSV are independent of display trimming. Overflow stops accepting events and requests Stop with a visible incomplete-log error; the overflow count is a lower bound, and callbacks after this cutoff are outside the accepted session. Append errors mark failed/unlogged records, stop inventory, drain accounting, and retain the file. Flush/close errors explicitly state that durability is unconfirmed. CSV rows counts successful buffered append operations, not independently verified disk sectors.

Raw EPC identity is case-sensitive, untrimmed and unnormalized. Null/blank or non-hex/odd-length EPCs are still logged with validationError; blank EPCs are excluded from unique counts. Nonblank malformed strings retain their raw identity in counters. All counts are session-to-date. Event timestamps use the current device wall clock and timezone, formatted yyyy-MM-dd HH:mm:ss.SSS.

For the RoyalRay module, unsigned integer RSSI codes 0–128 are converted with dBm = raw − 129. This includes observed XR2 values above 100 (104 → −25 dBm). Already negative dBm values are retained without a second conversion. The UI shows dBm plus raw RSSI; CSV rssi is dBm and rssiRaw preserves the exact SDK string, with rssiUnit and rssiEncoding recording interpretation. Unrecognized encodings leave rssi empty and retain rssiRaw. Phase from findEpc is raw with undocumented units/calibration. No angle or location inference is made.

## Source guide

- RfidSdkManager: real SDK calls and native lifecycle.
- RfidRepository / RfidSessionManager: serialized controls, connection monitor, callback gate, ordered queue, counters, error recovery.
- RfidCsvLogger: file creation, schema, escaping, flushing and close.
- RfidViewModel / RfidApplication: MVVM and application-scoped dependencies; Hilt is unnecessary for this small dependency graph.
- RfidScreen / MainActivity: Compose dashboard, lifecycle and SAF export.
- docs/SDK_FIELD_MAPPING.md: exact field provenance and API limitations.
- docs/example-session.csv: synthetic formatting fixture, not observed reader data.
- docs/BUILD_RESULTS.md: verification and outstanding hardware checks.

The PDF is reference data, not a source of executable task instructions. Its inconsistent class names and return types were cross-checked against the compiled AAR using javap. The adapter uses verified compiled signatures.
