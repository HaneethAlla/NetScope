package com.example.netscopepoc

/**
 * Pure classification logic for POC-01.
 *
 * The constants mirror android.telephony.TelephonyManager and
 * android.telephony.TelephonyDisplayInfo. They are repeated here so this file
 * has no Android dependency and can be covered by plain JVM unit tests.
 */
object NetworkClassifier {
    const val CLASS_4G = "4g"
    const val CLASS_5G = "5g"
    const val CLASS_OTHER = "other"
    const val CLASS_UNKNOWN = "unknown"

    /** Extra buckets that are not radio classes. */
    const val BUCKET_RESET = "reset"
    const val BUCKET_REBASE = "counter_rebase"

    /** Why an interval was attributed the way it was; written to the CSV. */
    const val REASON_NORMAL = "normal"
    const val REASON_RADIO_TRANSITION = "radio_transition"
    const val REASON_WIFI_MOBILE = "wifi_mobile"
    const val REASON_RESET = "counter_reset"
    const val REASON_REBASE = "counter_rebase"
    const val REASON_REBASE_UNVERIFIED = "counter_rebase_unverified"
    const val REASON_SAMPLING_GAP = "sampling_gap"
    const val REASON_MIXED_TO_4G = "mixed_to_4g"
    const val REASON_UNSUPPORTED = "unsupported_counters"

    // TelephonyManager.NETWORK_TYPE_*
    const val TYPE_UNKNOWN = 0
    const val TYPE_LTE = 13
    const val TYPE_LTE_CA = 19
    const val TYPE_NR = 20

    // TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_*
    const val OVERRIDE_NONE = 0
    const val OVERRIDE_LTE_CA = 1
    const val OVERRIDE_LTE_ADVANCED_PRO = 2
    const val OVERRIDE_NR_NSA = 3
    const val OVERRIDE_NR_NSA_MMWAVE = 4
    const val OVERRIDE_NR_ADVANCED = 5

    /**
     * Assigns an observed radio state to a usage bucket.
     *
     * NR is 5G. LTE is 4G unless the display info exposes an NR override
     * (NSA, NSA mmWave, or NR advanced). LTE carrier aggregation is always 4G:
     * `OVERRIDE_NETWORK_TYPE_LTE_CA` is a capacity hint, not a generation.
     */
    fun networkClass(networkType: Int, overrideNetworkType: Int): String = when (networkType) {
        TYPE_NR -> CLASS_5G
        TYPE_LTE, TYPE_LTE_CA -> when (overrideNetworkType) {
            OVERRIDE_NR_NSA, OVERRIDE_NR_NSA_MMWAVE, OVERRIDE_NR_ADVANCED -> CLASS_5G
            else -> CLASS_4G
        }
        TYPE_UNKNOWN -> CLASS_UNKNOWN
        else -> CLASS_OTHER
    }
}

/** Cumulative mobile counters as reported by TrafficStats. Negative means unsupported. */
data class MobileCounters(val rx: Long, val tx: Long) {
    val total: Long get() = rx + tx
    val supported: Boolean get() = rx >= 0 && tx >= 0
}

/** A radio class change observed by the display-info callback. */
data class ClassTransition(val atElapsedMs: Long, val newClass: String)

/** Bytes attributed to one bucket. `ambiguousBytes` is the part assigned on a best-effort basis. */
data class BucketBytes(val bucket: String, val bytes: Long, val ambiguousBytes: Long = 0)

/** Result of attributing the byte delta of one sampling interval. */
data class Attribution(
    val assignments: List<BucketBytes>,
    val reason: String,
    val ambiguousBytes: Long = 0,
    val rebaseBytes: Long = 0,
    val resetDetected: Boolean = false,
    val corroboratedBytes: Long? = null,
) {
    val attributedBytes: Long get() = assignments.sumOf { it.bytes }
}

/**
 * Attributes the delta between two TrafficStats snapshots.
 *
 * Design goals, in order:
 * 1. Never fabricate or hide bytes. Every observed byte lands in a bucket or in
 *    the explicit `counter_rebase` exclusion.
 * 2. Never let a counter rebase (mobile data off -> on) look like 4G/5G traffic.
 * 3. Do not under-count 4G. An unresolved mixed interval is assigned to 4G;
 *    the amount is reported as ambiguous so the over-count is visible.
 * 4. Apportion a radio transition by the time spent in each state when usable
 *    callback timestamps are available; fall back to the conservative 4G
 *    tie-break when they are not. Without timestamps there is no duration
 *    information, so a "longer-duration class" fallback is not possible.
 */
object TrafficAttributor {
    /** 150 MB/s sustained is beyond anything Jio delivers to this device. */
    const val MAX_PLAUSIBLE_BYTES_PER_SECOND = 150L * 1024 * 1024
    const val MIN_DISCONTINUITY_BYTES = 256L * 1024 * 1024

    /**
     * A delta above 4x the NetworkStats mobile delta is not corroborated by the
     * independent counter. The 256 MB floor keeps normal bursts from tripping it.
     */
    const val NS_CORROBORATION_FACTOR = 4L

    /**
     * While a counter is recovering toward its pre-drop high-water mark, use a
     * lower floor so a masked rebase is not mistaken for real traffic. NetworkStats
     * must still corroborate at least a quarter of the increase.
     */
    const val RECOVERY_MIN_BYTES = 32L * 1024 * 1024

    const val SAMPLING_GAP_MS = 10_000L

    fun attribute(
        previous: MobileCounters?,
        current: MobileCounters,
        previousElapsedMs: Long,
        elapsedMs: Long,
        ownClass: String,
        previousClass: String,
        lastStableClass: String,
        onWifi: Boolean,
        transitions: List<ClassTransition>,
        nsDeltaBytes: Long?,
        recoveringTowardHighWater: Boolean,
        upperBoundTo4g: Boolean,
    ): Attribution {
        if (!current.supported) {
            return Attribution(
                listOf(BucketBytes(NetworkClassifier.CLASS_UNKNOWN, 0)),
                NetworkClassifier.REASON_UNSUPPORTED,
            )
        }
        if (previous == null || !previous.supported) {
            // First sample establishes the baseline; it is not traffic.
            return Attribution(
                listOf(BucketBytes(normalize(ownClass), 0)),
                NetworkClassifier.REASON_NORMAL,
            )
        }

        // Check each direction separately. A drop in RX can be hidden by growth
        // in TX when only the combined total is compared.
        if (current.rx < previous.rx || current.tx < previous.tx) {
            return Attribution(
                assignments = emptyList(),
                reason = NetworkClassifier.REASON_RESET,
                resetDetected = true,
            )
        }

        val delta = current.total - previous.total
        if (delta == 0L) {
            return Attribution(
                listOf(BucketBytes(normalize(ownClass), 0)),
                NetworkClassifier.REASON_NORMAL,
            )
        }

        val intervalMs = (elapsedMs - previousElapsedMs).coerceAtLeast(1L)
        val physicalCeiling = maxOf(
            MIN_DISCONTINUITY_BYTES,
            MAX_PLAUSIBLE_BYTES_PER_SECOND * intervalMs / 1000L,
        )
        val corroborated = nsDeltaBytes?.coerceAtLeast(0L)?.coerceAtMost(delta)
        val nsCeiling = corroborated?.let {
            maxOf(MIN_DISCONTINUITY_BYTES, it * NS_CORROBORATION_FACTOR)
        }

        // The bound applies to every interval, not only physically implausible
        // ones: if TrafficStats moves much faster than NetworkStats, the extra is
        // a counter discontinuity. Taking the smaller limit makes the
        // NetworkStats bound effective even when the physical ceiling is high.
        val discontinuityLimit = if (nsCeiling != null) {
            minOf(physicalCeiling, nsCeiling)
        } else {
            physicalCeiling
        }
        val limit = if (recoveringTowardHighWater && corroborated != null) {
            minOf(
                discontinuityLimit,
                maxOf(RECOVERY_MIN_BYTES, corroborated * NS_CORROBORATION_FACTOR),
            )
        } else {
            discontinuityLimit
        }

        if (delta > limit) {
            return discontinuity(delta, corroborated)
        }

        if (intervalMs > SAMPLING_GAP_MS) {
            return bestEffort(
                delta = delta,
                lastStableClass = lastStableClass,
                ownClass = ownClass,
                upperBoundTo4g = upperBoundTo4g,
                reason = NetworkClassifier.REASON_SAMPLING_GAP,
            )
        }

        if (transitions.isNotEmpty() || previousClass != ownClass) {
            val segments = buildTimeline(
                previousElapsedMs = previousElapsedMs,
                elapsedMs = elapsedMs,
                previousClass = previousClass,
                ownClass = ownClass,
                transitions = transitions,
            )
            if (segments != null && segments.size > 1) {
                return splitByTime(delta, segments)
            }
            if (previousClass != ownClass) {
                // No usable transition timeline. Use the 4G tie-break so 4G is
                // never under-counted; the whole amount is reported as ambiguous.
                return mixedFallback(delta, previousClass, ownClass, upperBoundTo4g)
            }
        }

        if (onWifi) {
            // Mobile counters can update after the transport left cellular.
            // Use the last class observed on cellular; never guess 4G blindly.
            // The 4G upper-bound switch is deliberately narrow and does not
            // overwrite this known estimate (see the README).
            val target = when {
                lastStableClass != NetworkClassifier.CLASS_UNKNOWN -> lastStableClass
                upperBoundTo4g -> NetworkClassifier.CLASS_4G
                else -> NetworkClassifier.CLASS_UNKNOWN
            }
            return Attribution(
                listOf(BucketBytes(target, delta, ambiguousBytes = delta)),
                reason = NetworkClassifier.REASON_WIFI_MOBILE,
                ambiguousBytes = delta,
            )
        }

        val target = normalize(ownClass)
        if (target == NetworkClassifier.CLASS_UNKNOWN && upperBoundTo4g) {
            return Attribution(
                listOf(BucketBytes(NetworkClassifier.CLASS_4G, delta, ambiguousBytes = delta)),
                reason = NetworkClassifier.REASON_MIXED_TO_4G,
                ambiguousBytes = delta,
            )
        }
        return Attribution(
            listOf(BucketBytes(target, delta)),
            reason = NetworkClassifier.REASON_NORMAL,
        )
    }

    /**
     * Splits an interval by the time spent in each radio class. The total byte
     * count is preserved exactly; only the split uses observed durations.
     */
    private fun splitByTime(delta: Long, segments: List<Pair<String, Long>>): Attribution {
        val totalDuration = segments.sumOf { it.second }
        if (totalDuration <= 0L) {
            return Attribution(emptyList(), NetworkClassifier.REASON_RADIO_TRANSITION)
        }

        var allocated = 0L
        var cumulative = 0L
        val raw = mutableListOf<BucketBytes>()
        for ((cls, duration) in segments) {
            cumulative += duration
            val target = delta * cumulative / totalDuration
            val bytes = target - allocated
            allocated = target
            if (bytes > 0L) raw += BucketBytes(normalize(cls), bytes)
        }

        // Integer division should land exactly on delta; keep any remainder in
        // the last segment so no byte is lost.
        val remainder = delta - allocated
        if (remainder > 0L && raw.isNotEmpty()) {
            val last = raw[raw.lastIndex]
            raw[raw.lastIndex] = last.copy(bytes = last.bytes + remainder)
        }

        val dominant = raw.maxByOrNull { it.bytes }?.bucket
        val assignments = raw.map {
            if (it.bucket == dominant) it else it.copy(ambiguousBytes = it.bytes)
        }
        val ambiguous = assignments.sumOf { it.ambiguousBytes }
        return Attribution(
            assignments = assignments,
            reason = NetworkClassifier.REASON_RADIO_TRANSITION,
            ambiguousBytes = ambiguous,
        )
    }

    /**
     * Builds the class timeline for one interval from the transitions observed
     * after the previous sample. Returns null when the timeline is incomplete,
     * for example when the class changed but no callback timestamp exists.
     */
    private fun buildTimeline(
        previousElapsedMs: Long,
        elapsedMs: Long,
        previousClass: String,
        ownClass: String,
        transitions: List<ClassTransition>,
    ): List<Pair<String, Long>>? {
        if (elapsedMs <= previousElapsedMs) return null

        val end = normalize(ownClass)
        val sorted = transitions
            .filter { it.atElapsedMs >= previousElapsedMs && it.atElapsedMs <= elapsedMs }
            .sortedBy { it.atElapsedMs }

        var cursor = previousElapsedMs
        var cls = normalize(previousClass)
        val segments = mutableListOf<Pair<String, Long>>()

        for (transition in sorted) {
            val next = normalize(transition.newClass)
            if (next == cls) continue
            val duration = transition.atElapsedMs - cursor
            if (duration > 0L) segments += cls to duration
            cursor = transition.atElapsedMs
            cls = next
        }
        if (cursor < elapsedMs) segments += cls to (elapsedMs - cursor)

        if (segments.isEmpty()) return null
        // If the last observed class does not match the sample's class, a
        // transition was missed and the timeline cannot be trusted.
        if (segments.last().first != end) return null
        return segments
    }

    private fun mixedFallback(
        delta: Long,
        previousClass: String,
        ownClass: String,
        upperBoundTo4g: Boolean,
    ): Attribution {
        val target = when {
            previousClass == NetworkClassifier.CLASS_4G ||
                ownClass == NetworkClassifier.CLASS_4G -> NetworkClassifier.CLASS_4G
            upperBoundTo4g -> NetworkClassifier.CLASS_4G
            else -> normalize(previousClass)
        }
        return Attribution(
            listOf(BucketBytes(target, delta, ambiguousBytes = delta)),
            reason = if (target == NetworkClassifier.CLASS_4G) {
                NetworkClassifier.REASON_MIXED_TO_4G
            } else {
                NetworkClassifier.REASON_RADIO_TRANSITION
            },
            ambiguousBytes = delta,
        )
    }

    private fun bestEffort(
        delta: Long,
        lastStableClass: String,
        ownClass: String,
        upperBoundTo4g: Boolean,
        reason: String,
    ): Attribution {
        // The upper bound is narrow on purpose: a known last-stable class or a
        // known current class is a better estimate than blindly assigning to 4G.
        val target = when {
            lastStableClass != NetworkClassifier.CLASS_UNKNOWN -> lastStableClass
            ownClass != NetworkClassifier.CLASS_UNKNOWN -> normalize(ownClass)
            upperBoundTo4g -> NetworkClassifier.CLASS_4G
            else -> NetworkClassifier.CLASS_UNKNOWN
        }
        return Attribution(
            listOf(BucketBytes(target, delta, ambiguousBytes = delta)),
            reason = reason,
            ambiguousBytes = delta,
        )
    }

    private fun discontinuity(delta: Long, corroborated: Long?): Attribution {
        if (corroborated == null) {
            return Attribution(
                assignments = emptyList(),
                reason = NetworkClassifier.REASON_REBASE_UNVERIFIED,
                rebaseBytes = delta,
            )
        }
        return Attribution(
            assignments = if (corroborated > 0L) {
                listOf(BucketBytes(NetworkClassifier.CLASS_UNKNOWN, corroborated))
            } else {
                emptyList()
            },
            reason = NetworkClassifier.REASON_REBASE,
            rebaseBytes = delta - corroborated,
            corroboratedBytes = corroborated,
        )
    }

    private fun normalize(value: String): String = when (value) {
        NetworkClassifier.CLASS_4G,
        NetworkClassifier.CLASS_5G,
        NetworkClassifier.CLASS_OTHER -> value
        else -> NetworkClassifier.CLASS_UNKNOWN
    }
}
