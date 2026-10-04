# Eado upstream audit — 2026-10-04

Fork: iscorx/DiPlay-Legacy-Android, verified parent programmerguohuajing/DiPlay-Legacy-Android.
Base: c8884ad. Upstream main inspected: 36302e05f4bb4f06592ea5f910e4a41c3702d0fb; latest fetched release v0.2.11.
Legacy history is a separate source snapshot, so missing commits are identified against upstream v0.2.7, then checked by file diff (not merge ancestry).

| Upstream change | Relevant to Eado? | Safe on API19? | Ported? | Reason |
|---|---|---|---|---|
| 3d0411d USBMUX incremental framing / bounded control padding (supersedes 9f9bc1b) | Yes | Pure JVM | Yes | Preserve fragmented/coalesced frames; do not discard next frame |
| 2cfd801 + 650e83b AirPlay port fallback and socket ownership | Yes | java.net supported | Yes | Listener, Bonjour and iAP2 must advertise identical actual port |
| Latest IphoneUsbHost endpoint diagnostics | Yes | Only with guards | Preserved wrappers | Do not copy API21 configuration or API26 request methods over Legacy wrappers |
| 8ad84f7 / 81a0767 wireless diagnostics | Yes | Mixed | Adapted | Preserve stage evidence without importing Android 10 P2P APIs |
| 885dffb Android 10 P2P recovery | Not KitKat | No unguarded copy | No | Existing manual hotspot is the API19 path |
| Audio stream isolation and audio timeline | Yes | Mixed AudioTrack APIs | Audit existing fallback | Existing Legacy AudioTrack constructor / byte-buffer fallback retained |
| Latest VPN app allowlist | Yes on API21+ | Guard required | Yes | API19 has no per-app VPN allowlist; never pretend otherwise |
| BYD cluster, battery, wheel speed, ADB and parked video | No | Mixed/new dependencies | No | Unrelated factory integration would increase risk on Changan |
| Removal of UsbApiCompat upstream | No | No | No | Would regress verified installation/runtime compatibility |

P0 inspection: minSdk 19 in mobile/common/shared; APP_PLATFORM android-19; NDK 25.2.9519653; armeabi-v7a included. Existing CI incorrectly requested NDK 28.2 and must be corrected. Build and emulator outcomes are recorded separately in EADO_COMPATIBILITY_REPORT.md; static inspection is not runtime validation.

Architecture: mobile packages common classic Activity UI; common owns persistence, session lifetime and reports; shared owns transport, iAP2/MFi, AirPlay, networking, media and JNI. Changes extend these modules rather than introducing another Android project.
