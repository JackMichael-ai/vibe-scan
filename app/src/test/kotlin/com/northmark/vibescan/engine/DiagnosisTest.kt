package com.northmark.vibescan.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosisTest {

    @Test
    fun testUnpack29Elements() {
        val raw = FloatArray(29)
        raw[0] = 85f      // health
        raw[1] = 2f       // faultCode (BPFO)
        raw[2] = 1.5f     // rmsMs2
        raw[3] = 4.2f     // rmsMms
        raw[4] = 3.5f     // kurtosis
        raw[10] = 90f     // confidence
        raw[11] = 1.0f    // baselineReady
        raw[13] = 'B'.toInt().toFloat() // isoZone
        
        raw[14] = 1.1f    // rmsMmsX
        raw[15] = 2.2f    // rmsMmsY
        raw[16] = 4.2f    // rmsMmsZ (Axial dominance)
        
        raw[23] = 1.8f    // bpfoHarmonicRatio (>1.5)
        raw[24] = 2.0f    // worstAxis (Z)
        raw[25] = 100f    // harmonicConfidence
        
        raw[26] = 75f     // mountingQuality
        raw[27] = 1.0f    // isMounted
        raw[28] = 2.0f    // mountingStatusLevel (Good)

        val d = Diagnosis.fromRaw(raw)

        assertEquals(85, d.health)
        assertEquals(2, d.faultCode)
        assertEquals(4.2f, d.rmsMms, 0.01f)
        assertTrue(d.baselineReady)
        assertEquals('B', d.isoZone)
        
        assertEquals(4.2f, d.rmsMmsZ, 0.01f)
        assertEquals(1.8f, d.bpfoHarmonicRatio, 0.01f)
        assertEquals(2, d.worstAxis)
        assertEquals(100f, d.harmonicConfidence, 0.01f)
        
        assertTrue(d.isMounted)
        assertEquals(2, d.mountingStatusLevel)
        assertEquals(75f, d.mountingQuality, 0.1f)

        // Test derived logic
        assertTrue("BPFO should be confirmed", d.isBpfoConfirmed)
        assertEquals("Z-axis (axial)", d.worstAxisLabel)
        assertTrue(d.axialNote.contains("Axial dominance"))
    }

    @Test
    fun testLegacyArrayReturnsIdle() {
        val legacy = FloatArray(26)
        val d = Diagnosis.fromRaw(legacy)
        assertEquals(100, d.health)
        assertEquals(0, d.faultCode)
        assertEquals(0f, d.rmsMms, 0f)
    }
}
