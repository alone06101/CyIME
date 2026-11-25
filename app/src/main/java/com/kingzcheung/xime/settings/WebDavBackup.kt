package com.kingzcheung.xime.settings

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.kingzcheung.xime.plugin.SecureValueCipher
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** WebDAV 云端备份配置：服务器地址、账号、密码（硬件加密存），以及自动备份开关与频率。 */
object WebDavConfig {
    private const val PREFS = "webdav_backup"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"
    private const val KEY_AUTO_ENABLED = "auto_enabled"
    private const val KEY_FREQUENCY = "frequency"

    enum class Frequency(val id: String, val label: String) {
        DAILY("daily", "每天"), WEEKLY("weekly", "每周");
        companion object {
            fun of(id: String?): Frequency = entries.firstOrNull { it.id == id } ?: DAILY
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun baseUrl(context: Context): String = prefs(context).getString(KEY_BASE_URL, "") ?: ""
    fun username(context: Context): String = prefs(context).getString(KEY_USERNAME, "") ?: ""
    fun password(context: Context): String =
        prefs(context).getString(KEY_PASSWORD, null)?.let { SecureValueCipher.decrypt(it) } ?: ""
    fun isConfigured(context: Context): Boolean = baseUrl(context).isNotBlank()
    fun autoBackupEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_ENABLED, false)
    fun frequency(context: Context): Frequency = Frequency.of(prefs(context).getString(KEY_FREQUENCY, null))

    fun save(context: Context, baseUrl: String, username: String, password: String) {
        prefs(context).edit()
            .putString(KEY_BASE_URL, baseUrl.trim())
            .putString(KEY_USERNAME, username.trim())
            .putString(KEY_PASSWORD, SecureValueCipher.encrypt(password))
            .apply()
    }

    fun setAutoBackup(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_ENABLED, enabled).apply()
    }

    fun setFrequency(context: Context, frequency: Frequency) {
        prefs(context).edit().putString(KEY_FREQUENCY, frequency.id).apply()
    }
}

/** WebDAV 云端备份：把本地备份包 PUT 到 WebDAV 服务器，支持 Basic Auth。 */
object WebDavBackup {
    private const val TAG = "WebDavBackup"
    private const val DIR_NAME = "CyIME-backup"
    private const val ZIP_MEDIA_TYPE = "application/zip"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    /** 按日期生成备份文件名，如 CyIME-2026-11-25.zip。 */
    fun backupFileName(): String =
        "CyIME-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".zip"

    /** 手动触发：生成并上传备份包。 */
    fun backupAndUpload(context: Context, includePersonal: Boolean, includeClipboardHistory: Boolean, includePluginConfig: Boolean): Result<Unit> = runCatching {
        val bytes = BackupManager.backupToBytes(context, includePersonal, includeClipboardHistory, includePluginConfig)
        upload(context, bytes, backupFileName())
    }

    /** 上传一段内容到 WebDAV。先确保目录存在，再 PUT 文件。 */
    fun upload(context: Context, bytes: ByteArray, fileName: String): Result<Unit> = runCatching {
        val base = WebDavConfig.baseUrl(context).trim().trimEnd('/')
        require(base.isNotBlank()) { "未配置 WebDAV 服务器地址" }
        val username = WebDavConfig.username(context)
        val password = WebDavConfig.password(context)
        val auth = if (username.isNotEmpty()) Credentials.basic(username, password) else null

        ensureDirectory(base, auth)
        val fileUrl = "$base/$DIR_NAME/$fileName"
        val request = Request.Builder().url(fileUrl)
            .apply { auth?.let { header("Authorization", it) } }
            .put(bytes.toRequestBody(ZIP_MEDIA_TYPE.toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            check(response.code in 200..299) { "上传失败：HTTP ${response.code}" }
        }
    }.onFailure { Log.e(TAG, "upload failed", it) }

    /** 确保 WebDAV 目录存在：MKCOL，已存在（405）也视为成功。 */
    private fun ensureDirectory(base: String, auth: String?) {
        val dirUrl = "$base/$DIR_NAME/"
        val request = Request.Builder().url(dirUrl)
            .apply { auth?.let { header("Authorization", it) } }
            .method("MKCOL", null)
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code !in 200..299 && response.code != 405) {
                check(false) { "创建目录失败：HTTP ${response.code}" }
            }
        }
    }

    /** 列出云端 CyIME-backup/ 目录里的备份文件名（新的在前）。 */
    fun listBackups(context: Context): Result<List<String>> = runCatching {
        val base = WebDavConfig.baseUrl(context).trim().trimEnd('/')
        require(base.isNotBlank()) { "未配置 WebDAV 服务器地址" }
        val username = WebDavConfig.username(context)
        val password = WebDavConfig.password(context)
        val auth = if (username.isNotEmpty()) Credentials.basic(username, password) else null
        val request = Request.Builder().url("$base/$DIR_NAME/")
            .apply { auth?.let { header("Authorization", it) } }
            .method("PROPFIND", null)
            .header("Depth", "1")
            .build()
        client.newCall(request).execute().use { response ->
            check(response.code in 200..299) { "列出文件失败：HTTP ${response.code}" }
            parseFileNames(response.body?.string().orEmpty())
        }
    }.onFailure { Log.e(TAG, "listBackups failed", it) }

    /** 从云端下载一个备份文件的内容。 */
    fun download(context: Context, fileName: String): Result<ByteArray> = runCatching {
        val base = WebDavConfig.baseUrl(context).trim().trimEnd('/')
        require(base.isNotBlank()) { "未配置 WebDAV 服务器地址" }
        val username = WebDavConfig.username(context)
        val password = WebDavConfig.password(context)
        val auth = if (username.isNotEmpty()) Credentials.basic(username, password) else null
        val request = Request.Builder().url("$base/$DIR_NAME/$fileName")
            .apply { auth?.let { header("Authorization", it) } }
            .get().build()
        client.newCall(request).execute().use { response ->
            check(response.code in 200..299) { "下载失败：HTTP ${response.code}" }
            response.body?.bytes() ?: error("下载内容为空")
        }
    }.onFailure { Log.e(TAG, "download failed", it) }

    /** 从 WebDAV PROPFIND 的 XML 里解析出备份文件名。 */
    private fun parseFileNames(xml: String): List<String> {
        val regex = Regex("<[^>]*href[^>]*>([^<]+)</[^>]*href[^>]*>", RegexOption.IGNORE_CASE)
        val result = mutableListOf<String>()
        for (m in regex.findAll(xml)) {
            val name = m.groupValues[1].trim().substringAfterLast('/').trim()
            if (name.endsWith(".zip")) result.add(name)
        }
        return result.distinct().sortedDescending()
    }

    /** 打开/关闭自动备份（每天或每周）。 */
    fun scheduleAutoBackup(context: Context, enabled: Boolean, frequency: WebDavConfig.Frequency) {
        val name = "webdav_auto_backup"
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(name)
            return
        }
        val intervalDays = if (frequency == WebDavConfig.Frequency.DAILY) 1L else 7L
        val request = PeriodicWorkRequestBuilder<WebDavAutoBackupWorker>(intervalDays, TimeUnit.DAYS).build()
        wm.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

/** 自动备份 Worker：按当前备份选项生成并上传到 WebDAV。 */
class WebDavAutoBackupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        if (!WebDavConfig.isConfigured(ctx) || !WebDavConfig.autoBackupEnabled(ctx)) return Result.success()
        return try {
            val result = WebDavBackup.backupAndUpload(
                ctx,
                includePersonal = com.kingzcheung.xime.ui.settings.BackupChoices.includePersonal(ctx),
                includeClipboardHistory = com.kingzcheung.xime.ui.settings.BackupChoices.includeClipboardHistory(ctx),
                includePluginConfig = com.kingzcheung.xime.ui.settings.BackupChoices.includePluginConfig(ctx),
            )
            if (result.isSuccess) Result.success() else Result.retry()
        } catch (e: Exception) {
            Log.w("WebDavBackup", "auto backup failed", e)
            Result.retry()
        }
    }
}
