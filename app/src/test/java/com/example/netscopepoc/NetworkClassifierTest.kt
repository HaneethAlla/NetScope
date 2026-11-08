package com.example.netscopepoc

import com.example.netscopepoc.NetworkClassifier.CLASS_4G
import com.example.netscopepoc.NetworkClassifier.CLASS_5G
import com.example.netscopepoc.NetworkClassifier.CLASS_OTHER
import com.example.netscopepoc.NetworkClassifier.CLASS_UNKNOWN
import com.example.netscopepoc.NetworkClassifier.OVERRIDE_LTE_ADVANCED_PRO
import com.example.netscopepoc.NetworkClassifier.OVERRIDE_LTE_CA
import com.example.netscopepoc.NetworkClassifier.OVERRIDE_NONE
import com.example.netscopepoc.NetworkClassifier.OVERRIDE_NR_ADVANCED
import com.example.netscopepoc.NetworkClassifier.OVERRIDE_NR_NSA
import com.example.netscopepoc.NetworkClassifier.OVERRIDE_NR_NSA_MMWAVE
import com.example.netscopepoc.NetworkClassifier.TYPE_LTE
import com.example.netscopepoc.NetworkClassifier.TYPE_LTE_CA
import com.example.netscopepoc.NetworkClassifier.TYPE_NR
import com.example.netscopepoc.NetworkClassifier.TYPE_UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkClassifierTest {

    @Test
    fun `NR without override is 5G`() {
        assertEquals(CLASS_5G, NetworkClassifier.networkClass(TYPE_NR, OVERRIDE_NONE))
    }

    @Test
    fun `LTE without override is 4G`() {
        assertEquals(CLASS_4G, NetworkClassifier.networkClass(TYPE_LTE, OVERRIDE_NONE))
    }

    @Test
    fun `LTE carrier aggregation stays 4G`() {
        assertEquals(CLASS_4G, NetworkClassifier.networkClass(TYPE_LTE, OVERRIDE_LTE_CA))
        assertEquals(CLASS_4G, NetworkClassifier.networkClass(TYPE_LTE, OVERRIDE_LTE_ADVANCED_PRO))
        assertEquals(CLASS_4G, NetworkClassifier.networkClass(TYPE_LTE_CA, OVERRIDE_NONE))
    }

    @Test
    fun `LTE NSA override is 5G`() {
        assertEquals(CLASS_5G, NetworkClassifier.networkClass(TYPE_LTE, OVERRIDE_NR_NSA))
        assertEquals(CLASS_5G, NetworkClassifier.networkClass(TYPE_LTE_CA, OVERRIDE_NR_NSA))
    }

    @Test
    fun `LTE NSA mmWave override is 5G`() {
        assertEquals(CLASS_5G, NetworkClassifier.networkClass(TYPE_LTE, OVERRIDE_NR_NSA_MMWAVE))
    }

    @Test
    fun `LTE NR advanced override is 5G`() {
        assertEquals(CLASS_5G, NetworkClassifier.networkClass(TYPE_LTE, OVERRIDE_NR_ADVANCED))
    }

    @Test
    fun `legacy radios are other`() {
        assertEquals(CLASS_OTHER, NetworkClassifier.networkClass(3, OVERRIDE_NONE))   // UMTS
        assertEquals(CLASS_OTHER, NetworkClassifier.networkClass(10, OVERRIDE_NONE))  // HSPA
        assertEquals(CLASS_OTHER, NetworkClassifier.networkClass(2, OVERRIDE_NONE))   // EDGE
    }

    @Test
    fun `missing network type is unknown`() {
        assertEquals(CLASS_UNKNOWN, NetworkClassifier.networkClass(TYPE_UNKNOWN, OVERRIDE_NONE))
    }
}
