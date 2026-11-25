package com.kingzcheung.xime.settings

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 万象拼音（amzxyz/rime-wanxiang）运行时更新。
 *
 * 版本信息不写进 APK：每次检查时从 GitHub API 拉取 latest release 的
 * base 包下载地址与 sha256，动态构造 [MarketScheme]，复用 [XimeIndexSource]
 * 的下载与安装流程。万象发新版无需重新构建 APK。
 */
object WanxiangUpdate {
    private const val TAG = "WanxiangUpdate"
    private const val REPO = "amzxyz/rime-wanxiang"
    private const val ASSET_NAME = "rime-wanxiang-base.zip"
    private const val SCHEME_ID = "wanxiang"
    private const val PREFS = "wanxiang_update"
    private const val KEY_VERSION = "installed_version"

    data class Release(val version: String, val zipUrl: String, val sha256: String?, val sizeBytes: Long)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** 从 GitHub API 获取最新 release 的 base 包信息；失败返回 null。 */
    fun fetchLatestRelease(): Release? = try {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "CyIME")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            parseRelease(resp.body?.string() ?: return null)
        }
    } catch (e: Exception) {
        Log.w(TAG, "fetchLatestRelease failed", e)
        null
    }

    private fun parseRelease(json: String): Release? {
        val root = org.json.JSONObject(json)
        val version = root.optString("tag_name").removePrefix("v")
        if (version.isBlank()) return null
        val assets = root.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name") != ASSET_NAME) continue
            val url = asset.optString("browser_download_url")
            if (url.isBlank()) return null
            val digest = asset.optString("digest").removePrefix("sha256:")
            return Release(
                version = version,
                zipUrl = url,
                sha256 = digest.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) },
                sizeBytes = asset.optLong("size", 0L),
            )
        }
        return null
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 本机上次安装的万象拼音版本；没记录时为 null。 */
    fun installedVersion(context: Context): String? = prefs(context).getString(KEY_VERSION, null)

    private fun markInstalled(context: Context, version: String) {
        prefs(context).edit().putString(KEY_VERSION, version).apply()
    }

    /** 动态构造 MarketScheme（下载地址来自 GitHub 最新 release）。 */
    fun marketScheme(release: Release): MarketScheme = MarketScheme(
        id = SCHEME_ID,
        name = "万象拼音（标准版）",
        author = "amzxyz",
        description = "带声调的拼音方案：全拼、8 种双拼、中英混输、语言模型。",
        type = "remote",
        tags = listOf("拼音", "双拼", "万象"),
        homepage = "https://github.com/amzxyz/rime-wanxiang",
        currentVersion = release.version,
        versions = listOf(SchemeVersion(
            version = release.version,
            changelog = "万象拼音 $release.version（GitHub 最新 release）",
            downloadUrls = listOf(DownloadItem(
                url = release.zipUrl,
                sha256 = release.sha256,
                size = if (release.sizeBytes > 0) formatMegabytes(release.sizeBytes) else null,
            )),
        )),
    )

    private fun formatMegabytes(bytes: Long): String =
        String.format(java.util.Locale.US, "%.2f MB", bytes / 1024.0 / 1024.0)

    /** 检查更新：返回最新 release；检查失败返回 null。 */
    suspend fun checkUpdate(context: Context): Release? = withContext(Dispatchers.IO) {
        fetchLatestRelease()
    }

    /** 下载并安装万象拼音最新版；成功返回 true。 */
    suspend fun update(context: Context, release: Release): Boolean = withContext(Dispatchers.IO) {
        val scheme = marketScheme(release)
        val dl = XimeIndexSource.downloadScheme(context, scheme)
        if (!dl.success) return@withContext false
        val inst = XimeIndexSource.installFromMarket(context, scheme)
        if (!inst.success) return@withContext false
        markInstalled(context, release.version)
        true
    }
}
