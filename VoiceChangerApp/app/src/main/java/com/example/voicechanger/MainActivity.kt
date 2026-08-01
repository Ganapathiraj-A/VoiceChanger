package com.example.voicechanger

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
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

    var activeMediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var currentlyPlayingKey by remember { mutableStateOf<String?>(null) }

    fun createMediaPlayerFromUri(uri: Uri): MediaPlayer? {
        val mp = MediaPlayer()
        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                mp.setDataSource(pfd.fileDescriptor)
                mp.prepare()
                pfd.close()
                return mp
            }
        } catch (e: Exception) {
            LogManager.e(context, "PLAYER", "PFD prepare failed: ${e.message}")
        }
        try {
            mp.reset()
            mp.setDataSource(context, uri)
            mp.prepare()
            return mp
        } catch (e: Exception) {
            LogManager.e(context, "PLAYER", "Uri prepare failed: ${e.message}")
        }
        val path = uri.path
        if (path != null) {
            val subName = path.substringAfterLast('/').substringAfterLast(':')
            val directFile = File("/sdcard/Download", subName)
            if (directFile.exists()) {
                try {
                    mp.reset()
                    mp.setDataSource(directFile.absolutePath)
                    mp.prepare()
                    return mp
                } catch (e: Exception) {
                    LogManager.e(context, "PLAYER", "Direct path prepare failed: ${e.message}")
                }
            }
        }
        try { mp.release() } catch (_: Exception) {}
        return null
    }

    fun stopAudioPlayback() {
        try {
            activeMediaPlayer?.stop()
            activeMediaPlayer?.release()
        } catch (_: Exception) {}
        activeMediaPlayer = null
        currentlyPlayingKey = null
    }

    fun toggleAudioPlayback(key: String, createPlayer: () -> MediaPlayer?) {
        if (currentlyPlayingKey == key && activeMediaPlayer?.isPlaying == true) {
            stopAudioPlayback()
        } else {
            stopAudioPlayback()
            try {
                val mp = createPlayer()
                if (mp != null) {
                    activeMediaPlayer = mp
                    currentlyPlayingKey = key
                    mp.setOnCompletionListener {
                        stopAudioPlayback()
                    }
                    mp.start()
                } else {
                    Toast.makeText(context, "Cannot play audio file", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                stopAudioPlayback()
                Toast.makeText(context, "Error playing audio: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            stopAudioPlayback()
        }
    }

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

    var isAnalyzingPreview by remember { mutableStateOf(false) }
    var previewData by remember { mutableStateOf<DiarizePreviewResponse?>(null) }
    var selectedPreserveCluster by remember { mutableStateOf(0) }

    var previewProgressPercent by remember { mutableStateOf(0f) }
    var previewEtaSeconds by remember { mutableStateOf(3f) }
    var previewElapsedSeconds by remember { mutableStateOf(0f) }
    var previewStepMessage by remember { mutableStateOf("Analyzing speakers...") }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            stopAudioPlayback()
            selectedFileUri = uri
            val rawName = uri.lastPathSegment ?: "Selected Audio File"
            selectedFileName = rawName.substringAfterLast('/').substringAfterLast(':').ifEmpty { "Selected Audio File" }
            convertedFile = null
            errorMessage = null
            previewData = null
            statusMessage = "File selected. Analyzing speakers..."
            LogManager.i(context, "UI", "Selected file: $selectedFileName ($uri)")
            refreshLogs()

            fun triggerSpeakerPreview(u: Uri) {
                coroutineScope.launch {
                    isAnalyzingPreview = true
                    errorMessage = null
                    previewProgressPercent = 0f
                    previewEtaSeconds = 3f
                    previewElapsedSeconds = 0f
                    previewStepMessage = "Uploading & initializing speaker analysis..."
                    statusMessage = "Analyzing speakers..."

                    val submitRes = CloudApiClient.submitDiarizePreviewJob(context, u)
                    submitRes.onSuccess { prevId ->
                        var isFinished = false
                        while (!isFinished && isAnalyzingPreview) {
                            delay(500)
                            val pollRes = CloudApiClient.pollPreviewStatus(context, prevId)
                            pollRes.onSuccess { st ->
                                previewProgressPercent = st.progressPercent
                                previewEtaSeconds = st.etaSeconds
                                previewElapsedSeconds = st.elapsedSeconds
                                previewStepMessage = st.stepMsg

                                if (st.status == "completed" && st.previewResponse != null) {
                                    isFinished = true
                                    isAnalyzingPreview = false
                                    previewData = st.previewResponse
                                    selectedPreserveCluster = 0
                                    statusMessage = "Speakers analyzed! Play sample audio for Speaker A & Speaker B below to choose which voice to preserve."
                                } else if (st.status == "failed") {
                                    isFinished = true
                                    isAnalyzingPreview = false
                                    statusMessage = "File selected. You can convert or retry speaker analysis."
                                    errorMessage = "Speaker analysis failed: ${st.stepMsg}"
                                }
                            }
                        }
                    }.onFailure { err ->
                        isAnalyzingPreview = false
                        statusMessage = "File selected. You can convert or retry speaker analysis."
                        errorMessage = "Speaker analysis error: ${err.message ?: err.javaClass.simpleName}"
                    }
                    refreshLogs()
                }
            }

            triggerSpeakerPreview(uri)
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
            val submitResult = CloudApiClient.submitJob(context, uri, preserveSpeakerCluster = selectedPreserveCluster)
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
                        "Voice Changer Cloud AI",
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
                        onClick = { filePickerLauncher.launch("*/*") },
                        enabled = !isProcessing,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))
                    ) {
                        Text("📁 Choose Audio / Video File", color = Color.White)
                    }

                    if (selectedFileUri != null) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                "Selected: ${selectedFileName ?: "Audio file"}",
                                fontSize = 14.sp,
                                color = Color(0xFF1DB954),
                                fontWeight = FontWeight.Medium
                            )

                            val isInputPlaying = currentlyPlayingKey == "INPUT_${selectedFileUri}" && activeMediaPlayer?.isPlaying == true
                            Button(
                                onClick = {
                                    toggleAudioPlayback("INPUT_${selectedFileUri}") {
                                        createMediaPlayerFromUri(selectedFileUri!!)
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isInputPlaying) Color(0xFFE53935) else Color(0xFF1976D2)
                                )
                            ) {
                                Text(
                                    if (isInputPlaying) "⏸ Pause Input Audio" else "▶ Play Input Audio",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                if (isAnalyzingPreview) {
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp),
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    "🔍 Analyzing Speakers...",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    color = Color.White
                                                )
                                                Text(
                                                    "${previewProgressPercent.toInt()}%",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    color = Color(0xFF1DB954)
                                                )
                                            }

                                            LinearProgressIndicator(
                                                progress = { (previewProgressPercent / 100f).coerceIn(0f, 1f) },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(8.dp),
                                                color = Color(0xFF1DB954),
                                                trackColor = Color(0xFF333333)
                                            )

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    previewStepMessage,
                                                    fontSize = 12.sp,
                                                    color = Color.Gray,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Text(
                                                    "⏱ ${previewElapsedSeconds.toInt()}s (ETA: ${previewEtaSeconds.toInt()}s)",
                                                    fontSize = 12.sp,
                                                    color = Color(0xFFFFA726),
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            if (previewData != null) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        "Choose Voice to PRESERVE (Touch ▶ to listen):",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )

                                    val prev = previewData!!

                                    // Speaker A Card
                                    val isPlayingA = currentlyPlayingKey == "PREV_SPK_A" && activeMediaPlayer?.isPlaying == true
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (selectedPreserveCluster == 0) Color(0xFF1DB954).copy(alpha = 0.25f) else Color(0xFF2A2A2A)
                                        ),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                RadioButton(
                                                    selected = (selectedPreserveCluster == 0),
                                                    onClick = { selectedPreserveCluster = 0 },
                                                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF1DB954))
                                                )
                                                Column(modifier = Modifier.padding(start = 4.dp)) {
                                                    Text("Speaker A", fontWeight = FontWeight.Bold, color = Color.White)
                                                    Text("${prev.speakerAPct}% speech time (${prev.speakerADurSec}s)", color = Color.LightGray, fontSize = 12.sp)
                                                }
                                            }
                                            IconButton(
                                                onClick = {
                                                    toggleAudioPlayback("PREV_SPK_A") {
                                                        val mp = MediaPlayer()
                                                        mp.setDataSource(prev.speakerAUrl)
                                                        mp.prepare()
                                                        mp
                                                    }
                                                }
                                            ) {
                                                Text(if (isPlayingA) "⏸" else "▶", fontSize = 20.sp, color = Color.White)
                                            }
                                        }
                                    }

                                    // Speaker B Card
                                    val isPlayingB = currentlyPlayingKey == "PREV_SPK_B" && activeMediaPlayer?.isPlaying == true
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (selectedPreserveCluster == 1) Color(0xFF1DB954).copy(alpha = 0.25f) else Color(0xFF2A2A2A)
                                        ),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                RadioButton(
                                                    selected = (selectedPreserveCluster == 1),
                                                    onClick = { selectedPreserveCluster = 1 },
                                                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF1DB954))
                                                )
                                                Column(modifier = Modifier.padding(start = 4.dp)) {
                                                    Text("Speaker B", fontWeight = FontWeight.Bold, color = Color.White)
                                                    Text("${prev.speakerBPct}% speech time (${prev.speakerBDurSec}s)", color = Color.LightGray, fontSize = 12.sp)
                                                }
                                            }
                                            IconButton(
                                                onClick = {
                                                    toggleAudioPlayback("PREV_SPK_B") {
                                                        val mp = MediaPlayer()
                                                        mp.setDataSource(prev.speakerBUrl)
                                                        mp.prepare()
                                                        mp
                                                    }
                                                }
                                            ) {
                                                Text(if (isPlayingB) "⏸" else "▶", fontSize = 20.sp, color = Color.White)
                                            }
                                        }
                                    }
                                }
                            }
                        }
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
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        val isPlayingFile = currentlyPlayingKey == "FILE_${file.absolutePath}" && activeMediaPlayer?.isPlaying == true
                                        Button(
                                            onClick = {
                                                toggleAudioPlayback("FILE_${file.absolutePath}") {
                                                    MediaPlayer().apply {
                                                        setDataSource(file.absolutePath)
                                                        prepare()
                                                    }
                                                }
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(40.dp),
                                            shape = RoundedCornerShape(6.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (isPlayingFile) Color(0xFFE53935) else Color(0xFF1DB954)
                                            )
                                        ) {
                                            Text(
                                                if (isPlayingFile) "⏸ Pause" else "▶ Play Audio",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.Black
                                            )
                                        }

                                        Button(
                                            onClick = { shareFileToWhatsApp(file) },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(40.dp),
                                            shape = RoundedCornerShape(6.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366))
                                        ) {
                                            Text(
                                                "💬 Share",
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
