package com.example.netscopepoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Replays the 2026-11-08 field log through the attribution rules.
 *
 * The log contains two counter rebases (40,891 MB and 41,333 MB) that the old
 * build counted as 5G. This test locks in the corrected behaviour on real data:
 * the rebases are excluded, and observed mobile still reconciles with
 * NetworkStats.
 */
class FieldCsvReplayTest {

    private val mb = 1024L * 1024L

    private data class Row(
        val elapsedMs: Long,
        val rx: Long,
        val tx: Long,
        val transport: String,
        val cls: String,
        val nsMobileToday: Long,
    )

    private class Replayer {
        var last: MobileCounters? = null
        var lastElapsed = 0L
        var lastNs = -1L
        var highWaterRx = 0L
        var highWaterTx = 0L
        var pending = false
        var rebaseHighWaterRx = 0L
        var rebaseHighWaterTx = 0L
        var resetAt = 0L
        var previousClass = NetworkClassifier.CLASS_UNKNOWN
        var lastStableClass = NetworkClassifier.CLASS_UNKNOWN

        val totals = mutableMapOf<String, Long>()
        var ambiguousTotal = 0L
        var rebaseTotal = 0L
        var resetEvents = 0

        fun sample(row: Row): Attribution {
            val current = MobileCounters(row.rx, row.tx)
            val nsDelta = if (lastNs >= 0L && row.nsMobileToday >= lastNs) {
                row.nsMobileToday - lastNs
            } else {
                null
            }
            val recovering = pending &&
                (current.rx < rebaseHighWaterRx || current.tx < rebaseHighWaterTx)
            val attribution = TrafficAttributor.attribute(
                previous = last,
                current = current,
                previousElapsedMs = if (lastElapsed == 0L) row.elapsedMs else lastElapsed,
                elapsedMs = row.elapsedMs,
                ownClass = row.cls,
                previousClass = previousClass,
                lastStableClass = lastStableClass,
                onWifi = row.transport == "wifi",
                transitions = emptyList(),
                nsDeltaBytes = nsDelta,
                recoveringTowardHighWater = recovering,
                upperBoundTo4g = true,
            )
            attribution.assignments.forEach {
                totals[it.bucket] = (totals[it.bucket] ?: 0L) + it.bytes
            }
            ambiguousTotal += attribution.ambiguousBytes
            rebaseTotal += attribution.rebaseBytes
            if (attribution.resetDetected) {
                resetEvents += 1
                pending = true
                rebaseHighWaterRx = maxOf(rebaseHighWaterRx, highWaterRx, last?.rx ?: 0L)
                rebaseHighWaterTx = maxOf(rebaseHighWaterTx, highWaterTx, last?.tx ?: 0L)
                resetAt = row.elapsedMs
            } else {
                highWaterRx = maxOf(highWaterRx, current.rx)
                highWaterTx = maxOf(highWaterTx, current.tx)
                val recovered = current.rx >= rebaseHighWaterRx && current.tx >= rebaseHighWaterTx
                if (pending && (recovered || row.elapsedMs - resetAt > 60_000L)) {
                    pending = false
                    rebaseHighWaterRx = 0L
                    rebaseHighWaterTx = 0L
                }
            }
            last = current
            lastElapsed = row.elapsedMs
            lastNs = row.nsMobileToday
            previousClass = row.cls
            if (row.transport == "cell" && row.cls != NetworkClassifier.CLASS_UNKNOWN) {
                lastStableClass = row.cls
            }
            return attribution
        }
    }

    @Test
    fun `field log rebases are excluded and totals reconcile`() {
        val rows = loadRows()
        assertEquals(339, rows.size)

        val r = Replayer()
        rows.forEach { r.sample(it) }

        val fourG = r.totals[NetworkClassifier.CLASS_4G] ?: 0L
        val fiveG = r.totals[NetworkClassifier.CLASS_5G] ?: 0L
        val other = r.totals[NetworkClassifier.CLASS_OTHER] ?: 0L
        val unknown = r.totals[NetworkClassifier.CLASS_UNKNOWN] ?: 0L
        val observed = fourG + fiveG + other + unknown
        val nsSession = rows.last().nsMobileToday - rows.first().nsMobileToday

        // Two phantom jumps of 40.9 GB and 41.3 GB must be excluded, not counted.
        assertTrue("rebase total was ${r.rebaseTotal / mb} MB", r.rebaseTotal in 82_000 * mb..82_500 * mb)
        assertEquals(1, r.resetEvents)

        // 4G must not be under-counted: the LTE-CA flapping and transition bytes belong here.
        assertTrue("4G was ${fourG / mb} MB", fourG in 185 * mb..195 * mb)
        assertTrue("5G was ${fiveG / mb} MB", fiveG in 680 * mb..692 * mb)
        assertTrue("observed was ${observed / mb} MB", observed in 870 * mb..880 * mb)
        assertTrue("unknown was ${unknown / mb} MB", unknown < 1 * mb)

        // The real pipeline matched NetworkStats to 0.01%; stay within 2 MB.
        assertTrue(
            "reconciliation was ${(nsSession - observed) / mb} MB",
            abs(nsSession - observed) < 2 * mb,
        )

        // Ambiguity is reported, not hidden.
        assertTrue("ambiguous was ${r.ambiguousTotal / mb} MB", r.ambiguousTotal in 20 * mb..35 * mb)
    }

    private fun loadRows(): List<Row> {
        val stream = javaClass.getResourceAsStream("/field_2026-11-08.csv")
            ?: error("field CSV fixture missing")
        return stream.bufferedReader().useLines { lines ->
            lines.drop(1).filter { it.isNotBlank() }.map { line ->
                val c = line.split(",")
                Row(
                    elapsedMs = c[1].toLong(),
                    rx = c[3].toLong(),
                    tx = c[4].toLong(),
                    transport = c[10],
                    cls = c[11],
                    nsMobileToday = c[14].toLong(),
                )
            }.toList()
        }
    }
}
