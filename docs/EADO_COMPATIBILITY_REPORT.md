# DiPlay Eado Legacy — compatibility report

Target: Changan Eado 2020, S401AUTO-MX6Q, Android 4.4.2/API19, i.MX6Q, kernel 3.0.35. Work starts from the user's Legacy fork at c8884ad, not a new Android project. Branch: `eado-api19-carplay`. Package remains `com.shihab.diplay`; version code 30, version `0.2.7-eado.1`.

## Implemented

| Area | Change and principal files |
|---|---|
| Legacy export | `EadoReport.kt`, `DiPlayActivity.kt`, common manifest: API19–28 saves directly to Download/DiPlay, requests storage permission only on API23–28, falls back to external app files then internal app files. Displays full destination, UTF-8 byte count and time; no picker or share target needed. Android 10+ retains MediaStore. |
| Diagnostics UI | `EadoCompatibilityActivity.kt`: Settings → Diagnostics → Eado Compatibility Center; colored quick status/pipeline, USB descriptors/endpoints, VID 0x05AC detection, bounded PID history and attach/detach logs, wireless inspection, full scrollable report, Refresh/Copy All/Save/Clear old log. Clear removes archived app logs only. |
| USB repair | 15-second enumeration polling; explicit permission callback and one no-response retry with receiver re-registration; recheck actual permission; at most three open attempts separated by 1.5 seconds. Diagnostic handles are closed without claiming interfaces or sending control transfers. Success launches the existing wired transport. Active app sessions must be stopped before diagnostic open. |
| USB transport | `IphoneUsbHost.kt`, `LegacyUsbWrite.kt`: pre-26 timed bulk reads instead of unbounded requestWait; pre-28 writes split at 16 KiB with a single deadline and partial-write handling. Selectable automatic/compatibility bulk/API26+ async backends. Legacy configuration wrappers retained. |
| Upstream fixes | Incremental `UsbMuxFrameBuffer` and regression tests; `AirPlayPortSelector` handles occupied 7000 with bounded fallback candidates then an ephemeral port. Actual bound port reaches both Bonjour and iAP2. API21+ VPN app allowlist is guarded; API19 keeps existing IPv6 link-local routing because allowlist API is unavailable. |
| Wireless modes | `ManualHotspotManager.kt`: explicit IPv4 selection and experimental shared-network mode selects the associated station interface by its actual IPv4 address, checks saved network name, and leaves system Wi-Fi settings untouched. Requires existing Connection setup credentials. It does not claim automatic credential discovery or prove hotspot filtering. |
| Session evidence | `EadoEvents.kt`, `SessionLogFile.kt`, host callbacks: bounded redacted live events; iAP2/MFi/Bonjour/listener/session/video/audio/touch evidence. TCP acceptance does not mean established AirPlay. Old log generations cannot change the new session. Unknown stages stay unknown. |
| Recovery/media | One legacy transport reconnect maximum per failed connection sequence; authentication failures do not automatically retry. Decoder configuration failure can reconnect with H.264/30fps at 80%, then 60%, at most twice. Fresh legacy installs default to 80%; existing saved choices remain. Existing API19 codec buffer and AudioTrack constructor/fallback paths retained. Touch records mapped/queued input, not an unobserved phone response. |
| Privacy | Raw media payload prefixes removed from normal media logs; report redaction extended. Runtime identity and Android signing material remain outside Git. No telemetry/upload is added. |
| Build | API19, NDK r25c / APP_PLATFORM=android-19 and all original ABIs retained. CI requests correct NDK. Explicit v1+v2 signing. |

## Validation

The initial signed release passed 369 unit tests, zero failures/errors, and release lint with zero errors/five warnings. Additional session-generation and USB chunk-boundary regression tests were then added; final totals and final APK hash are recorded in `EADO_VALIDATION.json` after the final build.

API19, API21, API24 and API27 x86 emulators passed installation of the signed release, launcher startup, Settings navigation, Compatibility Center, report rendering, Copy All and Save confirmation with no AndroidRuntime fatal exception. Emulators are software/platform checks, not i.MX6/USB/iPhone substitutes. Final smoke results are also recorded in `EADO_VALIDATION.json`.

APK checks: package unchanged; minSdk19; armeabi-v7a present; v1/JAR and v2 signatures verified. Signing certificate SHA-256:

`93b217f9695b05b239524f7c54af4b48859fedefafdf01deb54e1d01ebe73d50`

Build host: Windows. Legacy ndk-build cannot handle the workspace path's spaces; a synchronized source copy in the user's temporary directory was used for building. Original repository/history remain in the workspace. Source/CI credential guard passes. Standalone runtime files were locally provisioned from the official Legacy v0.2.7 release at the user's explicit request and are not in Git. The APK necessarily contains this experimental shared identity; acceptance by the user's iPhone remains untested.

## Limits that matter on the car

- No physical Eado or iPhone was connected during development. Previously established car evidence remains app startup (LEVEL1), working USB mass storage and iPhone association to AndroidAP. No new CarPlay success is claimed.
- Descriptor visibility is the decisive next observation. If flash storage appears but Apple VID does not, a below-app issue is **suspected**, not proved. No kernel/role patch is applied.
- Permission prompts, OEM USB open/claim behavior, NCM/VPN, certificate acceptance, Bluetooth handoff and sustained video/audio/touch require the car test. Bulk reads cannot distinguish a kernel timeout from all I/O failures; protocol deadlines bound recovery.
- The API19 VPN lacks per-app allowlisting. Existing IPv6 link-local route behavior requires regression checks against factory apps on the car. No firewall/system routes or permanent properties are changed by diagnostics.
- Shared Wi-Fi is experimental and still uses the existing Bluetooth/iAP2 setup. Select the mode, join both devices to the same external network, and enter that network's credentials in Connection setup. Lack of incoming traffic alone is not proof of hotspot filtering.
- Audio uses existing safe fallback configurations rather than changing sample rate without resampling. Missing audio or a video session that supplies no frames needs its diagnostic evidence; repeated speculative reconnects are not used. Video automatic fallback triggers on observed decoder configuration failure, not merely a quiet/hidden screen.
- Runtime properties Android does not expose remain UNKNOWN, including phone network association before protocol evidence and unreadable AP channel. LEVEL9 usefulness and LEVEL10 stability require explicit physical observation; they are never inferred from queued touch/audio alone.
- New diagnostic labels are English. Core app's existing Arabic and other translations are retained.
- Factory launcher, camera, radio, CAN, MCU, EasyConnection and firmware are not modified. The existing project's BYD code is not expanded for Changan. No root, kernel rebinding, flashing, persistent iptables or factory app removal is performed.

## Short car test

1. Save any reports/settings needed from the old installation. This fork has a new signing key: uninstall the old Legacy package only if Android rejects the update, then install the supplied APK. Uninstalling clears that app's private data.
2. Park → Settings → Diagnostics → Eado Compatibility Center. Save a no-USB report.
3. Connect the known-working flash drive → USB Inspector → Save Report. Disconnect it, connect the unlocked iPhone → USB Inspector. Capture whether VID `0x05AC` appears.
4. Run Auto Diagnose & Repair — USB; accept USB/CarPlay/VPN prompts if shown. Save the report after the result.
5. Test AndroidAP with its exact saved credentials and selected paired iPhone. Use Wireless Inspector and the wireless repair button. If needed test explicit IPv4, then the external shared-network mode.
6. Screenshot Quick Status/Pipeline and save the full report. Check video, sound, touch and reconnection physically before claiming a stable usable session.

The replacement signing key and its local password file must be backed up privately for future updates; they are deliberately absent from this repository. Do not publish them or the runtime identity directory.
