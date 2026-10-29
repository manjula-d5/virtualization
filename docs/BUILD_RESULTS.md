# Build and verification results

Verified 2026-10-29 in the supplied Windows workspace.

## Completed

- `gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`: BUILD SUCCESSFUL.
- 18 JVM tests: 17 diagnostic regression tests plus the original starter test. Zero failures/errors.
- Diagnostic tests cover exact raw EPC preservation, UTF-8/CSV quotes/comma/newline escaping, repeated reads, case-sensitive counts, missing EPC handling, unique files, optional empty fields, selected-EPC raw phase/antenna, queue drain, 200-row UI bound, zone requirement/locking, duplicate Start prevention, Stop in-flight events, late callback exclusion, new-session reset, storage creation failure, write/close failures, deterministic queue overload, connection loss, failed Start/Stop, and background shutdown.
- Example CSV independently parsed and checked with Python csv: 50 columns, 3 synthetic rows, repeated counts retained, angle/phase blank for continuous inventory.
- `apksigner verify --print-certs`: successful; APK is signed with the local Android debug certificate.
- APK size: 11,793,821 bytes. Vendor native libraries are present for arm64-v8a, armeabi-v7a and armeabi. AndroidX also ships x86 UI libraries; the RFID vendor runtime does not support x86.
- No database APIs/dependencies were added. Scan records persist only in CSV.
- Actual SDK signatures and key method bytecode verified with javap. No fabricated APIs or production mock reader are present. Test doubles exist only in the unit-test source set.

## Lint and build notes

Lint completed with 0 errors and 17 warnings: dependency-update notices, starter unused resources, redundant starter activity label, requested Android identifier usage, and inline dependencies rather than version-catalog entries. Existing toolchain/dependency versions were deliberately preserved where possible.

The AndroidX ExperimentalDetector crashed in the initial lint run with `Unexpected owner function: null` in Kotlin FIR analysis. Its two checks (`UnsafeOptInUsageError` / `UnsafeOptInUsageWarning`) are narrowly disabled in app/build.gradle.kts. All other checks run. Kotlin compiler opt-in checks remain active.

Native symbol stripping tools could not strip some supplied libraries, so Gradle packages them unchanged. No native binaries were patched. Target SDK 37 compatibility and native page-size compatibility are not proven by compilation.

## Not verified on hardware

ADB server startup failed in this environment; no successful device/emulator run or UI screenshot is claimed. Build-time tests use a test-only RfidReader implementation and injected failing CSV sinks. They exercise application logic, not RF behavior or native firmware.

On the actual XR2, verify:

1. Install APK; connect with no competing RFID app; check model/serial/firmware and native serial permissions.
2. Scan repeated physical tags; compare raw callback RSSI with vendor tooling. Inspect live counts and exported per-event rows.
3. Exercise EPC+TID and EPC+USER with compatible tags and confirm configured word pointer/length and returned memory.
4. In Selected EPC diagnostics, verify findEpc returns genuine phase/antenna for the installed firmware. Raw phase units and default/sentinel meanings need vendor confirmation. No angle is calculated.
5. Verify setOutputPower and setRange acceptance/readback; measure actual RF behavior separately. Existing region is retained.
6. Check rapid Start/Stop, actual end acknowledgement timing, disconnect/power loss, screen rotation, backgrounding, process termination and saved-file recovery.
7. Exercise sustained maximum reader rate, real disk exhaustion and SAF export/provider failures. Overflow or I/O failure must produce a visible incomplete-log error.
8. Check layout at XR2 screen/font sizes and confirm OS/firmware/target-SDK compatibility.

Synthetic example data is a formatting fixture only. It is not hardware evidence.

## RSSI correction

User-supplied XR2 CSV and screenshots show module royalRay, readerType 113, firmware 2.15, with raw RSSI 101–105. The app now converts RoyalRay raw strength to dBm using raw − 129, including codes above 100, while retaining the SDK string in rssiRaw. Two added regression tests cover observed samples, invalid/unknown encodings, signed inputs (no double conversion), and CSV units/provenance. The supplied original CSV is unchanged; output contains a corrected copy. The updated APK has not yet been validated on the physical reader.

## DCIM saving and simplified screen

Removed the connected/disconnected controls card. Automatic connection remains. Added persisted SAF folder access limited to DCIM or a descendant, with a once-only system folder grant. Stop drains/closes the recovery CSV before copying to DCIM and reports its destination; copy failures retain the recovery file and show an error. Tests cover publication after complete draining and destination failure recovery. Actual folder-provider permission persistence and DCIM output still require device verification.

## TXT extension update

New sessions and DCIM copies use .txt and text/plain. Existing comma-separated content and all fields are preserved. UI labels use TXT. Saved-log discovery includes both old .csv and new .txt files; existing files are unchanged.

## JSON-line TXT and manifest permissions

TXT records are now one JSON object per event, with properly escaped strings and numeric counters, no CSV header. Manifest declares WRITE_EXTERNAL_STORAGE (max SDK 29) and MANAGE_EXTERNAL_STORAGE; the UI requests the corresponding legacy runtime grant or modern Settings grant. Direct DCIM output uses exclusive file creation, flush/fsync and media indexing. Runtime permission flow and device writes require hardware verification. Existing formatting/count/drain/error tests were updated for the headerless format and use an independent JSON parser.

## Combined-zone workflow

One public TXT is published only after both Floor and BackOffice contain reads. Pending closed segments are journaled across restarts. Output preserves order and zone and recalculates counters across the combined file. Tests cover both scan orders, repeated EPCs across zones, publisher recreation, new batch reset, and DCIM save failure/retry. Live counters remain scoped to the current scanning segment. Physical stop/start sequencing and DCIM publication still require XR2 validation.
`nHandheld tilt / configured band update: assembleDebug, testDebugUnitTest (22 tests, zero failures), and lintDebug passed. Sensor math, missing samples, JSON field provenance and configured band mapping covered by regression test. Live XR2 sensor availability and physical calibration remain unverified.
