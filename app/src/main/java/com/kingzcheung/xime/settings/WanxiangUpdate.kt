package com.kingzcheung.xime.settings

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
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

    data class Release(val version: String, val zipUrl: String, val sha256: String?, val sizeBytes: Long, val hasDigest: Boolean = false)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** 检查结果的短期缓存：GitHub 未认证 API 限 60 次/小时，用户连点会直接 429。 */
    private const val CACHE_TTL_MS = 10 * 60 * 1000L
    @Volatile private var cachedAt = 0L
    @Volatile private var cached: Release? = null
    @Volatile private var cachedEtag: String? = null

    /** 从 GitHub API 获取最新 release 的 base 包信息；失败返回 null。 */
    fun fetchLatestRelease(force: Boolean = false): Release? = try {
        if (!force) {
            val hit = cached
            if (hit != null && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) return hit
        }
        val request = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "CyIME")
            .apply { cachedEtag?.let { header("If-None-Match", it) } }
            .build()
        client.newCall(request).execute().use { resp ->
            // 304：内容没变，用缓存那一个（即使过了 TTL）
            if (resp.code == 304) return cached
            if (!resp.isSuccessful) return null
            parseRelease(resp.body?.string() ?: return null)?.also {
                cached = it
                cachedAt = System.currentTimeMillis()
                cachedEtag = resp.header("ETag")
            }
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
            val sha = digest.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }
            return Release(
                version = version,
                zipUrl = url,
                sha256 = sha,
                sizeBytes = asset.optLong("size", 0L),
                hasDigest = sha != null,
            )
        }
        return null
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 本机已装的万象拼音版本：优先本功能的记录，其次是市场安装/导入时记下的版本（用户从商店装的也能识别）。 */
    fun installedVersion(context: Context): String? =
        prefs(context).getString(KEY_VERSION, null) ?: MarketVersionStore.getSchemeVersion(context, SCHEME_ID)

    private fun markInstalled(context: Context, version: String) {
        prefs(context).edit().putString(KEY_VERSION, version).apply()
    }

    /**
     * [latest] 是否比 [installed] 新。按 `.`、`-`、`_` 分段：都是数字比数值，其余比字串；
     * 已装版本更长（如 5.4.9-hotfix）算更新，避免误报“有更新”把用户的兼容版降级。
     */
    internal fun isNewer(latest: String, installed: String?): Boolean {
        if (installed.isNullOrBlank()) return true
        if (installed == latest) return false
        val a = latest.split('.', '-', '_')
        val b = installed.split('.', '-', '_')
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrNull(i) ?: return false
            val y = b.getOrNull(i) ?: return true
            val nx = x.toIntOrNull()
            val ny = y.toIntOrNull()
            if (nx != null && ny != null) {
                if (nx != ny) return nx > ny
            } else if (x != y) {
                return x > y
            }
        }
        return false
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

    /** 检查更新：返回最新 release；检查失败返回 null。[force] 跳过 10 分钟缓存。 */
    suspend fun checkUpdate(context: Context, force: Boolean = false): Release? = withContext(Dispatchers.IO) {
        fetchLatestRelease(force)
    }

    /** 下载并安装万象拼音最新版；成功返回 true。失败时会把被覆盖的旧文件还原（有回滚）。 */
    suspend fun update(
        context: Context,
        release: Release,
        onDownloadProgress: (Long, Long) -> Unit = { _, _ -> },
        onInstalling: () -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        val scheme = marketScheme(release)
        val dl = XimeIndexSource.downloadScheme(context, scheme, onDownloadProgress = onDownloadProgress)
        if (!dl.success) return@withContext false
        onInstalling()
        // 安装会覆盖同名文件（万象旧版可能是用户自己导入的）。先把包围到的旧文件抄一份，失败就还原。
        val snapshot = snapshotTargets(context, scheme.id)
        try {
            val inst = SchemaManager.installPackageFromMarketDir(
                context = context,
                packageId = scheme.id,
                displayName = scheme.name,
                version = scheme.currentVersion,
                fromMarket = true,
                dependencies = scheme.dependencies,
                switchEnabled = true,
                replaceConflictingFiles = true,
            )
            if (!inst.success) {
                rollback(context, snapshot)
                return@withContext false
            }
            markInstalled(context, release.version)
            snapshot.dir.deleteRecursively()
            true
        } catch (e: Exception) {
            Log.w(TAG, "update failed, rolling back", e)
            rollback(context, snapshot)
            false
        }
    }

    /** 要覆盖的 rime 文件快照（相对路径 -> 备份文件 + 是否原本就存在）。 */
    private class TargetSnapshot(val dir: File, val saved: Map<String, File>, val targets: List<String>)

    private suspend fun snapshotTargets(context: Context, packageId: String): TargetSnapshot {
        val dir = File(context.cacheDir, "wanxiang-rollback-${System.currentTimeMillis()}")
        val rimeDir = SchemaManager.getRimeDir(context)
        val targets = runCatching { SchemaManager.listInstallTargetFiles(context, packageId) }
            .getOrDefault(emptyList())
        val saved = mutableMapOf<String, File>()
        targets.forEach { rel ->
            val src = File(rimeDir, rel)
            if (!src.isFile) return@forEach
            val dst = File(dir, rel)
            runCatching {
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
                saved[rel] = dst
            }
        }
        return TargetSnapshot(dir, saved, targets)
    }

    /** 安装失败时还原：旧文件写回，新出现的文件删掉。 */
    private fun rollback(context: Context, snapshot: TargetSnapshot) {
        val rimeDir = SchemaManager.getRimeDir(context)
        snapshot.targets.forEach { rel ->
            val live = File(rimeDir, rel)
            val saved = snapshot.saved[rel]
            if (saved != null) {
                runCatching { saved.copyTo(live, overwrite = true) }
            } else {
                runCatching { if (live.isFile) live.delete() }
            }
        }
        snapshot.dir.deleteRecursively()
        runCatching { com.kingzcheung.xime.rime.RimeConfigHelper.redeploy(context) }
    }
}
