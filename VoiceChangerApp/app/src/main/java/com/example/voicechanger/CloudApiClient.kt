package com.example.voicechanger

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
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

object CloudApiClient {
    private const val DIRECT_CLOUD_URL = "https://nationally-walnut-distributions-offline.trycloudflare.com"
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
        .readTimeout(60, TimeUnit.SECONDS)
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

    suspend fun submitJob(context: Context, fileUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            val idToken = getGcpIdToken(context)
            val fileName = getFileName(context, fileUri) ?: "input_audio.mp3"
            LogManager.i(context, "JOB", "Submitting file: $fileName")

            val inputStream: InputStream? = context.contentResolver.openInputStream(fileUri)
            if (inputStream == null) {
                LogManager.e(context, "JOB", "Cannot open input stream for $fileUri")
                return@withContext Result.failure<String>(Exception("Cannot open audio file stream"))
            }

            val bytes = inputStream.readBytes()
            inputStream.close()
            LogManager.i(context, "JOB", "File size read: ${bytes.size} bytes")

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
                .url("$DIRECT_CLOUD_URL/jobs/submit")
                .header("Authorization", "Bearer $idToken")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val err = "Job submit failed HTTP ${response.code}"
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
            LogManager.e(context, "JOB", "Submit exception", e)
            Result.failure(e)
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
                val msg = json.optString("status_msg", "Processing audio...")
                val filename = json.optString("filename", null)

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

                val outName = if (originalFileName.endsWith(".mp3", ignoreCase = true)) {
                    "converted_${originalFileName}"
                } else {
                    "converted_${originalFileName.substringBeforeLast(".")}.mp3"
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
}
