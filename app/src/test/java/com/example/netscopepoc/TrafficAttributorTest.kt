package com.example.netscopepoc

import com.example.netscopepoc.NetworkClassifier.BUCKET_REBASE
import com.example.netscopepoc.NetworkClassifier.CLASS_4G
import com.example.netscopepoc.NetworkClassifier.CLASS_5G
import com.example.netscopepoc.NetworkClassifier.CLASS_UNKNOWN
import com.example.netscopepoc.NetworkClassifier.REASON_MIXED_TO_4G
import com.example.netscopepoc.NetworkClassifier.REASON_NORMAL
import com.example.netscopepoc.NetworkClassifier.REASON_RADIO_TRANSITION
import com.example.netscopepoc.NetworkClassifier.REASON_REBASE
import com.example.netscopepoc.NetworkClassifier.REASON_REBASE_UNVERIFIED
import com.example.netscopepoc.NetworkClassifier.REASON_SAMPLING_GAP
import com.example.netscopepoc.NetworkClassifier.REASON_WIFI_MOBILE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficAttributorTest {

    private val mb = 1024L * 1024L

    private fun counters(rx: Long, tx: Long = 0L) = MobileCounters(rx, tx)

    private fun attribute(
        previous: MobileCounters?,
        current: MobileCounters,
        ownClass: String = CLASS_4G,
        previousClass: String = ownClass,
        lastStableClass: String = ownClass,
        onWifi: Boolean = false,
        transitions: List<ClassTransition> = emptyList(),
        nsDelta: Long? = null,
        recoveringTowardHighWater: Boolean = false,
        upperBound: Boolean = false,
        previousElapsed: Long = 0L,
        elapsed: Long = 5_000L,
    ) = TrafficAttributor.attribute(
        previous = previous,
        current = current,
        previousElapsedMs = previousElapsed,
        elapsedMs = elapsed,
        ownClass = ownClass,
        previousClass = previousClass,
        lastStableClass = lastStableClass,
        onWifi = onWifi,
        transitions = transitions,
        nsDeltaBytes = nsDelta,
        recoveringTowardHighWater = recoveringTowardHighWater,
        upperBoundTo4g = upperBound,
    )

    private fun Attribution.bytesFor(bucket: String): Long =
        assignments.filter { it.bucket == bucket }.sumOf { it.bytes }

    @Test
    fun `first sample establishes a baseline without inventing bytes`() {
        val result = attribute(null, counters(1_000), ownClass = CLASS_5G)
        assertEquals(0, result.attributedBytes)
        assertEquals(REASON_NORMAL, result.reason)
    }

    @Test
    fun `rx and tx deltas are added and attributed to the observed class`() {
        val result = attribute(counters(1_000, 500), counters(2_500, 700), ownClass = CLASS_5G)
        assertEquals(CLASS_5G, result.assignments.single().bucket)
        assertEquals(1_700, result.attributedBytes)
    }

    @Test
    fun `counter decrease is a reset with no bytes`() {
        val result = attribute(counters(10_000, 5_000), counters(100, 6_000))
        assertTrue(result.resetDetected)
        assertEquals(0, result.attributedBytes)
        assertEquals(NetworkClassifier.REASON_RESET, result.reason)
    }

    @Test
    fun `masked reset in one direction is detected`() {
        // RX drops from 10,000 to 100 while TX grows from 1,000 to 20,000.
        // Comparing only the combined total would miss this reset.
        val result = attribute(counters(10_000, 1_000), counters(100, 20_000))
        assertTrue(result.resetDetected)
        assertEquals(NetworkClassifier.REASON_RESET, result.reason)
        assertEquals(0, result.attributedBytes)
    }

    @Test
    fun `rebase recovery after a drop is excluded, not counted as 5G`() {
        val highWater = 40_000 * mb
        val result = attribute(
            previous = counters(1 * mb),
            current = counters(highWater),
            ownClass = CLASS_5G,
            recoveringTowardHighWater = true,
        )
        assertEquals(0, result.attributedBytes)
        assertEquals(highWater - 1 * mb, result.rebaseBytes)
        assertEquals(REASON_REBASE_UNVERIFIED, result.reason)
        assertFalse(result.resetDetected)
    }

    @Test
    fun `rebase recovery keeps only NetworkStats-corroborated bytes as real`() {
        val highWater = 40_000 * mb
        val corroborated = 2_000L
        val result = attribute(
            previous = counters(1 * mb),
            current = counters(highWater),
            ownClass = CLASS_5G,
            nsDelta = corroborated,
            recoveringTowardHighWater = true,
        )
        assertEquals(corroborated, result.bytesFor(CLASS_UNKNOWN))
        assertEquals(highWater - 1 * mb - corroborated, result.rebaseBytes)
        assertEquals(REASON_REBASE, result.reason)
    }

    @Test
    fun `implausible jump corroborated by NetworkStats sends only corroborated bytes to unknown`() {
        val delta = 40_000 * mb
        val corroborated = 20_000L
        val result = attribute(
            previous = counters(1 * mb),
            current = counters(1 * mb + delta),
            ownClass = CLASS_5G,
            nsDelta = corroborated,
        )
        assertEquals(corroborated, result.bytesFor(CLASS_UNKNOWN))
        assertEquals(delta - corroborated, result.rebaseBytes)
        assertEquals(REASON_REBASE, result.reason)
    }

    @Test
    fun `implausible jump without NetworkStats is an unverified rebase`() {
        val delta = 40_000 * mb
        val result = attribute(
            previous = counters(1 * mb),
            current = counters(1 * mb + delta),
            ownClass = CLASS_5G,
            nsDelta = null,
        )
        assertEquals(0, result.attributedBytes)
        assertEquals(delta, result.rebaseBytes)
        assertEquals(REASON_REBASE_UNVERIFIED, result.reason)
    }

    @Test
    fun `jump above the NetworkStats bound is flagged below the physical ceiling`() {
        // The 5 s physical ceiling is 750 MB, so the old rule would have
        // accepted this 300 MB jump. NetworkStats only saw 1 MB.
        val result = attribute(
            previous = counters(0),
            current = counters(300 * mb),
            ownClass = CLASS_5G,
            nsDelta = 1 * mb,
            previousElapsed = 0L,
            elapsed = 5_000L,
        )
        assertEquals(REASON_REBASE, result.reason)
        assertEquals(1 * mb, result.bytesFor(CLASS_UNKNOWN))
        assertEquals(299 * mb, result.rebaseBytes)
    }

    @Test
    fun `jump below the minimum discontinuity is accepted`() {
        val result = attribute(
            previous = counters(0),
            current = counters(200 * mb),
            ownClass = CLASS_5G,
            nsDelta = 1 * mb,
            previousElapsed = 0L,
            elapsed = 5_000L,
        )
        assertEquals(200 * mb, result.bytesFor(CLASS_5G))
        assertEquals(0, result.rebaseBytes)
    }

    @Test
    fun `recovery toward high water flags a jump the normal bound would allow`() {
        // 100 MB is below the normal 256 MB floor, but it is a recovery while
        // the counter is still under its pre-drop high-water mark.
        val result = attribute(
            previous = counters(0),
            current = counters(100 * mb),
            ownClass = CLASS_5G,
            nsDelta = 1 * mb,
            recoveringTowardHighWater = true,
            previousElapsed = 0L,
            elapsed = 5_000L,
        )
        assertEquals(REASON_REBASE, result.reason)
        assertEquals(1 * mb, result.bytesFor(CLASS_UNKNOWN))
        assertEquals(99 * mb, result.rebaseBytes)
    }

    @Test
    fun `a plausible fast transfer is not mistaken for a rebase`() {
        val delta = 500 * mb
        val result = attribute(
            previous = counters(1 * mb),
            current = counters(1 * mb + delta),
            ownClass = CLASS_5G,
            nsDelta = delta,
        )
        assertEquals(delta, result.bytesFor(CLASS_5G))
        assertEquals(0, result.rebaseBytes)
        assertEquals(REASON_NORMAL, result.reason)
    }

    @Test
    fun `small increase after a reset is real traffic, not a rebase`() {
        val result = attribute(
            previous = counters(1 * mb),
            current = counters(2 * mb),
            ownClass = CLASS_4G,
            recoveringTowardHighWater = true,
        )
        assertEquals(1 * mb, result.bytesFor(CLASS_4G))
        assertEquals(0, result.rebaseBytes)
    }

    @Test
    fun `radio transition is split by time spent in each class`() {
        val result = attribute(
            previous = counters(0),
            current = counters(1_000),
            previousClass = CLASS_4G,
            ownClass = CLASS_5G,
            transitions = listOf(ClassTransition(4_000L, CLASS_5G)),
            previousElapsed = 0L,
            elapsed = 5_000L,
        )
        // 80% of the interval was spent on 4G, 20% on 5G.
        assertEquals(800, result.bytesFor(CLASS_4G))
        assertEquals(200, result.bytesFor(CLASS_5G))
        assertEquals(REASON_RADIO_TRANSITION, result.reason)
    }

    @Test
    fun `radio transition split with a larger delta`() {
        val result = attribute(
            previous = counters(0),
            current = counters(400 * mb),
            previousClass = CLASS_4G,
            ownClass = CLASS_5G,
            transitions = listOf(ClassTransition(4_000L, CLASS_5G)),
            previousElapsed = 0L,
            elapsed = 5_000L,
        )
        assertEquals(320 * mb, result.bytesFor(CLASS_4G))
        assertEquals(80 * mb, result.bytesFor(CLASS_5G))
        assertEquals(80 * mb, result.ambiguousBytes)
    }

    @Test
    fun `multiple transitions in one interval are split by time`() {
        // 4G for 2 s, 5G for 2 s, then 4G for 1 s.
        val result = attribute(
            previous = counters(0),
            current = counters(1_000),
            previousClass = CLASS_4G,
            ownClass = CLASS_4G,
            transitions = listOf(
                ClassTransition(2_000L, CLASS_5G),
                ClassTransition(4_000L, CLASS_4G),
            ),
            previousElapsed = 0L,
            elapsed = 5_000L,
        )
        assertEquals(600, result.bytesFor(CLASS_4G))
        assertEquals(400, result.bytesFor(CLASS_5G))
        assertEquals(REASON_RADIO_TRANSITION, result.reason)
        assertEquals(400, result.ambiguousBytes)
    }

    @Test
    fun `mixed interval without a timestamp goes to 4G and is reported ambiguous`() {
        val result = attribute(
            previous = counters(0),
            current = counters(100 * mb),
            previousClass = CLASS_5G,
            ownClass = CLASS_4G,
        )
        assertEquals(100 * mb, result.bytesFor(CLASS_4G))
        assertEquals(100 * mb, result.ambiguousBytes)
        assertEquals(REASON_MIXED_TO_4G, result.reason)
    }

    @Test
    fun `mobile bytes while on Wi-Fi use the last stable mobile class`() {
        val result = attribute(
            previous = counters(0),
            current = counters(50 * mb),
            ownClass = CLASS_5G,
            lastStableClass = CLASS_5G,
            onWifi = true,
        )
        assertEquals(50 * mb, result.bytesFor(CLASS_5G))
        assertEquals(50 * mb, result.ambiguousBytes)
        assertEquals(REASON_WIFI_MOBILE, result.reason)
    }

    @Test
    fun `late mobile bytes with no known class stay unknown unless the 4G upper bound is on`() {
        val unknown = attribute(
            previous = counters(0),
            current = counters(50 * mb),
            lastStableClass = CLASS_UNKNOWN,
            ownClass = CLASS_UNKNOWN,
            onWifi = true,
        )
        assertEquals(CLASS_UNKNOWN, unknown.assignments.single().bucket)

        val upper = attribute(
            previous = counters(0),
            current = counters(50 * mb),
            lastStableClass = CLASS_UNKNOWN,
            ownClass = CLASS_UNKNOWN,
            onWifi = true,
            upperBound = true,
        )
        assertEquals(CLASS_4G, upper.assignments.single().bucket)
        assertEquals(50 * mb, upper.ambiguousBytes)
    }

    @Test
    fun `upper bound keeps a known last mobile class`() {
        val result = attribute(
            previous = counters(0),
            current = counters(50 * mb),
            ownClass = CLASS_5G,
            lastStableClass = CLASS_5G,
            onWifi = true,
            upperBound = true,
        )
        assertEquals(CLASS_5G, result.assignments.single().bucket)
    }

    @Test
    fun `upper bound keeps a time-weighted split`() {
        val result = attribute(
            previous = counters(0),
            current = counters(1_000),
            previousClass = CLASS_4G,
            ownClass = CLASS_5G,
            transitions = listOf(ClassTransition(4_000L, CLASS_5G)),
            upperBound = true,
            previousElapsed = 0L,
            elapsed = 5_000L,
        )
        assertEquals(800, result.bytesFor(CLASS_4G))
        assertEquals(200, result.bytesFor(CLASS_5G))
    }

    @Test
    fun `sampling gap is attributed best-effort and marked`() {
        val result = attribute(
            previous = counters(0),
            current = counters(10 * mb),
            lastStableClass = CLASS_4G,
            previousElapsed = 0L,
            elapsed = 30_000L,
        )
        assertEquals(10 * mb, result.bytesFor(CLASS_4G))
        assertEquals(REASON_SAMPLING_GAP, result.reason)
        assertEquals(10 * mb, result.ambiguousBytes)
    }

    @Test
    fun `upper bound keeps a known last class on a sampling gap`() {
        val result = attribute(
            previous = counters(0),
            current = counters(10 * mb),
            lastStableClass = CLASS_5G,
            ownClass = CLASS_5G,
            upperBound = true,
            previousElapsed = 0L,
            elapsed = 30_000L,
        )
        assertEquals(CLASS_5G, result.assignments.single().bucket)
        assertEquals(REASON_SAMPLING_GAP, result.reason)
    }

    @Test
    fun `unsupported counters are unknown and produce no bytes`() {
        val result = attribute(counters(1_000), counters(-1, -1))
        assertEquals(CLASS_UNKNOWN, result.assignments.single().bucket)
        assertEquals(0, result.attributedBytes)
    }

    @Test
    fun `idle interval keeps the observed class with zero bytes`() {
        val result = attribute(counters(1_000), counters(1_000), ownClass = CLASS_5G, onWifi = true)
        assertEquals(CLASS_5G, result.assignments.single().bucket)
        assertEquals(0, result.attributedBytes)
    }

    @Test
    fun `upper bound does not move a clearly observed 5G interval`() {
        val result = attribute(
            previous = counters(0),
            current = counters(10 * mb),
            ownClass = CLASS_5G,
            upperBound = true,
        )
        assertEquals(10 * mb, result.bytesFor(CLASS_5G))
        assertEquals(0, result.bytesFor(CLASS_4G))
    }

    @Test
    fun `rebase bucket is not a radio class`() {
        assertEquals("counter_rebase", BUCKET_REBASE)
        assertFalse(arrayOf(CLASS_4G, CLASS_5G, CLASS_UNKNOWN).contains(BUCKET_REBASE))
    }
}
