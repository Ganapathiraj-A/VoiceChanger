package com.example.voicechanger

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF1DB954),
                    secondary = Color(0xFF25D366),
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E),
                    onSurface = Color.White
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    VoiceChangerScreen()
                }
            }
        }
    }
}

fun formatTime(totalSeconds: Float): String {
    val secs = totalSeconds.toInt()
    if (secs < 0) return "0s"
    val minutes = secs / 60
    val remainingSecs = secs % 60
    return if (minutes > 0) {
        "${minutes}m ${remainingSecs}s"
    } else {
        "${remainingSecs}s"
    }
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) {
        String.format(Locale.getDefault(), "%.1f MB", mb)
    } else {
        String.format(Locale.getDefault(), "%.0f KB", kb)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceChangerScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf<String?>(null) }

    var isProcessing by remember { mutableStateOf(false) }
    var progressPercent by remember { mutableStateOf(0f) }
    var etaSeconds by remember { mutableStateOf(30f) }
    var elapsedSeconds by remember { mutableStateOf(0f) }
    var statusMessage by remember { mutableStateOf("Ready to process") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var convertedFile by remember { mutableStateOf<File?>(null) }

    var showLogs by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("No logs") }

    fun refreshLogs() {
        logText = LogManager.getLogContent(context)
    }

    val historyFiles = remember { mutableStateListOf<File>() }

    fun refreshHistory() {
        historyFiles.clear()
        val appMusicDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "VoiceChanger")
        if (appMusicDir.exists()) {
            val files = appMusicDir.listFiles { file -> file.isFile && file.name.endsWith(".mp3", ignoreCase = true) }
            if (files != null) {
                historyFiles.addAll(files.sortedByDescending { it.lastModified() })
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshHistory()
        refreshLogs()
        LogManager.i(context, "APP", "VoiceChanger App Launched.")
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedFileUri = uri
            selectedFileName = uri.lastPathSegment ?: "Selected Audio File"
            convertedFile = null
            errorMessage = null
            statusMessage = "File selected. Tap 'Convert Voice via Cloud'."
            LogManager.i(context, "UI", "Selected file: $selectedFileName ($uri)")
            refreshLogs()
        }
    }

    fun shareFileToWhatsApp(file: File) {
        try {
            val contentUri = FileProvider.getUriForFile(
                context,
                "com.example.voicechanger.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_TEXT, "Transformed Voice Audio generated by VoiceChanger Cloud Engine.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                setPackage("com.whatsapp")
            }

            try {
                context.startActivity(shareIntent)
            } catch (e: Exception) {
                val chooserIntent = Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "audio/*"
                        putExtra(Intent.EXTRA_STREAM, contentUri)
                        putExtra(Intent.EXTRA_TEXT, "Transformed Voice Audio.")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "Share Audio File"
                )
                context.startActivity(chooserIntent)
            }
            LogManager.i(context, "SHARE", "Triggered share for ${file.name}")
        } catch (e: Exception) {
            Toast.makeText(context, "Error sharing file: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            LogManager.e(context, "SHARE", "Share failed", e)
        }
    }

    fun startCloudProcessing() {
        val uri = selectedFileUri ?: return
        isProcessing = true
        progressPercent = 0f
        etaSeconds = 30f
        elapsedSeconds = 0f
        statusMessage = "Submitting file to Cloud Engine..."
        errorMessage = null
        convertedFile = null
        refreshLogs()

        coroutineScope.launch {
            val submitResult = CloudApiClient.submitJob(context, uri)
            refreshLogs()
            submitResult.onSuccess { jobId ->
                statusMessage = "Job created! Processing on Cloud..."
                var isCompleted = false

                while (!isCompleted && isProcessing) {
                    delay(1500)
                    val statusResult = CloudApiClient.pollJobStatus(context, jobId)
                    refreshLogs()
                    statusResult.onSuccess { resp ->
                        progressPercent = resp.progressPercent
                        etaSeconds = resp.etaSeconds
                        elapsedSeconds = resp.elapsedSeconds
                        statusMessage = resp.statusMsg

                        if (resp.status == "completed" || resp.progressPercent >= 100f) {
                            isCompleted = true
                            statusMessage = "Downloading converted MP3 file..."

                            val dlResult = CloudApiClient.downloadAndSaveResult(
                                context,
                                jobId,
                                selectedFileName ?: "audio.mp3"
                            )
                            refreshLogs()
                            dlResult.onSuccess { file ->
                                convertedFile = file
                                isProcessing = false
                                statusMessage = "Completed! Saved to Music/VoiceChanger/"
                                refreshHistory()
                                Toast.makeText(context, "Audio Processed Successfully!", Toast.LENGTH_SHORT).show()
                            }.onFailure { err ->
                                isProcessing = false
                                errorMessage = "Download failed: ${err.message}"
                            }
                        } else if (resp.status == "failed") {
                            isCompleted = true
                            isProcessing = false
                            errorMessage = "Cloud job failed: ${resp.statusMsg}"
                        }
                    }
                }
            }.onFailure { err ->
                isProcessing = false
                errorMessage = "Failed to submit job: ${err.message}"
                refreshLogs()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "VoiceChanger Cloud AI",
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1E1E1E)
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Service Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            "GCP Cloud Run Engine",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            "asia-south1 (Mumbai) • Pre-Warmed",
                            fontSize = 12.sp,
                            color = Color(0xFFA0A0A0)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1DB954))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "ONLINE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    }
                }
            }

            // File Selection Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Select Audio Input",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    Button(
                        onClick = { filePickerLauncher.launch("audio/*") },
                        enabled = !isProcessing,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))
                    ) {
                        Text("📁 Choose Audio / Video File", color = Color.White)
                    }

                    if (selectedFileUri != null) {
                        Text(
                            "Selected: ${selectedFileName ?: "Audio file"}",
                            fontSize = 14.sp,
                            color = Color(0xFF1DB954),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Process Action Button
            Button(
                onClick = { startCloudProcessing() },
                enabled = selectedFileUri != null && !isProcessing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1DB954),
                    disabledContainerColor = Color(0xFF2E4E38)
                )
            ) {
                Text(
                    if (isProcessing) "⚡ Processing on GCP Cloud..." else "⚡ Convert Voice via Cloud",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }

            // Progress & Status Card with Minutes & Seconds
            AnimatedVisibility(visible = isProcessing || progressPercent > 0f) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Live Cloud Progress",
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                "${progressPercent.toInt()}%",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = Color(0xFF1DB954)
                            )
                        }

                        LinearProgressIndicator(
                            progress = { (progressPercent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = Color(0xFF1DB954),
                            trackColor = Color(0xFF333333)
                        )

                        Text(
                            statusMessage,
                            fontSize = 13.sp,
                            color = Color(0xFFA0A0A0)
                        )

                        // Formatted Minutes and Seconds
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Elapsed: ${formatTime(elapsedSeconds)}",
                                fontSize = 13.sp,
                                color = Color.Gray
                            )
                            Text(
                                "ETA: ${formatTime(etaSeconds)}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1DB954)
                            )
                        }
                    }
                }
            }

            // Error Display
            if (errorMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF4A1515)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        errorMessage!!,
                        color = Color(0xFFFF6B6B),
                        modifier = Modifier.padding(16.dp),
                        fontSize = 14.sp
                    )
                }
            }

            // Converted Audio Files History Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "🎧 Converted Files History (${historyFiles.size})",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        IconButton(onClick = { refreshHistory() }) {
                            Text("🔄", fontSize = 14.sp)
                        }
                    }

                    if (historyFiles.isEmpty()) {
                        Text(
                            "No converted files found yet. Process an audio file to see it here.",
                            fontSize = 13.sp,
                            color = Color.Gray
                        )
                    } else {
                        historyFiles.forEach { file ->
                            val dateStr = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF282828)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        file.name,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            "${formatFileSize(file.length())} • $dateStr",
                                            fontSize = 12.sp,
                                            color = Color.Gray
                                        )
                                    }
                                    Button(
                                        onClick = { shareFileToWhatsApp(file) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(40.dp),
                                        shape = RoundedCornerShape(6.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366))
                                    ) {
                                        Text(
                                            "💬 Share to WhatsApp",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.Black
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Debug Logs Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "📋 Debug Logs",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Button(
                            onClick = {
                                showLogs = !showLogs
                                if (showLogs) refreshLogs()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))
                        ) {
                            Text(if (showLogs) "Hide" else "View Logs", color = Color.White, fontSize = 12.sp)
                        }
                    }

                    if (showLogs) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black)
                                .padding(8.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                logText,
                                color = Color(0xFF00FF00),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("VoiceChanger App Logs", logText)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Logs Copied to Clipboard!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF444444))
                            ) {
                                Text("Copy", fontSize = 12.sp)
                            }

                            Button(
                                onClick = {
                                    LogManager.clearLogs(context)
                                    refreshLogs()
                                    Toast.makeText(context, "Logs Cleared", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF662222))
                            ) {
                                Text("Clear", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
