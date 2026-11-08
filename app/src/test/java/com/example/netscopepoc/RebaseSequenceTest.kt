package com.example.netscopepoc

import com.example.netscopepoc.NetworkClassifier.CLASS_5G
import com.example.netscopepoc.NetworkClassifier.CLASS_UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays the two discontinuities observed in the field CSV through the same
 * state machine LoggerService uses. Guards against ordering mistakes between
 * attribution and rebase bookkeeping.
 */
class RebaseSequenceTest {

    private val mb = 1024L * 1024L

    private class Replayer {
        var last: MobileCounters? = null
        var lastElapsed = 0L
        var highWaterRx = 0L
        var highWaterTx = 0L
        var pending = false
        var rebaseHighWaterRx = 0L
        var rebaseHighWaterTx = 0L
        var resetAt = 0L
        val totals = mutableMapOf<String, Long>()
        var rebaseTotal = 0L
        var resetEvents = 0

        fun sample(now: Long, rx: Long, tx: Long, cls: String, ns: Long?): Attribution {
            val current = MobileCounters(rx, tx)
            val recovering = pending &&
                (current.rx < rebaseHighWaterRx || current.tx < rebaseHighWaterTx)
            val attribution = TrafficAttributor.attribute(
                previous = last,
                current = current,
                previousElapsedMs = if (lastElapsed == 0L) now else lastElapsed,
                elapsedMs = now,
                ownClass = cls,
                previousClass = cls,
                lastStableClass = cls,
                onWifi = false,
                transitions = emptyList(),
                nsDeltaBytes = ns,
                recoveringTowardHighWater = recovering,
                upperBoundTo4g = true,
            )
            attribution.assignments.forEach {
                totals[it.bucket] = (totals[it.bucket] ?: 0L) + it.bytes
            }
            rebaseTotal += attribution.rebaseBytes
            if (attribution.resetDetected) {
                resetEvents += 1
                pending = true
                rebaseHighWaterRx = maxOf(rebaseHighWaterRx, highWaterRx, last?.rx ?: 0L)
                rebaseHighWaterTx = maxOf(rebaseHighWaterTx, highWaterTx, last?.tx ?: 0L)
                resetAt = now
            } else {
                highWaterRx = maxOf(highWaterRx, current.rx)
                highWaterTx = maxOf(highWaterTx, current.tx)
                val recovered = current.rx >= rebaseHighWaterRx && current.tx >= rebaseHighWaterTx
                if (pending && (recovered || now - resetAt > 60_000L)) {
                    pending = false
                    rebaseHighWaterRx = 0L
                    rebaseHighWaterTx = 0L
                }
            }
            last = current
            lastElapsed = now
            return attribution
        }
    }

    private val staleLow = 335_382L + 422_220L
    private val jumpOne = 41_245_846_584L + 1_632_484_014L

    @Test
    fun `rebase when logging starts while mobile data is off`() {
        val r = Replayer()
        r.sample(0L, 335_382L, 422_220L, CLASS_5G, ns = null)
        val jump = r.sample(5_000L, 41_245_846_584L, 1_632_484_014L, CLASS_5G, ns = 20_000L)
        assertEquals(NetworkClassifier.REASON_REBASE, jump.reason)
        assertEquals(20_000L, r.totals[CLASS_UNKNOWN])
        assertEquals(0L, r.totals[CLASS_5G])
        assertTrue(r.rebaseTotal > 40_000L * mb)

        r.sample(10_000L, 41_245_846_584L + 30 * mb, 1_632_484_014L, CLASS_5G, ns = 30 * mb)
        assertEquals(30 * mb, r.totals[CLASS_5G])
    }

    @Test
    fun `reset then recovery is excluded, and normal sampling resumes`() {
        val r = Replayer()
        r.sample(0L, 335_382L, 422_220L, CLASS_5G, ns = null)
        r.sample(5_000L, 41_245_846_584L, 1_632_484_014L, CLASS_5G, ns = 20_000L)
        r.sample(10_000L, 41_245_846_584L + 30 * mb, 1_632_484_014L, CLASS_5G, ns = 30 * mb)

        val reset = r.sample(15_000L, 335_382L, 422_220L, CLASS_5G, ns = 0L)
        assertTrue(reset.resetDetected)
        assertEquals(1, r.resetEvents)

        val recovery = r.sample(
            20_000L,
            41_245_846_584L + 30 * mb,
            1_632_484_014L,
            CLASS_5G,
            ns = 10_000L,
        )
        assertEquals(NetworkClassifier.REASON_REBASE, recovery.reason)
        assertEquals(30_000L, r.totals[CLASS_UNKNOWN])

        r.sample(25_000L, 41_245_846_584L + 70 * mb, 1_632_484_014L, CLASS_5G, ns = 40 * mb)
        assertEquals(70 * mb, r.totals[CLASS_5G])
        assertTrue(r.rebaseTotal > 80_000L * mb)
    }

    @Test
    fun `stale counter reappearing without a prior drop is still caught`() {
        val r = Replayer()
        // Logging starts with mobile data off; the counter is the stale low value.
        r.sample(0L, 335_382L, 422_220L, CLASS_5G, ns = null)
        // Counter reappears at a plausible-looking value but NetworkStats contradicts it.
        val after = r.sample(5_000L, 41_245_846_584L, 1_632_484_014L, CLASS_5G, ns = 20_000L)
        assertEquals(NetworkClassifier.REASON_REBASE, after.reason)
        assertEquals(0L, r.totals[CLASS_5G])
        assertTrue(r.rebaseTotal > 40_000L * mb)
    }

    @Test
    fun `rebase after a process restart with a lost high water mark is still excluded`() {
        val r = Replayer()
        // The app starts while the radio is down; TrafficStats exposes the stale
        // low value and the session's high-water mark is therefore tiny.
        r.sample(0L, 340_862L, 427_972L, CLASS_5G, ns = null)
        // The radio returns and zeroes the counter before it rebases.
        val reset = r.sample(5_000L, 0L, 0L, CLASS_5G, ns = 0L)
        assertTrue(reset.resetDetected)
        // The true since-boot counter reappears. NetworkStats corroborates only
        // a few kilobytes, so the rest must be excluded, not counted as 5G.
        val jump = r.sample(10_000L, 42_146_758_438L, 1_649_533_003L, CLASS_5G, ns = 6_150L)
        assertEquals(NetworkClassifier.REASON_REBASE, jump.reason)
        assertEquals(6_150L, r.totals[CLASS_UNKNOWN])
        assertEquals(0L, r.totals[CLASS_5G])
        assertTrue(r.rebaseTotal > 40_000L * mb)
    }

    @Test
    fun `masked reset in one direction is detected`() {
        val r = Replayer()
        r.sample(0L, 10 * mb, 10 * mb, CLASS_5G, ns = null)
        // RX drops to zero while TX grows enough to keep the total unchanged.
        val reset = r.sample(5_000L, 0L, 20 * mb, CLASS_5G, ns = 0L)
        assertTrue(reset.resetDetected)
        assertEquals(1, r.resetEvents)
    }

    @Test
    fun `recovery toward high water is excluded as a rebase`() {
        val r = Replayer()
        r.sample(0L, 100 * mb, 0L, CLASS_5G, ns = null)
        val reset = r.sample(5_000L, 0L, 0L, CLASS_5G, ns = 0L)
        assertTrue(reset.resetDetected)
        // RX recovers to only 50 MB, still below the 100 MB high-water mark.
        val recovery = r.sample(10_000L, 50 * mb, 0L, CLASS_5G, ns = 1 * mb)
        assertEquals(NetworkClassifier.REASON_REBASE, recovery.reason)
        assertEquals(1 * mb, r.totals[CLASS_UNKNOWN])
        assertEquals(49 * mb, recovery.rebaseBytes)
    }
}
