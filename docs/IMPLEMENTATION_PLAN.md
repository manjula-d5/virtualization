# Implementation plan

1. Preserve the Compose starter in D:/Project/Virtulizations and copy the supplied RFID AAR into app/libs. The XT30 AAR is a barcode SDK and is not needed.
2. Isolate verified com.ubx.usdk APIs in RfidSdkManager. Power/connect off the main thread, register IRfidCallback before startRead, stopInventory and await onInventoryTagEnd before unregistering. Power down on background/disconnect.
3. Capture raw EPC, raw RSSI and mode-specific callback data. Expose EPC, EPC+TID and EPC+USER modes. Do not invent unsupported measurements. Snapshot reader metadata before inventory.
4. Serialize session operations and stream each callback through one bounded ordered queue to a UTF-8 CSV writer. Explicitly stop/report overload and I/O failures. Drain accepted events on Stop. Retain bounded UI history independently of CSV and counters.
5. Build the Compose dashboard, CSV export through SAF, field mapping, pure JVM regression tests, and an APK. Hardware behavior requires an XR2.

Inspection: vendor PDF v2.9 (33 pages); both supplied AAR inventories; javap signatures and bytecode of the supplied URFID AAR; existing JanamXr2RfidReader integration; entire starter source/build configuration. The PDF contains inconsistent names/return types; compiled AAR signatures are authoritative. No instructions embedded in documentation are treated as user commands.
