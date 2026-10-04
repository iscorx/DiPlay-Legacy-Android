package com.shilapi.xcertplay

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.usb.*
import android.os.*
import android.widget.*
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File

/** Ordinary app-only diagnostics. Never claims an interface or writes a device control request. */
class EadoCompatibilityActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    @Volatile private var cancelled = false
    @Volatile private var permissionResult = "NOT REQUESTED"
    private lateinit var usb: UsbManager
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var pipeline: LinearLayout
    private lateinit var quickGrid: LinearLayout
    private var quickFacts = emptyMap<String, String>()
    @Volatile private var openResult = "UNKNOWN"
    @Volatile private var pendingDeviceId = -1
    private var flashSeen = false
    private val history = linkedSetOf<String>()
    private val permissionAction get() = "$packageName.EADO_USB_PERMISSION"
    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != permissionAction) return
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            if (device.deviceId != pendingDeviceId || device.vendorId != 0x05ac) return
            permissionResult = if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) "GRANTED" else "DENIED"
            EadoEvents.log("USB permission callback $permissionResult")
        }
    }
    private val hotplug = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            device?.let {
                EadoEvents.log("${intent.action?.substringAfterLast('.')} VID=${it.vendorId.toString(16)} PID=${it.productId.toString(16)} id=${it.deviceId}")
            }
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) openResult = "UNKNOWN"
            refresh()
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (cancelled) return
            renderQuickStatus()
            renderPipeline()
            ui.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 16, 20, 24); setBackgroundColor(Color.rgb(15, 23, 34)) }
        setContentView(ScrollView(this).apply { addView(content) })
        content.addView(text("Eado Compatibility Center", 26))
        content.addView(text("Park the car before testing. UNKNOWN is not failure. USB flash storage does not prove Apple enumeration.", 16))
        status = text("Quick Status", 19); content.addView(status)
        quickGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; content.addView(quickGrid)
        fun action(label: String, block: () -> Unit) { content.addView(Button(this).apply { text = label; minHeight = 60; setOnClickListener { block() } }) }
        action("USB Inspector / Refresh") { refresh() }
        action("Wireless Inspector") { background { val report = networkReport(); ui.post { showText("Wireless Inspector", report) } } }
        action("Run Auto Diagnose & Repair — USB") { autoRepair() }
        action("Connect using tested USB transport") {
            if (!busy.get()) { AirPlayPersistence.saveWirelessEnabled(this, false); startActivity(Intent(this, CarPlayHostActivity::class.java)) }
        }
        action("USB backend selection") {
            AlertDialog.Builder(this).setTitle("USB backend").setSingleChoiceItems(arrayOf("Automatic (API19 bounded bulk)", "Compatibility bulk (all Android versions)", "Framework async (API26+ only)"), backend()) { dialog, which ->
                if (which == 2 && Build.VERSION.SDK_INT < 26) { toast("Framework timeout API is unavailable before API26"); return@setSingleChoiceItems }
                getSharedPreferences("eado", MODE_PRIVATE).edit().putInt("usb_backend", which).apply()
                dialog.dismiss(); toast("Applies on next connection")
            }.setNegativeButton("Close", null).show()
        }
        action("Wireless mode / IPv4 fallback") { wirelessOptions() }
        action("Run Auto Diagnose & Repair — Wireless") {
            if (busy.get() || CarPlayBackgroundSession.hasSession()) { toast("Stop the current app session before wireless repair"); return@action }
            background {
                networkReport()
                EadoEvents.log("Wireless repair: inspect local interface; listener port fallback enabled; one transport retry maximum")
                ui.post { if (!cancelled) {
                    AirPlayPersistence.saveWirelessEnabled(this, true)
                    AirPlayPersistence.saveWirelessHotspotMode(this, com.shilapi.xcertplay.orchestration.WirelessHotspotMode.MANUAL)
                    startActivity(Intent(this, CarPlayHostActivity::class.java))
                } }
            }
        }
        action("Safe video profile — H.264 / 30 fps / 80%") {
            AirPlayPersistence.saveHevcEnabled(this, false); AirPlayPersistence.saveFps(this, 30); AirPlayPersistence.saveDisplayScaleTenths(this, 8)
            EadoEvents.log("Saved H.264 30 fps 80%; applies on next connection"); toast("Saved for next connection")
        }
        action("View Full Report / Live Log") { viewReport() }
        action("Save Report") { saveReport() }
        action("Reset app connection state") {
            if (busy.get()) { toast("Wait for repair to finish"); return@action }
            CarPlayBackgroundSession.stop { ui.post { EadoEvents.clear(); EadoEvents.log("App session stopped; pairing retained"); refresh() } }
        }
        pipeline = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; content.addView(pipeline)
        details = text("", 16); details.setTextIsSelectable(true); content.addView(details)
        registerPermission()
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(hotplug, filter, Context.RECEIVER_EXPORTED) else registerReceiver(hotplug, filter)
        ui.post(tick); refresh()
    }

    private fun registerPermission() {
        val filter = IntentFilter(permissionAction)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(permissionReceiver, filter)
    }
    private fun backend() = getSharedPreferences("eado", MODE_PRIVATE).getInt("usb_backend", 0)
    private fun text(value: String, size: Int) = TextView(this).apply { text = value; textSize = size.toFloat(); setTextColor(Color.WHITE); setPadding(0, 10, 0, 10) }
    private fun toast(value: String) { Toast.makeText(this, value, Toast.LENGTH_LONG).show() }
    private fun background(block: () -> Unit) {
        if (cancelled) return
        worker.execute { try { block() } catch (e: Exception) { EadoEvents.log("Diagnostic failed: ${e.javaClass.simpleName}"); ui.post { if (!cancelled) toast("Diagnostic failed: ${e.javaClass.simpleName}") } } }
    }
    private fun devices(): List<UsbDevice> = usb.deviceList.values.sortedBy { it.deviceId }
    private fun scan(): String = buildString {
        val all = devices()
        appendLine("USB HOST: ${packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)}")
        appendLine("USB DEVICES: ${all.size}")
        appendLine("APPLE USB DEVICE: ${if (all.any { it.vendorId == 0x05ac }) "YES" else "NO"}")
        for (d in all) {
            history.add("VID=${d.vendorId.toString(16)} PID=${d.productId.toString(16)} id=${d.deviceId}")
            while (history.size > 40) history.remove(history.first())
            appendLine("Device Name: ${d.deviceName}; Device ID: ${d.deviceId}")
            appendLine("Vendor ID: ${d.vendorId} / 0x${d.vendorId.toString(16)}; Product ID: ${d.productId} / 0x${d.productId.toString(16)}")
            appendLine("Class/Subclass/Protocol: ${d.deviceClass}/${d.deviceSubclass}/${d.deviceProtocol}")
            appendLine("Configurations: ${if (Build.VERSION.SDK_INT >= 21) d.configurationCount.toString() else "UNKNOWN (API21 property)"}; interfaces: ${d.interfaceCount}")
            appendLine("Permission: ${usb.hasPermission(d)}; callback: $permissionResult")
            for (i in 0 until d.interfaceCount) {
                val intf = d.getInterface(i)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE) flashSeen = true
                appendLine("  Interface ${intf.id}: ${intf.interfaceClass}/${intf.interfaceSubclass}/${intf.interfaceProtocol}; endpoints=${intf.endpointCount}")
                if (intf.interfaceClass == 255 && intf.interfaceSubclass == 254 && intf.interfaceProtocol == 2) appendLine("  USBMUX candidate (descriptor only; not claimed)")
                for (j in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(j)
                    val type = when (ep.type) { 0 -> "CONTROL"; 1 -> "ISOCHRONOUS"; 2 -> "BULK"; 3 -> "INTERRUPT"; else -> "UNKNOWN" }
                    appendLine("    Address=0x${ep.address.toString(16)} ${if (ep.direction == 128) "IN" else "OUT"} $type maxPacket=${ep.maxPacketSize} interval=${ep.interval}")
                }
            }
        }
        appendLine("PID history: ${history.joinToString("; ")}")
        appendLine("Flash drive observed by UsbManager in this inspection: $flashSeen")
        if (flashSeen && all.none { it.vendorId == 0x05ac }) appendLine("If iPhone is connected now: below-app enumeration problem suspected, not proved. Kernel/host driver/role/vendor filtering are candidates. No system repair applied.")
    }

    private fun refresh() = background {
        val report = scan()
        val all = devices()
        val apple = all.firstOrNull { it.vendorId == 0x05ac }
        val permission = apple?.let { usb.hasPermission(it) }?.toString() ?: "UNKNOWN"
        val btState = runCatching { BluetoothAdapter.getDefaultAdapter()?.isEnabled }.getOrNull()?.toString() ?: "UNKNOWN"
        val hotspot = runCatching { com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this) }.getOrNull()?.toString() ?: "UNKNOWN"
        val facts = linkedMapOf("ANDROID API" to "${Build.VERSION.SDK_INT}", "USB HOST" to packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST).toString(),
            "USB DEVICES" to all.size.toString(), "APPLE USB" to (apple != null).toString(), "USB PERMISSION" to permission,
            "OPEN DEVICE" to openResult, "BLUETOOTH" to btState, "IPHONE SELECTED" to (DiPlayPreferences.phoneAddress(this) != null).toString(),
            "HOTSPOT" to hotspot, "IPHONE NETWORK" to "UNKNOWN")
        ui.post { if (!cancelled) { details.text = report; quickFacts = facts; status.text = "Quick Status - LEVEL ${EadoEvents.level()} (observed, not stability)"; renderQuickStatus(); renderPipeline() } }
    }
    private fun renderQuickStatus() {
        if (!::quickGrid.isInitialized) return
        quickGrid.removeAllViews()
        val facts = LinkedHashMap(quickFacts)
        val stages = EadoEvents.snapshot()
        listOf("iAP2", "MFi", "Bonjour", "AirPlay", "CarPlay session", "Video", "Audio", "Touch").forEach {
            facts[it] = stages.getValue(it).let { state -> if (state.startsWith("NOT STARTED")) "UNKNOWN" else state.substringBefore(" ") }
        }
        facts.entries.chunked(3).forEach { entries ->
            val row = LinearLayout(this)
            entries.forEach { (key, value) ->
                row.addView(text("$key\n$value", 14).apply {
                    setTextColor(when (value) { "true", "SUCCESS" -> Color.rgb(98,225,145); "false", "FAILED" -> Color.rgb(255,105,105); else -> Color.rgb(255,212,102) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            quickGrid.addView(row)
        }
    }
    private fun renderPipeline() {
        pipeline.removeAllViews()
        pipeline.addView(text("CarPlay Pipeline — current process evidence", 21))
        EadoEvents.snapshot().forEach { (name, value) ->
            pipeline.addView(text("$name: $value", 17).apply { setTextColor(when { value.startsWith("SUCCESS") -> Color.rgb(98, 225, 145); value.startsWith("FAILED") -> Color.rgb(255, 105, 105); else -> Color.rgb(255, 212, 102) }) })
        }
    }
    private fun autoRepair() {
        if (CarPlayBackgroundSession.hasSession()) { toast("Stop the app connection before opening a diagnostic USB handle"); return }
        if (!busy.compareAndSet(false, true)) return
        background {
            try {
                EadoEvents.stage("Device detection", "RUNNING", "Bounded enumeration poll, 15 seconds")
                var apple: UsbDevice? = null
                val deadline = SystemClock.elapsedRealtime() + 15000
                while (!cancelled && SystemClock.elapsedRealtime() < deadline) {
                    apple = devices().firstOrNull { it.vendorId == 0x05ac }
                    if (apple != null) break
                    Thread.sleep(500)
                }
                if (cancelled) return@background
                var device = apple ?: run { EadoEvents.stage("Device detection", "FAILED", "No Apple VID after 15s. Compare flash drive; no kernel/role changes attempted."); return@background }
                EadoEvents.stage("Device detection", "SUCCESS", "Apple VID detected PID=${device.productId.toString(16)}")
                for (attempt in 1..2) {
                    if (usb.hasPermission(device) || cancelled) break
                    permissionResult = "REQUESTED"
                    pendingDeviceId = device.deviceId
                    val target = device
                    ui.post {
                        if (!cancelled) {
                            if (attempt == 2) { runCatching { unregisterReceiver(permissionReceiver) }; registerPermission() }
                            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                            runCatching { usb.requestPermission(target, PendingIntent.getBroadcast(this, target.deviceId, Intent(permissionAction).setPackage(packageName), flags)) }
                                .onFailure { permissionResult = "REQUEST FAILED" }
                        }
                    }
                    EadoEvents.log("USB permission REQUESTED attempt=$attempt/2")
                    val until = SystemClock.elapsedRealtime() + 10000
                    while (!cancelled && SystemClock.elapsedRealtime() < until && !usb.hasPermission(device) && permissionResult == "REQUESTED") Thread.sleep(200)
                    if (permissionResult == "DENIED" || permissionResult == "REQUEST FAILED") break
                    if (!usb.hasPermission(device)) { permissionResult = "NO RESPONSE"; EadoEvents.log("USB permission NO RESPONSE; receiver retry bounded") }
                    device = devices().firstOrNull { it.vendorId == 0x05ac } ?: break
                }
                if (cancelled) return@background
                if (!usb.hasPermission(device)) { EadoEvents.stage("USB/Wi-Fi", "FAILED", "USB permission $permissionResult; grant USB permission and retry"); return@background }
                for (attempt in 1..3) {
                    if (cancelled || CarPlayBackgroundSession.hasSession()) break
                    device = devices().firstOrNull { it.vendorId == 0x05ac } ?: break
                    if (!usb.hasPermission(device)) break
                    val connection = runCatching { usb.openDevice(device) }.getOrNull()
                    openResult = if (connection == null) "FAILED" else "SUCCESS"
                    EadoEvents.log("openDevice attempt=$attempt/3 ${if (connection == null) "FAILED" else "SUCCESS"}")
                    if (connection != null) {
                        try {
                            EadoEvents.stage("USB/Wi-Fi", "SUCCESS", "openDevice; descriptor bytes=${runCatching { connection.rawDescriptors.size }.getOrDefault(0)}; no claim or control transfer")
                        } finally { connection.close() }
                        ui.post { if (!cancelled) { toast("USB open succeeded. Starting the existing wired CarPlay transport."); AirPlayPersistence.saveWirelessEnabled(this, false); startActivity(Intent(this, CarPlayHostActivity::class.java)) } }
                        return@background
                    }
                    if (attempt < 3) Thread.sleep(1500)
                }
                EadoEvents.stage("USB/Wi-Fi", "FAILED", "openDevice failed after bounded retries; framework/backend inspection required")
            } finally { busy.set(false); refresh() }
        }
    }

    private fun networkReport(): String = buildString {
        appendLine("Wireless inspector — local addresses, not proof of iPhone association")
        val interfaces = NetworkInterface.getNetworkInterfaces()
        if (interfaces != null) for (intf in Collections.list(interfaces)) {
            if (!intf.isUp || intf.isLoopback) continue
            appendLine("Interface ${intf.name}")
            intf.interfaceAddresses.forEach { appendLine("  ${it.address.hostAddress}/${it.networkPrefixLength}") }
        }
        appendLine("Hotspot enabled: ${runCatching { com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this@EadoCompatibilityActivity) }.getOrNull() ?: "UNKNOWN"}")
        @Suppress("DEPRECATION")
        val bt = BluetoothAdapter.getDefaultAdapter()
        appendLine("Bluetooth available: ${bt != null}")
        appendLine("Bluetooth enabled: ${runCatching { bt?.isEnabled }.getOrNull() ?: "UNKNOWN / permission required"}")
        appendLine("Paired devices: ${runCatching { bt?.bondedDevices?.size }.getOrNull() ?: "UNKNOWN"}")
        appendLine("iPhone selected: ${DiPlayPreferences.phoneAddress(this@EadoCompatibilityActivity) != null}; address omitted")
        appendLine("iPhone on network: UNKNOWN until protocol connection; SSID/band/gateway may be unavailable on OEM firmware")
        appendLine("No traffic does not prove hotspot filtering. Compare shared Wi-Fi using existing setup credentials.")
        appendLine(EadoEvents.report())
    }
    private fun wirelessOptions() {
        val prefs = getSharedPreferences("eado", MODE_PRIVATE)
        val choices = arrayOf("Built-in hotspot / automatic address", "Built-in hotspot / IPv4", "External Wi-Fi / shared network (experimental)")
        AlertDialog.Builder(this).setTitle("Wireless network path").setSingleChoiceItems(choices, prefs.getInt("network_mode", 0)) { dialog, which ->
            prefs.edit().putInt("network_mode", which).apply(); dialog.dismiss()
            showText("Next connection", "${choices[which]}\nConnect both devices to the same network for external mode. Save that network's exact credentials in Connection setup, choose Built-in hotspot, then Connect phone. This option changes app address selection only. It does not change car Wi-Fi or disable Bluetooth.")
        }.setNegativeButton("Close", null).show()
    }
    private fun viewReport(): Unit = background {
        val report = EadoReport.build(this, scan() + "\n" + networkReport())
        ui.post {
            if (cancelled) return@post
            val body = text(report, 14).apply { setTextIsSelectable(true) }
            val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val scroll = ScrollView(this).apply { addView(body) }
            layout.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            var dialog: AlertDialog? = null
            fun button(label: String, action: () -> Unit) { layout.addView(Button(this).apply { text = label; setOnClickListener { action() } }) }
            button("Refresh") { dialog?.dismiss(); viewReport() }
            button("Copy All") { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("DiPlay report", report)); toast("Copied") }
            button("Save") { saveReport() }
            button("Clear old log") {
                background { SessionLogFile.REPORT_NAMES.filter { it != "diplay.log" }.forEach { File(filesDir, "logs/$it").delete() }; EadoEvents.log("Archived logs cleared; current log retained") }
                toast("Archived logs cleared")
            }
            dialog = AlertDialog.Builder(this).setTitle("View Diagnostic Report").setView(layout).setPositiveButton("Close", null).create()
            dialog.show(); dialog.window?.setLayout(-1, -1)
        }
    }
    private fun saveReport() {
        if (Build.VERSION.SDK_INT in 23..28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 19); return
        }
        writeReport()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 19) writeReport() // denial still permits app-private fallback
    }
    private fun writeReport() = background {
        val saved = EadoReport.save(this, EadoReport.build(this, scan() + "\n" + networkReport()))
        ui.post { if (!cancelled) showText("Report saved", saved) }
    }
    private fun showText(title: String, body: String) { if (!cancelled) AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(TextView(this@EadoCompatibilityActivity).apply { text = body; textSize = 16f; setTextIsSelectable(true); setPadding(16, 16, 16, 16) }) }).setPositiveButton("Close", null).show() }
    override fun onDestroy() {
        cancelled = true; ui.removeCallbacksAndMessages(null); worker.shutdownNow()
        runCatching { unregisterReceiver(permissionReceiver) }; runCatching { unregisterReceiver(hotplug) }
        super.onDestroy()
    }
}
