package org.lsposed.npatch.ui.page

import android.os.Environment
import android.util.Log
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ramcosta.composedestinations.annotation.Destination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lsposed.npatch.R
import org.lsposed.npatch.ui.component.CenterTopBar
import org.lsposed.npatch.ui.util.LocalSnackbarHost
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "LogsScreen"

data class LogEntry(
    val timestamp: String,
    val level: String,
    val tag: String,
    val message: String,
    val raw: String
)

enum class TimeRange(val label: Int, val millis: Long) {
    HOUR_1(R.string.logs_time_1h, 3600_000L),
    HOUR_6(R.string.logs_time_6h, 21600_000L),
    HOUR_12(R.string.logs_time_12h, 43200_000L),
    DAY_1(R.string.logs_time_1d, 86400_000L),
    ALL(R.string.logs_time_all, Long.MAX_VALUE)
}

enum class LogLevel(val label: Int, val tag: String, val color: Color) {
    INFO(R.string.logs_level_info, "I", Color(0xFF4CAF50)),
    DEBUG(R.string.logs_level_debug, "D", Color(0xFF2196F3)),
    WARNING(R.string.logs_level_warning, "W", Color(0xFFFF9800)),
    ERROR(R.string.logs_level_error, "E", Color(0xFFF44336))
}

@OptIn(ExperimentalMaterial3Api::class)
@Destination
@Composable
fun LogsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = LocalSnackbarHost.current

    var logs by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var selectedTime by remember { mutableStateOf(TimeRange.HOUR_1) }
    var selectedLevels by remember { mutableStateOf(setOf(LogLevel.INFO, LogLevel.WARNING, LogLevel.ERROR)) }
    var isLoading by remember { mutableStateOf(true) }

    fun loadLogs() {
        isLoading = true
        scope.launch {
            logs = fetchLogs(selectedTime, selectedLevels)
            isLoading = false
        }
    }

    LaunchedEffect(selectedTime, selectedLevels) {
        loadLogs()
    }

    Scaffold(
        topBar = { CenterTopBar(stringResource(BottomBarDestination.Logs.label)) },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FloatingActionButton(
                    onClick = { loadLogs() },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.logs_refresh))
                }
                FloatingActionButton(
                    onClick = {
                        scope.launch {
                            val result = exportLogs(logs)
                            if (result != null) {
                                snackbarHost.showSnackbar(context.getString(R.string.logs_exported, result))
                            } else {
                                snackbarHost.showSnackbar(context.getString(R.string.logs_export_failed))
                            }
                        }
                    }
                ) {
                    Icon(Icons.Outlined.FileDownload, contentDescription = stringResource(R.string.logs_export))
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // Time range filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TimeRange.entries.forEach { time ->
                    FilterChip(
                        selected = selectedTime == time,
                        onClick = { selectedTime = time },
                        label = { Text(stringResource(time.label), fontSize = 12.sp) }
                    )
                }
            }

            // Level filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LogLevel.entries.forEach { level ->
                    FilterChip(
                        selected = level in selectedLevels,
                        onClick = {
                            selectedLevels = if (level in selectedLevels) {
                                selectedLevels - level
                            } else {
                                selectedLevels + level
                            }
                        },
                        label = { Text(stringResource(level.label), fontSize = 12.sp, color = level.color) }
                    )
                }
            }

            HorizontalDivider()

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (logs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.logs_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    items(logs) { entry ->
                        LogItem(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogItem(entry: LogEntry) {
    val levelColor = when (entry.level) {
        "E" -> Color(0xFFF44336)
        "W" -> Color(0xFFFF9800)
        "D" -> Color(0xFF2196F3)
        "I" -> Color(0xFF4CAF50)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.level,
                color = levelColor,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(14.dp)
            )
            Text(
                text = entry.timestamp,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
            Text(
                text = entry.tag,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp).widthIn(max = 120.dp)
            )
        }
        Text(
            text = entry.message,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 14.sp,
            modifier = Modifier.padding(start = 18.dp)
        )
    }
}

private const val MAX_LOG_ENTRIES = 8000

// A non-privileged manager can't read other apps' / the system's logcat (that needs the
// privileged READ_LOGS permission), so the previous logcat-based reader silently returned
// nothing. Each patched app instead writes its OWN process log to
//   Android/media/<pkg>/npatch/log/<yyyyMMdd>.log
// (see the loader's startLogcatCapture). The manager holds MANAGE_EXTERNAL_STORAGE, so read
// those files directly.
private suspend fun fetchLogs(timeRange: TimeRange, levels: Set<LogLevel>): List<LogEntry> =
    withContext(Dispatchers.IO) {
        try {
            val levelFilter = levels.joinToString("") { it.tag }
            if (levelFilter.isEmpty()) return@withContext emptyList()

            val cutoff = if (timeRange == TimeRange.ALL) 0L
                         else System.currentTimeMillis() - timeRange.millis

            val mediaRoot = File(Environment.getExternalStorageDirectory(), "Android/media")
            val logFiles = (mediaRoot.listFiles() ?: emptyArray())
                .flatMap { pkgDir ->
                    val logDir = File(pkgDir, "npatch/log")
                    (logDir.listFiles { f -> f.isFile && f.name.endsWith(".log") } ?: emptyArray())
                        .filter { it.lastModified() >= cutoff }
                        .map { pkgDir.name to it }
                }
                .sortedByDescending { it.second.lastModified() }

            val pattern = Regex("""^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+\d+\s+\d+\s+([VDIWEF])\s+(.+?)\s*:\s+(.*)$""")
            val entries = mutableListOf<LogEntry>()

            for ((pkg, file) in logFiles) {
                if (entries.size >= MAX_LOG_ENTRIES) break
                // Newest-first within the file: read then walk from the end.
                val lines = try { file.readLines() } catch (_: Exception) { continue }
                for (line in lines.asReversed()) {
                    val match = pattern.matchEntire(line) ?: continue
                    val (timestamp, level, tag, message) = match.destructured
                    if (level !in levelFilter) continue
                    entries.add(LogEntry(timestamp, level, "$pkg · ${tag.trim()}", message, line))
                    if (entries.size >= MAX_LOG_ENTRIES) break
                }
            }
            entries
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read logs", e)
            emptyList()
        }
    }

private suspend fun exportLogs(logs: List<LogEntry>): String? =
    withContext(Dispatchers.IO) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMddHHmm", Locale.getDefault()).format(Date())
            val fileName = "NPatch-log-$timestamp.txt"
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val file = File(dir, fileName)
            file.bufferedWriter().use { writer ->
                logs.reversed().forEach { entry ->  // export in chronological order
                    writer.write(entry.raw)
                    writer.newLine()
                }
            }
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export logs", e)
            null
        }
    }
