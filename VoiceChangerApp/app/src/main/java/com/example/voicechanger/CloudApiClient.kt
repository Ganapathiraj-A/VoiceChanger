package com.example.voicechanger

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class JobStatusResponse(
    val status: String,
    val progressPercent: Float,
    val etaSeconds: Float,
    val elapsedSeconds: Float,
    val statusMsg: String,
    val filename: String? = null
)

object CloudApiClient {
    private const val BASE_URL = "https://655ef1a6c5397f.lhr.life"

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()

    suspend fun submitJob(context: Context, fileUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            val fileName = getFileName(context, fileUri) ?: "input_audio.mp3"
            val inputStream: InputStream = context.contentResolver.openInputStream(fileUri)
                ?: return@withContext Result.failure(Exception("Cannot open audio file stream"))

            val bytes = inputStream.readBytes()
            inputStream.close()

            val mediaType = (context.contentResolver.getType(fileUri) ?: "audio/mpeg").toMediaTypeOrNull()

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    fileName,
                    bytes.toRequestBody(mediaType)
                )
                .build()

            val request = Request.Builder()
                .url("$BASE_URL/jobs/submit")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP Error: ${response.code}"))
                }
                val bodyStr = response.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                val jobId = json.getString("job_id")
                Result.success(jobId)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun pollJobStatus(jobId: String): Result<JobStatusResponse> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/jobs/$jobId")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP Error: ${response.code}"))
                }
                val bodyStr = response.body?.string() ?: ""
                val json = JSONObject(bodyStr)

                val status = json.optString("status", "processing")
                val pct = json.optDouble("progress_percent", 0.0).toFloat()
                val eta = json.optDouble("eta_seconds", 30.0).toFloat()
                val elapsed = json.optDouble("elapsed_seconds", 0.0).toFloat()
                val msg = json.optString("status_msg", "Processing audio...")
                val filename = json.optString("filename", null)

                Result.success(JobStatusResponse(status, pct, eta, elapsed, msg, filename))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadAndSaveResult(context: Context, jobId: String, originalFileName: String): Result<File> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/jobs/$jobId/download")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Download failed HTTP ${response.code}"))
                }

                val bytes = response.body?.bytes()
                    ?: return@withContext Result.failure(Exception("Empty download body"))

                val outName = if (originalFileName.endsWith(".mp3", ignoreCase = true)) {
                    "converted_${originalFileName}"
                } else {
                    "converted_${originalFileName.substringBeforeLast(".")}.mp3"
                }

                val savedFile = saveToPublicStorage(context, outName, bytes)
                Result.success(savedFile)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun saveToPublicStorage(context: Context, fileName: String, data: ByteArray): File {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/VoiceChanger")
            }
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { os ->
                    os.write(data)
                }
            }
        }

        // Also save to app's external files directory for guaranteed FileProvider sharing
        val appMusicDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "VoiceChanger")
        if (!appMusicDir.exists()) {
            appMusicDir.mkdirs()
        }
        val outFile = File(appMusicDir, fileName)
        FileOutputStream(outFile).use { fos ->
            fos.write(data)
        }
        return outFile
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        name = cursor.getString(index)
                    }
                }
            }
        }
        if (name == null) {
            name = uri.path
            val cut = name?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                name = name.substring(cut + 1)
            }
        }
        return name
    }
}
