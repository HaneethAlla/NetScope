package com.example.netscopepoc

import android.Manifest
import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    private val MB = 1024L * 1024L

    override fun onCreate(s: Bundle?) { super.onCreate(s); setContent { MaterialTheme { Screen() } } }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun usageOk() = getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    private fun mb(b: Long) = "%.2f MB".format(b / 1048576.0)
    private fun mbOrNa(b: Long) = if (b < 0) "n/a" else mb(b)
    private fun speed(bps: Long) = when {
        bps >= 1_000_000L -> "%.2f Mbps".format(bps / 1_000_000.0)
        bps >= 1_000L -> "%.1f KB/s".format(bps / 1_000.0)
        else -> "$bps B/s"
    }

    private fun pssMb(): Double? = try {
        val am = getSystemService(ActivityManager::class.java)
        am.getProcessMemoryInfo(intArrayOf(Process.myPid()))
            .firstOrNull()?.totalPss?.let { it / 1024.0 }
    } catch (_: Exception) {
        null
    }

    private fun heapMb(): Double {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / 1048576.0
    }

    private fun dirSize(dir: File): Long =
        if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun apkBytes(): Long = try {
        val ai = packageManager.getPackageInfo(packageName, 0).applicationInfo
        val paths = buildList {
            add(ai.sourceDir)
            ai.splitSourceDirs?.let { addAll(it.toList()) }
        }
        paths.sumOf { File(it).length() }
    } catch (_: Exception) {
        0L
    }
    private fun notificationState() = when {
        Build.VERSION.SDK_INT < 33 -> "not required (Android 12)"
        granted(Manifest.permission.POST_NOTIFICATIONS) -> "granted"
        else -> "denied"
    }

    private fun notificationsOk() =
        Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)

    private fun transportLabel() = when (Live.transport) {
        "cell" -> "cellular"
        "wifi" -> "Wi-Fi"
        "other" -> "other"
        else -> "no network"
    }

    private fun observedMobile(): Long =
        (Live.totals["4g"] ?: 0L) + (Live.totals["5g"] ?: 0L) +
            (Live.totals["other"] ?: 0L) + (Live.totals["unknown"] ?: 0L)

    private fun coveragePercent(observed: Long): Double? =
        if (observed <= 0L) null
        else 100.0 * ((Live.totals["4g"] ?: 0L) + (Live.totals["5g"] ?: 0L)) / observed

    private fun qualityGate(sessionMobile: Long): Long =
        maxOf(10L * MB, sessionMobile.coerceAtLeast(0L) / 100)

    private fun toggleLogging() {
        val i = Intent(this, LoggerService::class.java)
        when {
            !Live.running -> startForegroundService(i)
            // A pending marker must be written before the service dies.
            Live.stopAfterSample -> {
                Live.stopAfterSample = false
                stopService(i)
            }
            Live.note.isNotEmpty() -> Live.stopAfterSample = true
            else -> stopService(i)
        }
    }

    @Composable
    fun Screen() {
        var tick by remember { mutableIntStateOf(0) }
        var marker by remember { mutableStateOf("") }
        var showAllStats by remember { mutableStateOf(false) }
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) { while (true) { delay(2000); tick++ } }

        val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            val f = File(filesDir, "netscope_poc.csv")
            if (uri != null && f.exists()) contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().copyTo(o) }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    Spacer(Modifier.height(24.dp))
                    Text("NetScope",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(horizontal = 16.dp))
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    NavigationDrawerItem(
                        label = { Text("All stats") },
                        selected = showAllStats,
                        onClick = {
                            showAllStats = true
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    Text("Expanded stats view (placeholder for the final design).",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp))
                }
            },
        ) {
            if (showAllStats) {
                AllStatsScreen(tick = tick, onBack = { showAllStats = false })
            } else {
                FrontScreen(
                    tick = tick,
                    marker = marker,
                    onMarkerChange = { marker = it },
                    onOpenMenu = { scope.launch { drawerState.open() } },
                    onRequestPermissions = {
                        val ask = mutableListOf(Manifest.permission.READ_PHONE_STATE)
                        if (Build.VERSION.SDK_INT >= 33) ask += Manifest.permission.POST_NOTIFICATIONS
                        perms.launch(ask.toTypedArray())
                    },
                    onOpenUsageAccess = {
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    },
                    onToggleLogging = { toggleLogging() },
                    onExport = { export.launch("netscope_poc.csv") },
                    onMark = {
                        Live.markerCount += 1
                        Live.note = marker.ifBlank { "mark_${Live.markerCount}" }
                        marker = ""
                    },
                )
            }
        }
    }

    @Composable
    private fun FrontScreen(
        tick: Int,
        marker: String,
        onMarkerChange: (String) -> Unit,
        onOpenMenu: () -> Unit,
        onRequestPermissions: () -> Unit,
        onOpenUsageAccess: () -> Unit,
        onToggleLogging: () -> Unit,
        onExport: () -> Unit,
        onMark: () -> Unit,
    ) {
        val phoneOk = granted(Manifest.permission.READ_PHONE_STATE)
        val usageAccess = usageOk()
        val notifOk = notificationsOk()
        val observed = observedMobile()
        val sessionMobile = Live.nsMobileSession
        val gate = qualityGate(sessionMobile)
        val pss = remember(tick) { pssMb() }
        val heap = remember(tick) { heapMb() }
        val appData = remember(tick) { dirSize(filesDir) + dirSize(cacheDir) }
        val csvBytes = remember(tick) { File(filesDir, "netscope_poc.csv").length() }
        val apkBytes = remember(tick) { apkBytes() }

        Column(
            Modifier.statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpenMenu) { Text("Menu") }
                Spacer(Modifier.width(4.dp))
                Text("NetScope POC-01", style = MaterialTheme.typography.headlineSmall)
            }

            // Permissions are only shown while something is missing. Once granted
            // they stay out of the way.
            if (!phoneOk || !usageAccess || !notifOk) {
                key(tick) {
                    Text("Permissions needed", style = MaterialTheme.typography.titleMedium)
                    if (!phoneOk) Text("Phone state: not granted")
                    if (!notifOk) Text("Notifications: not granted")
                    if (!usageAccess) Text("Usage access: not granted")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!phoneOk || !notifOk) {
                        Button(onClick = onRequestPermissions) { Text("Allow") }
                    }
                    if (!usageAccess) {
                        Button(onClick = onOpenUsageAccess) { Text("Usage access") }
                    }
                }
            }

            Button(onClick = onToggleLogging) {
                Text(
                    when {
                        Live.stopAfterSample -> "Stopping after next sample"
                        Live.running -> "Stop logging"
                        else -> "Start logging"
                    },
                )
            }
            if (Live.stopAfterSample) {
                Text("Marker pending: one more sample will be written before logging stops.",
                    style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()

            Text("Current", style = MaterialTheme.typography.titleMedium)
            if (Live.lastSampleAt == 0L) {
                Text("Waiting for first sample")
            } else {
                Text("${Live.cls.uppercase()} on ${transportLabel()}",
                    style = MaterialTheme.typography.titleLarge)
                Text("Speed: Down ${speed(Live.downBps)} / Up ${speed(Live.upBps)}",
                    style = MaterialTheme.typography.titleMedium)
            }

            Text("Session data", style = MaterialTheme.typography.titleMedium)
            key(tick) {
                Text("Mobile total: ${mbOrNa(Live.nsMobileSession)}")
                Text("Wi-Fi total: ${mbOrNa(Live.nsWifiSession)}")
                BucketLine("4G", Live.totals["4g"] ?: 0L, Live.ambiguous["4g"] ?: 0L)
                BucketLine("5G", Live.totals["5g"] ?: 0L, Live.ambiguous["5g"] ?: 0L)
            }

            if (observed > 0) {
                val coverage = coveragePercent(observed)
                if (coverage != null) {
                    Text("Coverage: ${"%.1f".format(coverage)}% of observed mobile (not accuracy)",
                        style = MaterialTheme.typography.bodySmall)
                }
            }

            ReconciliationBlock(observed = observed, sessionMobile = sessionMobile, detailed = false)

            if (Live.ambiguousTotal > gate) {
                Text("Warning: 4G ambiguity +/- ${mb(Live.ambiguousTotal)} exceeds the quality gate " +
                    "(${mb(gate)}). Treat the 4G figure as an upper bound.",
                    color = MaterialTheme.colorScheme.error)
            }
            if (Live.rebaseTotal > 0L) {
                Text("Counter rebase excluded: ${mb(Live.rebaseTotal)}",
                    color = MaterialTheme.colorScheme.error)
            }
            if (Live.resetEvents > 0L) {
                Text("Counter drops (resets): ${Live.resetEvents}")
            }

            HorizontalDivider()

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = Live.upperBoundTo4g, onCheckedChange = { Live.upperBoundTo4g = it })
                Spacer(Modifier.width(8.dp))
                Text("4G upper bound (unknown/ambiguous -> 4G)")
            }
            Text("Narrow by design: keeps a time-weighted split or a known last mobile class. " +
                "Assigned amounts are shown as +/-.",
                style = MaterialTheme.typography.bodySmall)

            HorizontalDivider()

            Text("Tools", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = marker,
                onValueChange = onMarkerChange,
                label = { Text("Marker label (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(enabled = Live.running, onClick = onMark) { Text("Mark next row") }
            if (Live.lastMarker.isNotEmpty()) {
                Text("Last marker: ${Live.lastMarker} at " +
                    "${java.time.Instant.ofEpochMilli(Live.lastMarkerAt).atZone(java.time.ZoneId.systemDefault()).toLocalTime()}")
            }
            Button(onClick = onExport) { Text("Export CSV") }
            Text("Open Menu > All stats for raw counters, all buckets, and full reconciliation.",
                style = MaterialTheme.typography.bodySmall)

            HorizontalDivider()

            Text("App resources", style = MaterialTheme.typography.titleMedium)
            Text("RAM (PSS): ${pss?.let { "%.1f MB".format(it) } ?: "n/a"}   " +
                "Java heap: ${"%.1f MB".format(heap)}")
            Text("App data: ${mb(appData)}   CSV log: ${mb(csvBytes)}")
            Text("APK: ${mb(apkBytes)}")
            Text("PSS is Android's dynamic process-memory estimate and includes shared pages; " +
                "it changes between samples.",
                style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun AllStatsScreen(tick: Int, onBack: () -> Unit) {
        val observed = observedMobile()
        val sessionMobile = Live.nsMobileSession
        val gate = qualityGate(sessionMobile)

        Column(
            Modifier.statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("Back") }
                Spacer(Modifier.width(4.dp))
                Text("All stats", style = MaterialTheme.typography.headlineSmall)
            }

            key(tick) {
                Text("Permissions", style = MaterialTheme.typography.titleMedium)
                Text("Phone state: ${granted(Manifest.permission.READ_PHONE_STATE)}")
                Text("Notifications: ${notificationState()}")
                Text("Usage access: ${usageOk()}")

                HorizontalDivider()

                Text("Live", style = MaterialTheme.typography.titleMedium)
                Text("Classification: ${Live.cls}   Transport: ${Live.transport}")
                Text("Raw: ${Live.raw}  (13=LTE, 20=NR; override 1=LTE-CA, 3=NR NSA, 4=NR NSA mmWave, 5=NR Advanced)")
                Text("TrafficStats mobile RX/TX: ${mbOrNa(Live.mobRx)} / ${mbOrNa(Live.mobTx)}")
                Text("Speed (all interfaces): Down ${speed(Live.downBps)} / Up ${speed(Live.upBps)}")
                Text("Speed (mobile): Down ${speed(Live.mobileDownBps)} / Up ${speed(Live.mobileUpBps)}")
                Text("Speed (Wi-Fi/other): Down ${speed(Live.wifiDownBps)} / Up ${speed(Live.wifiUpBps)}")
                Text("NetworkStats today: mobile ${mbOrNa(Live.nsMobile)}, Wi-Fi ${mbOrNa(Live.nsWifi)}")
                Text("NetworkStats since logging started: mobile ${mbOrNa(Live.nsMobileSession)}, " +
                    "Wi-Fi ${mbOrNa(Live.nsWifiSession)}")
                Text("Last sample: ${if (Live.lastSampleAt == 0L) "none" else "${(System.currentTimeMillis() - Live.lastSampleAt) / 1000} s ago"}" +
                    "   interval ${Live.lastIntervalMs} ms   max gap ${Live.maxIntervalMs} ms")
                if (Live.maxIntervalMs > 10_000L) {
                    Text("Warning: a sampling gap over 10 s was observed; bytes around it are approximate.",
                        color = MaterialTheme.colorScheme.error)
                }

                HorizontalDivider()

                Text("Classified mobile since logging started", style = MaterialTheme.typography.titleMedium)
                listOf("4g", "5g", "other", "unknown").forEach { bucket ->
                    BucketLine(
                        label = bucket.uppercase(),
                        value = Live.totals[bucket] ?: 0L,
                        ambiguous = Live.ambiguous[bucket] ?: 0L,
                    )
                }
                Text("Wi-Fi since logging started (not generation-classified): ${mbOrNa(Live.nsWifiSession)}")

                if (observed > 0) {
                    val coverage = coveragePercent(observed)
                    if (coverage != null) {
                        Text("Coverage of observed mobile (4G+5G): ${"%.1f".format(coverage)}% " +
                            "(this is not accuracy)")
                    }
                }
                if (Live.rebaseTotal > 0L) {
                    Text("Excluded as counter rebase: ${mb(Live.rebaseTotal)}")
                    Text("A counter rebase was detected. It is excluded from every bucket; 4G/5G totals stay clean.",
                        color = MaterialTheme.colorScheme.error)
                }
                if (Live.resetEvents > 0L) {
                    Text("Counter drops (resets): ${Live.resetEvents}")
                }

                ReconciliationBlock(observed = observed, sessionMobile = sessionMobile, detailed = true)

                if (Live.ambiguousTotal > gate) {
                    Text("Warning: 4G ambiguity +/- ${mb(Live.ambiguousTotal)} exceeds the quality gate " +
                        "(${mb(gate)}). Treat the 4G figure as an upper bound.",
                        color = MaterialTheme.colorScheme.error)
                } else if (Live.ambiguousTotal > 0L) {
                    Text("4G ambiguity: +/- ${mb(Live.ambiguousTotal)} (within the quality gate)")
                }

                Text("Estimates only: only mobile traffic is classified. LTE-CA stays 4G. Transitions are split by " +
                    "time spent in each state; unresolved mixed intervals go to 4G and are reported as +/-. " +
                    "Counter resets and rebases are excluded, never counted as traffic.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    @Composable
    private fun BucketLine(label: String, value: Long, ambiguous: Long) {
        Text(
            if (ambiguous > 0L) "$label: ${mb(value)} (+/- ${mb(ambiguous)})"
            else "$label: ${mb(value)}",
        )
    }

    @Composable
    private fun ReconciliationBlock(observed: Long, sessionMobile: Long, detailed: Boolean) {
        if (sessionMobile <= 0L || observed <= 0L) {
            if (detailed) Text("Reconciliation: waiting for NetworkStats data")
            return
        }

        val diff = sessionMobile - observed
        val absDiff = kotlin.math.abs(diff)
        val percent = 100.0 * diff / sessionMobile
        val showPercent = sessionMobile >= 10L * MB

        if (detailed) {
            Text("Reconciliation: absolute difference ${mb(absDiff)}",
                style = MaterialTheme.typography.titleSmall)
            Text("System mobile ${mb(sessionMobile)}; observed mobile ${mb(observed)}")
            if (showPercent) Text("${"%.2f".format(percent)}% of system mobile")
        } else {
            Text("Reconciliation: ${mb(absDiff)} difference" +
                if (showPercent) " (${"%.2f".format(percent)}%)" else "")
        }

        val limit = maxOf(50L * MB, sessionMobile / 20)
        val observedExcess = observed - sessionMobile
        if (observedExcess > limit) {
            Text("Warning: observed mobile exceeds system mobile by ${mb(observedExcess)}; " +
                "session classification is unreliable.", color = MaterialTheme.colorScheme.error)
        } else if (absDiff > limit) {
            Text("Warning: observed mobile differs from system mobile by more than 5% or 50 MB; " +
                "session classification is unreliable.", color = MaterialTheme.colorScheme.error)
        }
    }
}
