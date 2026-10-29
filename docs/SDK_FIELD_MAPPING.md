# SDK field mapping

Sources: supplied XR2 RFID Development Documentation v2.9 (33 pages), compiled URFIDLibrary-v2.4.0307.aar classes.jar, embedded JARs and native library inventory. javap signatures and bytecode resolve documentation inconsistencies. Other project's Janam adapter was a cross-check, not the API authority.

## Verified lifecycle

- RFIDSDKManager.getInstance().power(true): int, negative is failure; wait 1500 ms, then connect(): boolean.
- getRfidManager(): com.ubx.usdk.rfid.RfidManager; isConnected(): boolean.
- setQueryMode(0/1/2); getQueryMode() confirms the configured mode. RoyalRay maps mode 1 to hybrid Memory=2, WordPtr=0, Length=6; mode 2 to Memory=3 with the same pointer/length. EPC mode is 0.
- registerCallback(com.ubx.usdk.rfid.aidl.IRfidCallback), startRead(): int, 0 success.
- IRfidCallback.onInventoryTag(String EPC, String data, String RSSI), onInventoryTagEnd().
- stopInventory(): int, 0 success; wait for end callback then unregisterCallback(the same instance).
- disConnect() and power(false); power(false) releases the manager internally in this artifact.
- For selected-tag diagnostics only: RfidManager.findEpc(String): com.ubx.usdk.bean.Tag6C. RoyalRay FindEPC copies epcId, memId, rssi, antId and phase from the returned ReadTag. Each non-null result is a separate targeted observation. It is never attached retroactively to a continuous-inventory callback. No inventory callback is registered in this mode.

All reader operations and file I/O run off the UI thread. Controls serialize through a coroutine Mutex. A probe job is joined before disconnect. End callbacks from older registrations cannot acknowledge a new inventory. Missing native support is reported as a connection error.

## CSV columns

Every column is listed below. Empty optional strings appear as N/A in the UI. Optional getter exceptions/negative status codes are treated as unavailable, not measurements. Reader properties and host battery are snapshots before scanning, not per-event measurements.

| Output field | Exact source | Availability and limitation |
|---|---|---|
| timestamp | System.currentTimeMillis at inventory callback entry; after findEpc returns | Host wall clock, local device timezone, milliseconds; not RF capture time |
| deviceName | android.os.Build.MODEL | Host device model, no hardcoded XR2/C72 value |
| deviceNumber | Settings.Secure.ANDROID_ID | App-signing/user scoped Android identifier, not hardware serial |
| serial | RfidManager.getDeviceId() | Vendor documents reader serial; may be empty |
| manufacturer | Build.MANUFACTURER | Host manufacturer reported by Android |
| firmwareVersion | RfidManager.getFirmwareVersion() | Optional reader firmware string |
| epc | IRfidCallback first argument; Tag6C.epcId for targeted mode | Raw string unchanged, including casing/whitespace/malformed data |
| Zone | User selection Floor / BackOffice | Fixed for each session; deliberately preserves sample JSON capitalization |
| rssi | decodeRssi(callback third argument / Tag6C.rssi, module) | dBm: RoyalRay unsigned code minus 129; already negative values preserved. Unknown encodings empty |
| rssiRaw | IRfidCallback third argument; Tag6C.rssi converted to string | Exact SDK value retained for audit |
| rssiUnit | decodeRssi | dBm when conversion/negative value is recognized; otherwise empty |
| rssiEncoding | decodeRssi | royalRay-offset129, signed-dBm, or unrecognized |
| phase | Tag6C.phase from findEpc | Available in selected-EPC mode only; raw integer, units/calibration unspecified. Not in public inventory callback |
| angle | None | Empty; no SDK measured angle or justified conversion |
| antennaId | Tag6C.antId from findEpc | Selected-EPC mode only. Inventory callback drops antenna; zero is retained as returned |
| antennaPort | None | No distinct port in supported event APIs |
| frequency | None in event APIs | Empty. Configured region is logged separately, never presented as actual event frequency |
| channel | None in event APIs | Empty; configured region indices are not instantaneous channel |
| transmitPower | RfidManager.getOutputPower() | Configured value, not tag receive power; setOutputPower(int), success 0; readback required |
| epcScanCount | SessionCounters.accept | Counts every accepted raw EPC occurrence; null/blank events preserved |
| sessionTotalCount | SessionCounters.accept | Event sequence number for each CSV row; malformed events included |
| sessionUniqueCount | SessionCounters | Distinct nonblank raw strings; case-sensitive; no EPC normalization |
| batteryLevel | ACTION_BATTERY_CHANGED EXTRA_LEVEL / EXTRA_SCALE | Host Android battery percent, not separate handle/module battery |
| tid | Callback second argument when setQueryMode(1) selected | Optional mixed TID payload; length/pointer shown separately; no claim of full tag memory |
| userMemory | Callback second argument when setQueryMode(2) selected | Optional mixed USER payload, not simultaneously queried with TID |
| pc | None in inventory callback or Tag6C | Empty; not extracted from EPC. Separate readTag access commands are outside inventory observations |
| crc | None in inventory callback or Tag6C | Empty; not fabricated/calculated as received CRC |
| protocol | None in inventory callback or Tag6C | Empty; API class name Tag6C is not a reported protocol measurement |
| rawData | Callback second argument / Tag6C.memId | Preserved for all modes; targeted mode does not assume which memory bank this represents |
| queryMode | User-selected QueryMode label | EPC, EPC + TID, EPC + USER, Selected EPC diagnostics |
| validationError | epcProblem(raw EPC) | Local missing/hex/even-length validation; raw payload always retained |
| moduleFirmware | RfidManager.getModuleFirmware() | Optional string |
| module | RfidManager.getModule() | Vendor implementation identifier (e.g. runtime-returned royalRay), not inferred manufacturer |
| hardwareVersion | No verified getter | Empty; Android Build.HARDWARE would not be reader hardware revision |
| sdkVersion | No SDK version getter / version constant | Empty. Supplied file is v2.4.0307; artifact filename is not a live queried version |
| connectionType | Selected built-in UHF / vendor serial SDK integration | Application integration descriptor, not a measured transport property |
| connectionStatus | RfidManager.isConnected() | Snapshot before inventory; separate UI state monitors loss |
| readerType | RfidManager.getReaderType() | Raw SDK type; 80 documented as short-range |
| readRange | RfidManager.getRange() | Vendor 0–100 scale; not metres. setRange(int), success 0, readback required; hardware may reject |
| readerTemperature | RfidManager.getReaderTemperature(): String | Optional raw string; compiled signature differs from PDF RfidDate claim; units not invented |
| regionCode | RfidManager.getFrequencyRegion().btRegion unsigned | Configured band code, not per-event channel; class is rfid.aidl.RfidDate |
| regionStartIndex | RfidDate.btFrequencyStart unsigned | Configured frequency start index, not MHz |
| regionEndIndex | RfidDate.btFrequencyEnd unsigned | Configured frequency end index, not MHz |
| configuredAntenna | RfidManager.getInventoryParameter().Antenna | Configuration snapshot only; never substituted for event antennaId |
| inventorySession | RfidParameter.Session | SDK inventory session configuration, not app session ID |
| qValue | RfidParameter.QValue | Configuration snapshot |
| scanInterval | RfidParameter.Interval | Raw SDK configuration field; not a measured event interval |
| memoryWordPointer | RfidParameter.WordPtr | Mixed-memory start pointer configured by SDK |
| memoryWordLength | RfidParameter.Length | Mixed-memory word count configured by SDK |
| eventSource | Adapter source label | inventoryCallback or findEpc, so targeted samples cannot be confused with inventory callbacks |

## Important distinctions

The RoyalRay internal ReadTag and public Tag6C classes have phase and antenna fields. IRfidCallback intentionally forwards only epcId, memId and stringified rssi; parsing vendor debug logs or reflection to access internal continuous-read objects would not be a supported callback integration. The app instead exposes the genuine findEpc API in a clearly separate selected-tag mode. Phase is not converted to angle. Native firmware may return defaults for some fields; zero phase/antenna is displayed as raw SDK output, not proof of calibrated support.

The initial app exposed raw encoded RSSI as-is. The supplied hardware CSV confirms module=royalRay and values 101–105; these are strength codes, not positive dBm. The XR2 RoyalRay conversion is raw minus 129 (101 → -28, 102 → -27, 103 → -26, 104 → -25, 105 → -24 dBm), consistent with the supplied vendor-app screenshot and the existing XR2 adapter offset. The older adapter's 0–100 cutoff is not used: values above 100 also require conversion. The PDF RSSI table has inconsistent rows, so it is not used as a literal lookup table. The conversion is gated on RoyalRay for nonnegative inputs; already negative dBm is not offset again. Raw strings remain available alongside normalized values. Physical signal calibration remains vendor/firmware dependent.

readTag/readDataByTid and inventoryOnceMix exist for explicit memory access; the app uses continuous mixed inventory to avoid interleaving access commands with live inventory. It does not read reserved/password areas, alter tags, or run kill/lock/write commands. Full USER/TID capacity is tag-dependent and not automatically inferred. No measured angle, full-memory dump, CRC or PC is claimed.

The AAR contains ARM native binaries (armeabi, armeabi-v7a, arm64-v8a); there is no x86 reader runtime. It also embeds GripBatterySdk, UEventObserverLibrary, rfidV2.2 and rfiddriver JARs. These remain nested in the copied AAR. No additional Bluetooth or network connection flow is used.

## Handheld attitude and configured RF band

handheldTiltDegrees/handheldOrientation derive from gravity relative to the screen normal, with gravitySensor or filteredAccelerometer source and handheldTiltAgeMs. No tag direction is inferred. Sensor snapshots expire after 1000 ms. configuredFrequencyBand uses Appendix 2 region 4 mapping only: 865.1 + index×0.2 MHz for indices 0–14. Other regions are left unavailable pending verified mappings. Event frequency/channel remain empty and angle stays N/A. Optional selected-EPC probing exposes genuine raw phase and antenna fields separately.
