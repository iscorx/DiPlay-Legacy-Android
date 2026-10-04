package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class EadoReportTest {
    @Test fun publicFolderFailureFallsBackWithoutPicker() {
        val root = Files.createTempDirectory("eado-report").toFile()
        try {
            val blocked = File(root, "blocked").apply { writeText("not a directory") }
            val external = File(root, "external")
            val result = EadoReport.saveFiles(blocked, external, root, "test.txt", "تقرير USB")
            assertEquals(File(external, "DiPlay/test.txt"), result)
            assertEquals("تقرير USB", result.readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun unavailableExternalStorageUsesPrivateDirectory() {
        val root = Files.createTempDirectory("eado-report").toFile()
        try { assertEquals(File(root, "DiPlay/test.txt"), EadoReport.saveFiles(null, null, root, "test.txt", "ok")) }
        finally { root.deleteRecursively() }
    }
    @Test(expected = IllegalArgumentException::class) fun disallowsPathTraversal() {
        EadoReport.saveFiles(null, null, File("."), "../test.txt", "bad")
    }
    @Test fun unknownIsNotSuccessAndResetDropsPriorSessionEvidence() {
        EadoEvents.clear()
        assertTrue(EadoEvents.snapshot().values.all { it.contains("UNKNOWN") })
        EadoEvents.observe("iap2 authentication accepted")
        assertTrue(EadoEvents.snapshot().getValue("MFi").startsWith("SUCCESS"))
        assertTrue(EadoEvents.snapshot().getValue("Video").contains("UNKNOWN"))
        EadoEvents.clear()
        assertTrue(EadoEvents.snapshot().getValue("MFi").contains("UNKNOWN"))
    }
    @Test fun secretsAndRawProtocolNeverEnterLiveLog() {
        EadoEvents.clear()
        EadoEvents.log("password=example-secret")
        EadoEvents.log("payload=001122")
        EadoEvents.log("Bluetooth 01:02:03:04:05:06")
        assertFalse(EadoEvents.report().contains("example-secret"))
        assertFalse(EadoEvents.report().contains("001122"))
        assertFalse(EadoEvents.report().contains("01:02:03:04:05:06"))
    }
    @Test fun tcpAcceptDoesNotEstablishAirPlayOrCarPlay() {
        EadoEvents.clear()
        EadoEvents.observe("AirPlay incoming TCP connection")
        assertTrue(EadoEvents.snapshot().getValue("AirPlay").startsWith("RUNNING"))
        assertEquals(1, EadoEvents.level())
    }
    @Test fun latePreviousSessionLogCannotChangeCurrentEvidence() {
        val old = EadoEvents.beginSession()
        EadoEvents.beginSession()
        EadoEvents.observe("iap2 authentication accepted", old)
        assertTrue(EadoEvents.snapshot().getValue("MFi").contains("UNKNOWN"))
        EadoEvents.observe("iap2 authentication accepted")
        assertEquals(5, EadoEvents.level())
        EadoEvents.endSession()
        assertEquals(1, EadoEvents.level())
    }
}
