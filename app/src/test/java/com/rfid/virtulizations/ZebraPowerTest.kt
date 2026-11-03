package com.rfid.virtulizations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ZebraPowerTest {
    @Test fun automaticSelectionRecognizesXr2AndKeepsZebraOnItsOwnSdk() {
        assertEquals("XR2", automaticReaderFamily("Janam", "XR2"))
        assertEquals("XR2", automaticReaderFamily("JANAM", "XR2 UHF"))
        assertEquals("Zebra", automaticReaderFamily("Zebra Technologies", "TC22"))
        assertEquals("Zebra", automaticReaderFamily("Zebra", "MC3330xR"))
        assertEquals("Zebra", automaticReaderFamily("Samsung", "SM-A"))
    }
    @Test fun powerUsesCapabilityValuesRatherThanAssumingIndexEqualsDbm() {
        val levels = intArrayOf(100, 150, 200, 270, 300)
        assertEquals(1, zebraPowerIndex(levels, 15))
        assertEquals(4, zebraPowerIndex(levels, 30))
        assertThrows(IllegalArgumentException::class.java) { zebraPowerIndex(levels, 26) }
        assertThrows(IllegalArgumentException::class.java) { zebraPowerIndex(intArrayOf(330), 33) }
    }
    @Test fun reportedPowerAndDropdownUseTenthsOfDbm() {
        assertEquals("30", zebraPowerDbm(300))
        assertEquals("15", zebraPowerDbm(150))
        assertEquals("29.5", zebraPowerDbm(295))
        val levels = (0..300).toList().toIntArray()
        assertEquals((5..30).toList(), zebraSupportedPowers(levels))
        assertEquals(listOf(10, 15, 20), zebraSupportedPowers(intArrayOf(100, 150, 155, 200)))
    }
}
