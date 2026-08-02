package com.example.voicechanger

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
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
import androidx.documentfile.provider.DocumentFile
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
        val allFiles = mutableListOf<File>()

        val candidateDirs = listOf(
            File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "VoiceChanger"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "VoiceChanger"),
            File(Environment.getExternalStorageDirectory(), "Music/VoiceChanger"),
            File(context.filesDir, "VoiceChanger"),
            File(context.cacheDir, "VoiceChanger")
        )

        for (dir in candidateDirs) {
            if (dir.exists() && dir.isDirectory) {
                val files = dir.listFiles { file ->
                    file.isFile && (file.name.endsWith(".mp3", true) || file.name.endsWith(".wav", true) || file.name.endsWith(".m4a", true) || file.name.endsWith(".aac", true) || file.name.endsWith(".3gp", true))
                }
                if (files != null) {
                    allFiles.addAll(files)
                }
            }
        }

        val sortedUnique = allFiles
            .distinctBy { it.name }
            .sortedByDescending { it.lastModified() }

        historyFiles.addAll(sortedUnique)
    }

    val prefs = remember { context.getSharedPreferences("voice_changer_settings", Context.MODE_PRIVATE) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var autoLaunchOnCallEnd by remember { mutableStateOf(prefs.getBoolean("auto_launch_on_call_end", false)) }
    var defaultFolderUriStr by remember { mutableStateOf(prefs.getString("default_folder_uri", null)) }
    var defaultFolderName by remember { mutableStateOf(prefs.getString("default_folder_name", null)) }

    val phoneStatePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            prefs.edit().putBoolean("auto_launch_on_call_end", true).apply()
            autoLaunchOnCallEnd = true
            Toast.makeText(context, "Post-call auto-launch enabled!", Toast.LENGTH_SHORT).show()
        } else {
            prefs.edit().putBoolean("auto_launch_on_call_end", false).apply()
            autoLaunchOnCallEnd = false
            Toast.makeText(context, "READ_PHONE_STATE permission required to detect call end", Toast.LENGTH_LONG).show()
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                LogManager.e(context, "SETTINGS", "Failed to take persistable URI permission: ${e.message}")
            }
            val docFile = DocumentFile.fromTreeUri(context, treeUri)
            val name = docFile?.name ?: treeUri.lastPathSegment ?: "Selected Folder"
            prefs.edit()
                .putString("default_folder_uri", treeUri.toString())
                .putString("default_folder_name", name)
                .apply()
            defaultFolderUriStr = treeUri.toString()
            defaultFolderName = name
            Toast.makeText(context, "Default recording folder set to: $name", Toast.LENGTH_SHORT).show()
        }
    }

    fun scanAndSelectLatestCallRecording() {
        try {
            var latestUri: Uri? = null
            var latestName: String? = null

            if (!defaultFolderUriStr.isNullOrBlank()) {
                val treeUri = Uri.parse(defaultFolderUriStr)
                val docFolder = DocumentFile.fromTreeUri(context, treeUri)
                if (docFolder != null && docFolder.isDirectory) {
                    val audioDoc = docFolder.listFiles()
                        .filter { file ->
                            val n = file.name ?: ""
                            file.isFile && (n.endsWith(".mp3", true) || n.endsWith(".m4a", true) || n.endsWith(".wav", true) || n.endsWith(".3gp", true) || n.endsWith(".aac", true) || n.endsWith(".mp4", true))
                        }
                        .maxByOrNull { it.lastModified() }

                    if (audioDoc != null) {
                        latestUri = audioDoc.uri
                        latestName = audioDoc.name
                    }
                }
            }

            if (latestUri == null) {
                val candidates = listOf(
                    File("/storage/emulated/0/Recordings/Call"),
                    File("/storage/emulated/0/CallRecordings"),
                    File("/storage/emulated/0/MIUI/sound_recorder/call_rec"),
                    File("/storage/emulated/0/Recordings"),
                    File("/storage/emulated/0/Music"),
                    File("/storage/emulated/0/Download")
                )
                val candidateFiles = candidates
                    .filter { it.exists() && it.isDirectory }
                    .flatMap { dir ->
                        dir.listFiles { f ->
                            f.isFile && (f.name.endsWith(".mp3", true) || f.name.endsWith(".m4a", true) || f.name.endsWith(".wav", true) || f.name.endsWith(".3gp", true) || f.name.endsWith(".aac", true) || f.name.endsWith(".mp4", true))
                        }?.toList() ?: emptyList()
                    }
                val latestFile = candidateFiles.maxByOrNull { it.lastModified() }
                if (latestFile != null) {
                    latestUri = Uri.fromFile(latestFile)
                    latestName = latestFile.name
                }
            }

            if (latestUri != null) {
                selectedFileUri = latestUri
                selectedFileName = latestName ?: "Latest Call Recording"
                statusMessage = "Auto-selected latest call recording: $selectedFileName"
                LogManager.i(context, "AUTO", "Auto-selected latest audio recording: $selectedFileName ($latestUri)")
            }
        } catch (e: Exception) {
            LogManager.e(context, "AUTO", "Error scanning for latest recording: ${e.message}", e)
        }
    }

    var isAnalyzingPreview by remember { mutableStateOf(false) }
    var previewData by remember { mutableStateOf<DiarizePreviewResponse?>(null) }
    var selectedPreserveCluster by remember { mutableStateOf(0) }
    var conversionMode by remember { mutableStateOf("rvc") }
    var selectedTargetProfile by remember { mutableStateOf("tamil_female") }
    var showAdvancedOptions by remember { mutableStateOf(false) }

    var previewProgressPercent by remember { mutableStateOf(0f) }
    var previewEtaSeconds by remember { mutableStateOf(3f) }
    var previewElapsedSeconds by remember { mutableStateOf(0f) }
    var previewStepMessage by remember { mutableStateOf("Analyzing speakers...") }

    fun triggerSpeakerPreview(u: Uri) {
        coroutineScope.launch {
            isAnalyzingPreview = true
            errorMessage = null
            previewProgressPercent = 0f
            previewEtaSeconds = 3f
            previewElapsedSeconds = 0f
            previewStepMessage = "Uploading & initializing speaker analysis..."
            statusMessage = "Analyzing audio speakers..."

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
                            val resp = st.previewResponse
                            previewData = resp
                            if (resp != null) {
                                selectedPreserveCluster = if (resp.speakerAPct <= resp.speakerBPct) 0 else 1
                            }
                            statusMessage = "Speakers analyzed! Auto-selected speaker with less speech duration (${if (selectedPreserveCluster == 0) "Speaker A" else "Speaker B"}). Ready to convert!"
                        } else if (st.status == "failed") {
                            isFinished = true
                            isAnalyzingPreview = false
                            statusMessage = "File selected. Ready to convert."
                            errorMessage = "Speaker analysis failed: ${st.stepMsg}"
                        }
                    }
                }
            }.onFailure { err ->
                isAnalyzingPreview = false
                statusMessage = "File selected. Ready to convert."
                errorMessage = "Speaker analysis error: ${err.message ?: err.javaClass.simpleName}"
            }
            refreshLogs()
        }
    }

    var isAutoCloseActive by remember { mutableStateOf(false) }
    var autoCloseRemainingSec by remember { mutableStateOf(0) }

    fun cancelAutoClose() {
        if (isAutoCloseActive) {
            isAutoCloseActive = false
        }
    }

    LaunchedEffect(Unit) {
        refreshHistory()
        refreshLogs()
        scanAndSelectLatestCallRecording()

        val isPostCall = (context as? ComponentActivity)?.intent?.getBooleanExtra("AUTO_SELECT_LATEST_CALL", false) == true
        if (isPostCall) {
            isAutoCloseActive = true
            autoCloseRemainingSec = 7
            while (autoCloseRemainingSec > 0 && isAutoCloseActive) {
                delay(1000)
                autoCloseRemainingSec--
            }
            if (autoCloseRemainingSec <= 0 && isAutoCloseActive) {
                LogManager.i(context, "AUTO", "7s Auto-close timer expired without user response. Closing app.")
                (context as? ComponentActivity)?.finish()
            }
        }

        LogManager.i(context, "APP", "VoiceChanger App Launched.")
    }

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
            showAdvancedOptions = false
            statusMessage = "File selected. Analyzing speakers..."
            LogManager.i(context, "UI", "Selected file: $selectedFileName ($uri)")
            refreshLogs()

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
        cancelAutoClose()
        isProcessing = true
        progressPercent = 0f
        etaSeconds = 30f
        elapsedSeconds = 0f
        statusMessage = "Submitting file to Cloud Engine..."
        errorMessage = null
        convertedFile = null
        refreshLogs()

        coroutineScope.launch {
            if (isAnalyzingPreview || previewData == null) {
                statusMessage = "Analyzing audio speakers first to choose default target speaker..."
                var waitRetries = 0
                while ((isAnalyzingPreview || previewData == null) && waitRetries < 30) {
                    delay(500)
                    waitRetries++
                }
            }

            statusMessage = "Submitting file to Cloud Engine..."
            val submitResult = CloudApiClient.submitJob(
                context,
                uri,
                preserveSpeakerCluster = selectedPreserveCluster,
                conversionMode = conversionMode,
                targetProfileName = selectedTargetProfile
            )
            refreshLogs()
            submitResult.onSuccess { jobId ->
                statusMessage = "Job created! Processing on Cloud..."
                var isCompleted = false
                var pollErrorCount = 0

                while (!isCompleted && isProcessing) {
                    delay(1500)
                    val statusResult = CloudApiClient.pollJobStatus(context, jobId)
                    refreshLogs()
                    statusResult.onSuccess { resp ->
                        pollErrorCount = 0
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
                    }.onFailure { err ->
                        pollErrorCount++
                        if (pollErrorCount >= 10) {
                            isCompleted = true
                            isProcessing = false
                            errorMessage = "Job status polling failed after 10 retries: ${err.message}"
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
                actions = {
                    if (isAutoCloseActive && autoCloseRemainingSec > 0) {
                        Button(
                            onClick = {
                                (context as? ComponentActivity)?.finish()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Text(
                                "❌ Close (${autoCloseRemainingSec}s)",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    } else {
                        IconButton(
                            onClick = {
                                (context as? ComponentActivity)?.finish()
                            }
                        ) {
                            Text("❌", fontSize = 16.sp)
                        }
                    }
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

                            // Convert Voice via Cloud Button placed right below Choose Audio
                            Button(
                                onClick = { startCloudProcessing() },
                                enabled = selectedFileUri != null && !isProcessing,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp)
                                    .padding(top = 4.dp),
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
                        }
                    }
                }
            }

            // Collapsible Advanced Options Section (Speaker Diarization & Engine Selection)
            Card(
                onClick = { showAdvancedOptions = !showAdvancedOptions },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "⚙️ Advanced Options (Speaker & Engine)",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { showAdvancedOptions = !showAdvancedOptions }
                        ) {
                            Text(
                                if (showAdvancedOptions) "▲" else "▼",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1DB954)
                            )
                        }
                    }

                    AnimatedVisibility(visible = showAdvancedOptions) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (selectedFileUri != null) {
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
                                        if (isInputPlaying) "⏸ Pause Input Audio" else "▶ Play Selected Input Audio",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                            if (isAnalyzingPreview) {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF252525)),
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

                            if (previewData != null) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        "Voice to PRESERVE (Auto-selected speaker with less talk time):",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFFA726)
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
                                                    Text("Speaker A ${if (prev.speakerAPct <= prev.speakerBPct) "(Less Speech Time)" else ""}", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
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
                                                    Text("Speaker B ${if (prev.speakerBPct < prev.speakerAPct) "(Less Speech Time)" else ""}", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
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

                            // Conversion Engine Mode Selection Card
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF252525)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        "Conversion Engine Mode:",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = Color.White
                                    )

                                    // Mode 1: RVC Neural Voice Clone
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (conversionMode == "rvc") Color(0xFF1DB954).copy(alpha = 0.2f) else Color(0xFF2A2A2A)
                                        ),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            RadioButton(
                                                selected = (conversionMode == "rvc"),
                                                onClick = { conversionMode = "rvc" },
                                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF1DB954))
                                            )
                                            Column(modifier = Modifier.padding(start = 4.dp)) {
                                                Text("🤖 Neural Voice Clone (RVC)", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                                                Text("Clones exact target vocal identity & timbre", color = Color(0xFF1DB954), fontSize = 11.sp)
                                            }
                                        }
                                    }

                                    // Mode 2: Praat PSOLA
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (conversionMode == "praat_psola") Color(0xFF1DB954).copy(alpha = 0.2f) else Color(0xFF2A2A2A)
                                        ),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            RadioButton(
                                                selected = (conversionMode == "praat_psola"),
                                                onClick = { conversionMode = "praat_psola" },
                                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF1DB954))
                                            )
                                            Column(modifier = Modifier.padding(start = 4.dp)) {
                                                Text("⚡ Praat Pitch & Formant Shift", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                                                Text("Fast, crisp pitch & vocal tract transform", color = Color.LightGray, fontSize = 11.sp)
                                            }
                                        }
                                    }

                                    // Mode 3: Adaptive Target Voice Morphing
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (conversionMode == "target_morph") Color(0xFF1DB954).copy(alpha = 0.2f) else Color(0xFF2A2A2A)
                                        ),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            RadioButton(
                                                selected = (conversionMode == "target_morph"),
                                                onClick = { conversionMode = "target_morph" },
                                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF1DB954))
                                            )
                                            Column(modifier = Modifier.padding(start = 4.dp)) {
                                                Text("🎯 Adaptive Target Voice Morphing", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                                                Text("Dynamic F0 & formant envelope matching to target profile", color = Color.LightGray, fontSize = 11.sp)
                                            }
                                        }
                                    }

                                    // Mode 4: ASR + TTS Speech Recreation
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (conversionMode == "asr_tts") Color(0xFF1DB954).copy(alpha = 0.2f) else Color(0xFF2A2A2A)
                                        ),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            RadioButton(
                                                selected = (conversionMode == "asr_tts"),
                                                onClick = { conversionMode = "asr_tts" },
                                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF1DB954))
                                            )
                                            Column(modifier = Modifier.padding(start = 4.dp)) {
                                                Text("🗣️ Full Voice Recreation (ASR + TTS)", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                                                Text("Extracts words via ASR & regenerates 100% synthetic voice via Neural TTS", color = Color(0xFF25D366), fontSize = 11.sp)
                                            }
                                        }
                                    }

                                    if (conversionMode == "rvc" || conversionMode == "target_morph") {
                                        Text(
                                            "Target Voice Profile to Clone/Morph Into:",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = Color(0xFFFFA726),
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            listOf(
                                                "tamil_female" to "Tamil Female",
                                                "tamil_male" to "Tamil Male",
                                                "english_female" to "English Female",
                                                "english_male" to "English Male"
                                            ).forEach { (id, label) ->
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    RadioButton(
                                                        selected = (selectedTargetProfile == id),
                                                        onClick = { selectedTargetProfile = id },
                                                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF1DB954))
                                                    )
                                                    Text(label, color = Color.White, fontSize = 13.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
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
                            "🎧 Converted Files History (Past ${minOf(5, historyFiles.size)} of ${historyFiles.size})",
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
                        historyFiles.take(5).forEach { file ->
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

            // Settings Button at Bottom of Main Screen
            Card(
                onClick = { showSettingsDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⚙️", fontSize = 18.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("App & Call Recording Settings", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                            Text(
                                if (autoLaunchOnCallEnd) "Post-call auto-launch: ON" else "Post-call auto-launch: OFF",
                                fontSize = 12.sp,
                                color = if (autoLaunchOnCallEnd) Color(0xFF1DB954) else Color.Gray
                            )
                        }
                    }
                    Text("Configure ▶", fontSize = 13.sp, color = Color(0xFF1DB954), fontWeight = FontWeight.Bold)
                }
            }
        }

        // Settings Dialog Modal
        if (showSettingsDialog) {
            AlertDialog(
                onDismissRequest = { showSettingsDialog = false },
                title = {
                    Text("⚙️ App & Call Settings", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 18.sp)
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        // Option 1: Post Call Auto Launch Toggle
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Auto-Launch After Call Ends",
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Switch(
                                        checked = autoLaunchOnCallEnd,
                                        onCheckedChange = { checked ->
                                            if (checked) {
                                                phoneStatePermissionLauncher.launch(android.Manifest.permission.READ_PHONE_STATE)
                                            } else {
                                                prefs.edit().putBoolean("auto_launch_on_call_end", false).apply()
                                                autoLaunchOnCallEnd = false
                                            }
                                        },
                                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF1DB954))
                                    )
                                }
                                Text("Automatically launches VoiceChanger immediately when any phone call finishes.", fontSize = 11.sp, color = Color.LightGray)
                            }
                        }

                        // Option 2: Default Call Recording Folder Selection
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Default Call Recording Folder:", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                                Text(
                                    defaultFolderName ?: "Not Set (Auto-scans System Call Record Folders)",
                                    fontSize = 12.sp,
                                    color = if (defaultFolderName != null) Color(0xFF1DB954) else Color.Gray
                                )
                                Button(
                                    onClick = { folderPickerLauncher.launch(null) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(40.dp),
                                    shape = RoundedCornerShape(6.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))
                                ) {
                                    Text("📁 Select Call Recording Folder", color = Color.White, fontSize = 12.sp)
                                }
                                Text("Automatically selects the newest recording from this folder when app opens after call.", fontSize = 11.sp, color = Color.LightGray)
                            }
                        }

                        // Option 3: App Version & GitHub Update Checker
                        val (versionName, versionCode) = remember {
                            try {
                                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                                val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo.longVersionCode else pInfo.versionCode.toLong()
                                Pair(pInfo.versionName ?: "1.1.0", vCode)
                            } catch (_: Exception) {
                                Pair("1.1.0", 11L)
                            }
                        }

                        var isCheckingUpdate by remember { mutableStateOf(false) }
                        var releaseInfoState by remember { mutableStateOf<GitHubReleaseInfo?>(null) }
                        var updateCheckError by remember { mutableStateOf<String?>(null) }

                        fun checkAppUpdate() {
                            coroutineScope.launch {
                                isCheckingUpdate = true
                                updateCheckError = null
                                val res = CloudApiClient.checkLatestReleaseInfo(context)
                                isCheckingUpdate = false
                                res.onSuccess { info ->
                                    releaseInfoState = info
                                }.onFailure { err ->
                                    updateCheckError = "Check failed: ${err.message ?: "Network error"}"
                                }
                            }
                        }

                        LaunchedEffect(Unit) {
                            checkAppUpdate()
                        }

                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("App Version Information", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                                        Text("v$versionName (Build $versionCode)", fontSize = 12.sp, color = Color(0xFF1DB954))
                                    }
                                    if (isCheckingUpdate) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color(0xFF1DB954), strokeWidth = 2.dp)
                                    } else {
                                        IconButton(onClick = { checkAppUpdate() }) {
                                            Text("🔄", fontSize = 14.sp)
                                        }
                                    }
                                }

                                if (releaseInfoState != null) {
                                    val info = releaseInfoState!!
                                    val dateStr = if (info.publishedEpochMs > 0) {
                                        SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(info.publishedEpochMs))
                                    } else info.publishedAt

                                    if (info.isUpdateAvailable) {
                                        Text("✨ New update available on GitHub!", fontWeight = FontWeight.Bold, color = Color(0xFF25D366), fontSize = 12.sp)
                                        Text("GitHub Released: $dateStr", fontSize = 11.sp, color = Color.LightGray)
                                    } else {
                                        Text("✅ App is up-to-date (Latest Release: $dateStr)", fontSize = 11.sp, color = Color.LightGray)
                                    }
                                } else if (updateCheckError != null) {
                                    Text(updateCheckError!!, fontSize = 11.sp, color = Color(0xFFFF6B6B))
                                }

                                Button(
                                    onClick = {
                                        val url = releaseInfoState?.htmlUrl ?: "https://github.com/Ganapathiraj-A/VoiceChanger/releases/tag/latest"
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                        context.startActivity(intent)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(40.dp),
                                    shape = RoundedCornerShape(6.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (releaseInfoState?.isUpdateAvailable == true) Color(0xFF1DB954) else Color(0xFF333333)
                                    )
                                ) {
                                    Text(
                                        if (releaseInfoState?.isUpdateAvailable == true) "📥 Download New Update from GitHub" else "🌐 View GitHub Latest Release",
                                        color = if (releaseInfoState?.isUpdateAvailable == true) Color.Black else Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { showSettingsDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954))
                    ) {
                        Text("Done", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                containerColor = Color(0xFF1E1E1E)
            )
        }
    }
}
