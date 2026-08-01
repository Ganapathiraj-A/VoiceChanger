package com.example.voicechanger

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.TimeUnit

data class JobStatusResponse(
    val status: String,
    val progressPercent: Float,
    val etaSeconds: Float,
    val elapsedSeconds: Float,
    val statusMsg: String,
    val filename: String? = null
)

data class DiarizePreviewResponse(
    val status: String,
    val previewId: String,
    val speakerAPct: Float,
    val speakerADurSec: Float,
    val speakerAUrl: String,
    val speakerBPct: Float,
    val speakerBDurSec: Float,
    val speakerBUrl: String
)

data class PreviewStatusResponse(
    val status: String,
    val progressPercent: Float,
    val etaSeconds: Float,
    val elapsedSeconds: Float,
    val stepMsg: String,
    val previewResponse: DiarizePreviewResponse? = null
)

object CloudApiClient {
    private const val DIRECT_CLOUD_URL = "https://voice-changer-service-ffboj7vvya-el.a.run.app"
    private const val SA_EMAIL = "voice-changer-app-sa@antigravity-app-5c1ff.iam.gserviceaccount.com"
    private const val PRIVATE_KEY_PEM = """-----BEGIN PRIVATE KEY-----
MIIEvwIBADANBgkqhkiG9w0BAQEFAASCBKkwggSlAgEAAoIBAQC+CH9ak2wEZYc8
stoARrEbis2Uu49R39IDJX2ATd9E5HzW/WnEfod/1p5PPyZko2mF20fGmf2u2WpW
Znf/oCWcg9ULgne9v3QrdJ1nxOl/ww4rWc4Obb6vOBdJ/skvQhI0f90hGMkqlZ6p
YnrDu3d/U4ekkTrGs/q+M7UVYw+AaiBVGtd32fkTguvluevMswhBIJJ7MaszlZqn
afhAiwwSFqfsCRJ0WgVVpi93HC1F+FTtt8ZZZyNh00Sz+GQ7NkttKvhsF8eIkJzE
zbOtji1JLlW/UoT2VWglsengyTgZBWlcIftpCHGnvl3xkbktZyM0dAyshCmxhCeA
1oUuPCMBAgMBAAECggEAFHfv1763TMXuyh/tkUgL/Y521EVbi5MTGNmp6e75VH9T
3whOoyaJy8A/LwP7S626SPu0gHTHuVSbupCw7iy+wFwGz4WPBjYf+ipGZg30pJlK
5mp24mD5v//HqmWyH8/7DAKVu+HikR6qh04fMQP5PBKwMo6eCRcLs/73y0TvP4J/
EQq2DAmdrLHenAPIcS6FTBGnU7V6kY2/TJVp7ecN1TkQoWEx0JXU0otAnqv/mLNW
kPhxPcAHCUU8MGlR13KOgc4hlfG5Y8RET7orNg1c6O+robvU43ObO+nJs8TUwX7J
b+Ig521vlRsozWu6HuhVMNfdWPNZ4Uww9XLljjHpDQKBgQD00K4c5NaGaWlRX9H+
GymvqjyvjmK0DLyRlaOc5ZHVyi2td+IlUPHNoPUSrPVeqTn9Z9dcSeBxr6wYqSlL
VDpp4yDbZo8tSblKpVpjy9CxLE/r0FNBmy1Q2KON36j19RmiNAjhagO4CEfYAddC
lfYmyrEEX5wa9zILfr14Nv0zZwKBgQDGtxiWygEscVMJm/pDLFVusK1Kxurz2bL2
MwEylMgeJ3S3M2yMres7IzDe7EqgvzwQyyTXEYwbADkt8NxJ8Sj/TN9OO0SqJfmp
YOSJNb9DYdyLLLRjVQvHY4D5H62/kl3opFL0AXjiSx5BchWm6Sr9UDqOtUEGCWnt
PQjy0JsdVwKBgQC5kdj44+lM12hSm2xkzhgqJMN9W1OsIR9qx1/O1SFXSbqYDBBq
stGnScOa1WnkyCfB0s2nEgTEiCHOS6OWixEAJH9Kb5JGBOUkFPTQQrU9J1apbC8/
wq1149EOAKRlU9WLYx/8Jc0N2ZEDxllyCpQcUXYe145PzmKr3fUmw5/oLwKBgQCI
/AstV4+7jVuK0kWRLOyv44dydvHcrAQciEiZD8tsThK9f+uihvoTyEyWQBmp+mpz
wTZiNCx7KIpCSznwlxiF9f4yNdU93fPfeXXRyIVS9BFOt8CagTQffU6Zbecems21
5CFzJ9inVtVClFystSv3d+kGG5j5il/FNUAH8xoa/QKBgQDQRhjMK/Ap21khcarr
cBUWDbBaR1qeeYUQbH6INrDZCIUhXDL1PvY5OVBbHqQfmE17C0Z/72gHky+DpgXv
PbLGwMoIk9HT4J/mmQz3Dvy4tIpbDMfQooJVuoWLyiChRQKK4tGqQq5S0pFFK7E/
i+fozqmCTkpHUig37W5sLesojw==
-----END PRIVATE KEY-----"""

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cachedIdToken: String? = null
    @Volatile
    private var tokenExpiryTimeSec: Long = 0

    private fun getGcpIdToken(context: Context): String {
        val nowSec = System.currentTimeMillis() / 1000
        val token = cachedIdToken
        if (token != null && nowSec < tokenExpiryTimeSec - 120) {
            return token
        }

        synchronized(this) {
            val curToken = cachedIdToken
            if (curToken != null && nowSec < tokenExpiryTimeSec - 120) {
                return curToken
            }

            LogManager.i(context, "AUTH", "Generating new GCP RSA OIDC token...")

            val headerJson = JSONObject().apply {
                put("alg", "RS256")
                put("typ", "JWT")
            }.toString()

            val payloadJson = JSONObject().apply {
                put("iss", SA_EMAIL)
                put("sub", SA_EMAIL)
                put("aud", "https://oauth2.googleapis.com/token")
                put("target_audience", DIRECT_CLOUD_URL)
                put("iat", nowSec)
                put("exp", nowSec + 3600)
            }.toString()

            val encodedHeader = base64UrlEncode(headerJson.toByteArray(Charsets.UTF_8))
            val encodedPayload = base64UrlEncode(payloadJson.toByteArray(Charsets.UTF_8))
            val unsignedJwt = "$encodedHeader.$encodedPayload"

            val signatureBytes = signSha256Rsa(unsignedJwt.toByteArray(Charsets.UTF_8), PRIVATE_KEY_PEM)
            val encodedSignature = base64UrlEncode(signatureBytes)
            val signedJwt = "$unsignedJwt.$encodedSignature"

            val formBody = FormBody.Builder()
                .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                .add("assertion", signedJwt)
                .build()

            val tokenReq = Request.Builder()
                .url("https://oauth2.googleapis.com/token")
                .post(formBody)
                .build()

            client.newCall(tokenReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errMsg = "Auth token request failed: HTTP ${resp.code}"
                    LogManager.e(context, "AUTH", errMsg)
                    throw Exception(errMsg)
                }
                val respStr = resp.body?.string() ?: ""
                val json = JSONObject(respStr)
                val newIdToken = json.getString("id_token")
                cachedIdToken = newIdToken
                tokenExpiryTimeSec = nowSec + 3000
                LogManager.i(context, "AUTH", "Successfully obtained GCP OIDC Token")
                return newIdToken
            }
        }
    }

    private fun base64UrlEncode(data: ByteArray): String {
        return Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun signSha256Rsa(data: ByteArray, pemKey: String): ByteArray {
        val cleanPem = pemKey
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        val privateKeyBytes = Base64.decode(cleanPem, Base64.DEFAULT)
        val spec = PKCS8EncodedKeySpec(privateKeyBytes)
        val kf = KeyFactory.getInstance("RSA")
        val privateKey = kf.generatePrivate(spec)

        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(privateKey)
        signer.update(data)
        return signer.sign()
    }

    private fun readBytesFromUri(context: Context, fileUri: Uri): ByteArray {
        // Try 1: ContentResolver openInputStream
        try {
            val stream = context.contentResolver.openInputStream(fileUri)
            if (stream != null) {
                return stream.use { it.readBytes() }
            }
        } catch (_: Exception) {}

        // Try 2: ParcelFileDescriptor
        try {
            val pfd = context.contentResolver.openFileDescriptor(fileUri, "r")
            if (pfd != null) {
                return FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
            }
        } catch (_: Exception) {}

        // Try 3: Direct File path resolution (e.g. primary:Download/...)
        val path = fileUri.path
        if (!path.isNullOrEmpty()) {
            val directFile = File(path)
            if (directFile.exists() && directFile.canRead()) {
                return directFile.readBytes()
            }

            val decodedPath = Uri.decode(path)
            if (decodedPath.contains("primary:")) {
                val relPath = decodedPath.substringAfter("primary:")
                val sdcardFile = File(Environment.getExternalStorageDirectory(), relPath)
                if (sdcardFile.exists() && sdcardFile.canRead()) {
                    return sdcardFile.readBytes()
                }
            }
        }

        throw Exception("Unable to open audio file stream for URI: $fileUri")
    }

    suspend fun submitDiarizePreviewJob(context: Context, fileUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            val idToken = getGcpIdToken(context)
            val fileName = getFileName(context, fileUri) ?: "input_audio.mp4"

            val bytes = readBytesFromUri(context, fileUri)
            val mediaType = (context.contentResolver.getType(fileUri) ?: "audio/mpeg").toMediaTypeOrNull()

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, bytes.toRequestBody(mediaType))
                .build()

            val request = Request.Builder()
                .url("$DIRECT_CLOUD_URL/diarize/preview/submit")
                .header("Authorization", "Bearer $idToken")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Preview submit failed HTTP ${response.code}"))
                }
                val bodyStr = response.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                Result.success(json.getString("preview_id"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun pollPreviewStatus(context: Context, previewId: String): Result<PreviewStatusResponse> = withContext(Dispatchers.IO) {
        try {
            val idToken = getGcpIdToken(context)
            val request = Request.Builder()
                .url("$DIRECT_CLOUD_URL/diarize/preview/status/$previewId")
                .header("Authorization", "Bearer $idToken")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Poll preview failed HTTP ${response.code}"))
                }
                val bodyStr = response.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                val status = json.optString("status", "processing")
                val pct = json.optDouble("progress_percent", 0.0).toFloat()
                val eta = json.optDouble("eta_seconds", 3.0).toFloat()
                val elapsed = json.optDouble("elapsed_seconds", 0.0).toFloat()
                val step = json.optString("step", "Analyzing speakers...")

                var prevResp: DiarizePreviewResponse? = null
                if (status == "completed" && json.has("speaker_a")) {
                    val spkA = json.getJSONObject("speaker_a")
                    val spkB = json.getJSONObject("speaker_b")
                    prevResp = DiarizePreviewResponse(
                        status = "success",
                        previewId = previewId,
                        speakerAPct = spkA.optDouble("speech_percent", 50.0).toFloat(),
                        speakerADurSec = spkA.optDouble("duration_seconds", 0.0).toFloat(),
                        speakerAUrl = "$DIRECT_CLOUD_URL${spkA.getString("sample_url")}",
                        speakerBPct = spkB.optDouble("speech_percent", 50.0).toFloat(),
                        speakerBDurSec = spkB.optDouble("duration_seconds", 0.0).toFloat(),
                        speakerBUrl = "$DIRECT_CLOUD_URL${spkB.getString("sample_url")}"
                    )
                }
                Result.success(PreviewStatusResponse(status, pct, eta, elapsed, step, prevResp))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun submitJob(
        context: Context,
        fileUri: Uri,
        preserveSpeakerCluster: Int? = null,
        conversionMode: String = "praat_psola",
        targetProfileName: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val idToken = getGcpIdToken(context)
            val fileName = getFileName(context, fileUri) ?: "input_audio.mp3"
            LogManager.i(context, "JOB", "Submitting file: $fileName (URI: $fileUri, preserve_cluster: $preserveSpeakerCluster, mode: $conversionMode, profile: $targetProfileName)")

            val bytes: ByteArray = try {
                readBytesFromUri(context, fileUri)
            } catch (e: Exception) {
                val readErr = "Failed to read file: ${e.message ?: e.javaClass.simpleName}"
                LogManager.e(context, "JOB", readErr, e)
                return@withContext Result.failure(Exception(readErr, e))
            }

            LogManager.i(context, "JOB", "File size read successfully: ${bytes.size} bytes")

            val mediaType = (context.contentResolver.getType(fileUri) ?: "audio/mpeg").toMediaTypeOrNull()

            val builder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    fileName,
                    bytes.toRequestBody(mediaType)
                )
                .addFormDataPart("conversion_mode", conversionMode)

            if (preserveSpeakerCluster != null) {
                builder.addFormDataPart("preserve_speaker_cluster", preserveSpeakerCluster.toString())
            }

            if (!targetProfileName.isNullOrBlank()) {
                builder.addFormDataPart("target_profile_name", targetProfileName)
            }

            val requestBody = builder.build()

            val request = Request.Builder()
                .url("$DIRECT_CLOUD_URL/jobs/submit")
                .header("Authorization", "Bearer $idToken")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val err = "Job submit failed HTTP ${response.code}: ${response.body?.string()}"
                    LogManager.e(context, "JOB", err)
                    return@withContext Result.failure(Exception(err))
                }
                val bodyStr = response.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                val jobId = json.getString("job_id")
                LogManager.i(context, "JOB", "Job submitted successfully! Job ID: $jobId")
                Result.success(jobId)
            }
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            LogManager.e(context, "JOB", "Submit exception: $msg", e)
            Result.failure(Exception(msg, e))
        }
    }

    suspend fun pollJobStatus(context: Context, jobId: String): Result<JobStatusResponse> = withContext(Dispatchers.IO) {
        try {
            val idToken = getGcpIdToken(context)
            val request = Request.Builder()
                .url("$DIRECT_CLOUD_URL/jobs/$jobId")
                .header("Authorization", "Bearer $idToken")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val err = "Poll status failed HTTP ${response.code}"
                    LogManager.e(context, "POLL", err)
                    return@withContext Result.failure(Exception(err))
                }
                val bodyStr = response.body?.string() ?: ""
                val json = JSONObject(bodyStr)

                val status = json.optString("status", "processing")
                val pct = json.optDouble("progress_percent", 0.0).toFloat()
                val eta = json.optDouble("eta_seconds", 30.0).toFloat()
                val elapsed = json.optDouble("elapsed_seconds", 0.0).toFloat()
                val msg = if (json.has("status_msg")) json.optString("status_msg", "Processing audio...") else json.optString("step", "Processing audio...")
                val filename = if (json.has("filename") && !json.isNull("filename")) json.getString("filename") else null

                LogManager.d(context, "POLL", "Status=$status, Pct=${pct}%, ETA=${eta}s, Msg=$msg")
                Result.success(JobStatusResponse(status, pct, eta, elapsed, msg, filename))
            }
        } catch (e: Exception) {
            LogManager.e(context, "POLL", "Poll exception", e)
            Result.failure(e)
        }
    }

    suspend fun downloadAndSaveResult(context: Context, jobId: String, originalFileName: String): Result<File> = withContext(Dispatchers.IO) {
        try {
            val idToken = getGcpIdToken(context)
            LogManager.i(context, "DOWNLOAD", "Downloading result for job $jobId...")
            val request = Request.Builder()
                .url("$DIRECT_CLOUD_URL/jobs/$jobId/download")
                .header("Authorization", "Bearer $idToken")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val err = "Download failed HTTP ${response.code}"
                    LogManager.e(context, "DOWNLOAD", err)
                    return@withContext Result.failure(Exception(err))
                }

                val bytes = response.body?.bytes()
                    ?: return@withContext Result.failure(Exception("Empty download body"))

                val cleanName = originalFileName.substringAfterLast('/').substringAfterLast(':').ifEmpty { "audio_input.mp3" }
                val outName = if (cleanName.endsWith(".mp3", ignoreCase = true)) {
                    "converted_${cleanName}"
                } else {
                    "converted_${cleanName.substringBeforeLast(".")}.mp3"
                }

                val savedFile = saveToPublicStorage(context, outName, bytes)
                LogManager.i(context, "DOWNLOAD", "Saved converted file to: ${savedFile.absolutePath}")
                Result.success(savedFile)
            }
        } catch (e: Exception) {
            LogManager.e(context, "DOWNLOAD", "Download exception", e)
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

    suspend fun checkLatestReleaseInfo(context: Context): Result<GitHubReleaseInfo> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://api.github.com/repos/Ganapathiraj-A/VoiceChanger/releases/tags/latest")
                .header("User-Agent", "VoiceChanger-Android-App")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                if (resp.isSuccessful && bodyStr.isNotEmpty()) {
                    val json = JSONObject(bodyStr)
                    val publishedAt = json.optString("published_at", "")
                    val htmlUrl = json.optString("html_url", "https://github.com/Ganapathiraj-A/VoiceChanger/releases/tag/latest")
                    val body = json.optString("body", "")

                    var publishedEpochMs = 0L
                    if (publishedAt.isNotEmpty()) {
                        try {
                            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                            publishedEpochMs = sdf.parse(publishedAt)?.time ?: 0L
                        } catch (_: Exception) {}
                    }

                    val appInstallTime = try {
                        val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                        pInfo.lastUpdateTime
                    } catch (_: Exception) {
                        0L
                    }

                    val isUpdateAvailable = (publishedEpochMs > 0 && publishedEpochMs > appInstallTime + 120_000)

                    Result.success(
                        GitHubReleaseInfo(
                            publishedAt = publishedAt,
                            publishedEpochMs = publishedEpochMs,
                            isUpdateAvailable = isUpdateAvailable,
                            releaseNotes = body,
                            htmlUrl = htmlUrl
                        )
                    )
                } else {
                    Result.failure(Exception("GitHub API returned HTTP ${resp.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

data class GitHubReleaseInfo(
    val publishedAt: String,
    val publishedEpochMs: Long,
    val isUpdateAvailable: Boolean,
    val releaseNotes: String?,
    val htmlUrl: String
)
