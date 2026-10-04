package com.shilapi.xcertplay

import java.util.ArrayDeque
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bounded process-local evidence; previous-session logs never turn live indicators green. */
internal object EadoEvents {
    private val lines = ArrayDeque<String>()
    private val stages = linkedMapOf<String, String>()
    private var lastTouchLog = 0L
    val pipeline = listOf("USB/Wi-Fi", "Device detection", "Bluetooth handoff", "iAP2", "MFi", "Bonjour", "AirPlay", "CarPlay session", "Video", "Audio", "Touch")
    private var generation = 0L
    @Synchronized fun clear() { lines.clear(); stages.clear(); lastTouchLog = 0; generation++ }
    @Synchronized fun beginSession(): Long { clear(); return generation }
    @Synchronized fun sessionGeneration(): Long = generation
    @Synchronized fun endSession() {
        stages.clear()
        log("Session ended; live stage evidence reset. Prior details remain in log.")
    }
    @Synchronized fun stage(name: String, state: String, evidence: String) {
        if (name == "Touch") {
            val now = System.currentTimeMillis()
            if (now - lastTouchLog < 2000) return
            lastTouchLog = now
        }
        stages[name] = "$state — $evidence"
        log("$name $state: $evidence")
    }
    @Synchronized fun log(message: String) {
        val safe = DiagnosticRedactor.redact(message) ?: return
        lines.addLast("[${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}] $safe")
        while (lines.size > 250) lines.removeFirst()
    }
    @Synchronized fun observe(message: String, session: Long = generation) {
        if (session != generation) return
        // Only exact, existing transport event markers are accepted as evidence.
        when {
            message.contains("first frame rendered") -> stage("Video", "SUCCESS", "Decoder rendered first frame; inspect car screen")
            message.contains("Audio playback started") -> stage("Audio", "SUCCESS", "AudioTrack started; audibility requires car check")
            message.contains("authentication-succeeded") || message.contains("iap2 authentication accepted") -> stage("MFi", "SUCCESS", "Phone accepted authentication")
            message.contains("0xaa02 challenge") -> stage("MFi", "RUNNING", "Challenge requested")
            message.contains("0xaa03 signature") -> stage("MFi", "RUNNING", "Challenge response sent")
            message.contains("wireless bonjour: control resolved") -> stage("Bonjour", "SUCCESS", "Phone control service resolved")
            message.contains("wireless Bonjour services started") -> stage("Bonjour", "RUNNING", "Publisher started; discovery not yet confirmed")
            message.contains("audio first PCM") -> stage("Audio", "RUNNING", "PCM decoded; audible output requires car confirmation")
            message.contains("AirPlay listener SUCCESS") -> stage("AirPlay", "RUNNING", message)
            message.contains("AirPlay incoming TCP") -> stage("AirPlay", "RUNNING", "TCP accepted; protocol establishment not yet confirmed")
            message.contains("iAP2 CSM channel opened") -> stage("iAP2", "SUCCESS", "Channel opened; authentication not implied")
            message.contains("RFCOMM connected") -> stage("Bluetooth handoff", "RUNNING", "RFCOMM connected; Wi-Fi handoff not confirmed")
        }
    }
    @Synchronized fun snapshot(): Map<String, String> = pipeline.associateWith { stages[it] ?: "NOT STARTED / UNKNOWN" }
    @Synchronized fun level(): Int = when {
        stages["Video"]?.startsWith("SUCCESS") == true -> 8
        stages["CarPlay session"]?.startsWith("SUCCESS") == true -> 7
        stages["AirPlay"]?.startsWith("SUCCESS") == true -> 6
        stages["MFi"]?.startsWith("SUCCESS") == true -> 5
        stages["iAP2"]?.startsWith("SUCCESS") == true -> 4
        stages["Device detection"]?.startsWith("SUCCESS") == true -> 3
        stages["USB/Wi-Fi"]?.startsWith("SUCCESS") == true -> 2
        else -> 1
    }
    @Synchronized fun report(): String = "Highest instrumented stage LEVEL ${level()}; audio/touch usefulness and LEVEL10 stability require human confirmation.\n" + snapshot().entries.joinToString("\n") { "${it.key}: ${it.value}" } +
        "\n--- Live log (this process) ---\n" + lines.joinToString("\n")
}
