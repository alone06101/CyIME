package com.kingzcheung.xime.settings

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.kingzcheung.xime.plugin.SecureValueCipher
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** WebDAV 云端备份配置：服务器地址、账号、密码（硬件加密存），以及自动备份开关与频率。 */
object WebDavConfig {
    internal const val PREFS = "webdav_backup"
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

    /** 按日期生成备份文件名，如 CyIME-2026-11-25-1530.zip（同日多次备份不互相覆盖）。 */
    fun backupFileName(): String =
        "CyIME-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date()) + ".zip"

    /** 云端的备份文件名只允许这套字符，防止 PROPFIND 返回的异常名字拼出目录穿越路径。 */
    private val SAFE_NAME = Regex("[A-Za-z0-9._-]+\\.zip")

    internal fun requireSafeName(fileName: String): String {
        val name = fileName.substringAfterLast('/')
        require(name.isNotBlank() && name.matches(SAFE_NAME)) { "非法的备份文件名" }
        return name
    }

    /** 手动触发：生成并上传备份包（备份先写 cacheDir 临时文件，不经过内存）。 */
    fun backupAndUpload(context: Context, includePersonal: Boolean, includeClipboardHistory: Boolean, includePluginConfig: Boolean): Result<Unit> = runCatching {
        val tmp = File(context.cacheDir, "webdav-upload.zip")
        try {
            BackupManager.backupToFile(context, tmp, includePersonal, includeClipboardHistory, includePluginConfig)
            uploadFile(context, tmp, backupFileName()).getOrThrow()
        } finally {
            tmp.delete()
        }
    }

    /** 上传一段内容到 WebDAV（小包用；大包请走 [uploadFile]）。先确保目录存在，再 PUT 文件。 */
    fun upload(context: Context, bytes: ByteArray, fileName: String): Result<Unit> = runCatching {
        val name = requireSafeName(fileName)
        val base = WebDavConfig.baseUrl(context).trim().trimEnd('/')
        require(base.isNotBlank()) { "未配置 WebDAV 服务器地址" }
        require(base.startsWith("https://")) { "WebDAV 地址必须是 https（Basic 认证密码不能走明文）" }
        val username = WebDavConfig.username(context)
        val password = WebDavConfig.password(context)
        val auth = if (username.isNotEmpty()) Credentials.basic(username, password) else null

        ensureDirectory(base, auth)
        val request = Request.Builder().url("$base/$DIR_NAME/$name")
            .apply { auth?.let { header("Authorization", it) } }
            .put(bytes.toRequestBody(ZIP_MEDIA_TYPE.toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            check(response.code in 200..299) { "上传失败：HTTP ${response.code}" }
        }
    }.onFailure { Log.e(TAG, "upload failed", it) }

    /** 上传一个本地备份文件（流式，不把整包读进内存）。 */
    fun uploadFile(context: Context, file: File, fileName: String): Result<Unit> = runCatching {
        val name = requireSafeName(fileName)
        val base = WebDavConfig.baseUrl(context).trim().trimEnd('/')
        require(base.isNotBlank()) { "未配置 WebDAV 服务器地址" }
        require(base.startsWith("https://")) { "WebDAV 地址必须是 https（Basic 认证密码不能走明文）" }
        val username = WebDavConfig.username(context)
        val password = WebDavConfig.password(context)
        val auth = if (username.isNotEmpty()) Credentials.basic(username, password) else null

        ensureDirectory(base, auth)
        val request = Request.Builder().url("$base/$DIR_NAME/$name")
            .apply { auth?.let { header("Authorization", it) } }
            .put(file.asRequestBody(ZIP_MEDIA_TYPE.toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            check(response.code in 200..299) { "上传失败：HTTP ${response.code}" }
        }
    }.onFailure { Log.e(TAG, "uploadFile failed", it) }

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
        require(base.startsWith("https://")) { "WebDAV 地址必须是 https（Basic 认证密码不能走明文）" }
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

    /** 从云端下载一个备份文件到 cacheDir（流式写盘，名字过白名单）。 */
    fun downloadToFile(context: Context, fileName: String): Result<File> = runCatching {
        val name = requireSafeName(fileName)
        val base = WebDavConfig.baseUrl(context).trim().trimEnd('/')
        require(base.isNotBlank()) { "未配置 WebDAV 服务器地址" }
        require(base.startsWith("https://")) { "WebDAV 地址必须是 https（Basic 认证密码不能走明文）" }
        val username = WebDavConfig.username(context)
        val password = WebDavConfig.password(context)
        val auth = if (username.isNotEmpty()) Credentials.basic(username, password) else null
        val target = File(context.cacheDir, "webdav-download.zip")
        val request = Request.Builder().url("$base/$DIR_NAME/$name")
            .apply { auth?.let { header("Authorization", it) } }
            .get().build()
        try {
            client.newCall(request).execute().use { response ->
                check(response.code in 200..299) { "下载失败：HTTP ${response.code}" }
                val body = response.body ?: error("下载内容为空")
                // 边下边写，顺便卡住体积，避免服务端回一个巨大文件把存储冲满。
                val limit = BackupManager.maxArchiveBytes()
                body.byteStream().use { input ->
                    target.outputStream().use { out ->
                        val chunk = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(chunk)
                            if (read < 0) break
                            total += read
                            require(total <= limit) { "备份文件过大（本机上限 ${limit / 1024 / 1024} MB）" }
                            out.write(chunk, 0, read)
                        }
                    }
                }
                check(target.length() > 0) { "下载内容为空" }
            }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        target
    }.onFailure { Log.e(TAG, "downloadToFile failed", it) }

    /** 旧接口：下载到内存（仅用于小包；正常恢复走 [downloadToFile]）。 */
    fun download(context: Context, fileName: String): Result<ByteArray> =
        downloadToFile(context, fileName).mapCatching { it.readBytes() }

    /** 从 WebDAV PROPFIND 的 XML 里解析出备份文件名。 */
    private fun parseFileNames(xml: String): List<String> {
        val regex = Regex("<[^>]*href[^>]*>([^<]+)</[^>]*href[^>]*>", RegexOption.IGNORE_CASE)
        val result = mutableListOf<String>()
        for (m in regex.findAll(xml)) {
            // 服务器可能回 URL 编码的名字（空格 %20 等），先解码再取末段。
            val raw = m.groupValues[1].trim()
            val decoded = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
            val name = decoded.substringAfterLast('/').trim()
            if (name.endsWith(".zip") && name.matches(SAFE_NAME)) result.add(name)
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
        // 自动备份只在非计费网络跑（几百 MB 不该拿手机流量传），失败按指数退避重试。
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()
        val request = PeriodicWorkRequestBuilder<WebDavAutoBackupWorker>(intervalDays, TimeUnit.DAYS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
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
