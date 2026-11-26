package com.kingzcheung.xime.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.net.Uri
import com.kingzcheung.xime.BuildConfig
import com.kingzcheung.xime.plugin.SecureValueCipher
import com.kingzcheung.xime.speech.OnlineSpeechApi
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 本地备份与恢复：备份包只写到用户用系统文件选择器选定的位置，不经过插件或服务器。
 *
 * 备份包格式（v2，兼容 v1：`_xime_backup/` 前缀外的条目一律视为 rime 目录相对路径）：
 * - `_xime_backup/manifest.json`                    格式版本、应用版本、是否含个人数据
 * - `_xime_backup/settings.json`                    设置项（kime_settings，类型保留，剔除设备状态键）
 * - `_xime_backup/prefs/<name>.json`                其他设置文件（[SETTINGS_PREFS]）
 * - `_xime_backup/files/<path>`                     filesDir 下的设置/学习文件（[isFilesEntry]）
 * - `_xime_backup/cells/<name>`                     导入、下载的分类词库（[isCellEntry]），不论是否勾选个人数据
 * - `_xime_backup/clipboard.json`                   剪贴板文字记录（[ClipboardExport] 的 JSON，见 [clipboardBackupItems]），
 *   恢复时合并进剪贴板，已有的不重复；旧版恢复不认识这个条目，会跳过
 * - 包根的 rime 文件：`*.custom.yaml`、自定义布局方案、themes/ 背景图；
 *   勾选个人数据时另含 userdb（自造词与调频）、自定义短语与个人词库
 * - v1 的插件条目（plugin_configs/、plugins.xml、plugins/）仍可恢复，本地备份不再生成
 * - 以后的版本换了前缀和几个名字（[NewerBackupNames]）：读备份包的地方都先经过 [incomingEntry]，那样的备份包这一版也恢复得了
 *
 * 恢复走 [BackupRestoreTransaction]：先完整校验再落盘，失败回滚。
 */
object BackupManager {

    private const val TAG = "BackupManager"

    /** 备份包内元数据条目前缀（保留字，rime 目录不应有同名顶层文件）。 */
    const val META_PREFIX = "_xime_backup/"

    /** 备份包里的条目名：以后的版本用新前缀（[NewerBackupNames.META_PREFIX]）写的元数据条目当成 [META_PREFIX] 的，别的原样。读备份包的地方都先经过它。 */
    internal fun incomingEntry(name: String): String =
        if (name.startsWith(NewerBackupNames.META_PREFIX)) META_PREFIX + name.removePrefix(NewerBackupNames.META_PREFIX) else name

    const val FORMAT_VERSION = 2

    /** 设置项 SharedPreferences 文件名（SettingsPreferences.PREFS_NAME，此处避免依赖私有常量）。 */
    private const val PREFS_NAME_SETTINGS = "kime_settings"

    /** 除 kime_settings 外随备份保存的设置文件（字幕显示、手写公式模式、模型下载源）。 */
    internal val SETTINGS_PREFS = listOf("caption_mode", "handwriting", "model_download_source")

    /**
     * 只带其中几个键的设置文件：
     * - `clipboard_board` 里还存着固定条目（pins），备份只带卡片行数、图片高度、图片张数、平铺/列表和图片占用上限；
     * - `webdav_backup` 带地址、账号、自动备份开关和频率；**密码不带**（存的是 Keystore 密文，换设备／重装后旧密钥
     *   已不在，带过去也解不开），恢复后需重新填一次；
     * - `online_speech_api` 带地址、模型、App ID 和自定义模板；**API 密钥不带**（同上）；`status` 是本机测试结果，也不带。
     */
    internal val PARTIAL_PREFS = mapOf(
        "clipboard_board" to setOf("card_max_lines", "image_height", "recent_image_count", "list_layout", "image_cache_mb"),
        WebDavConfig.PREFS to setOf("base_url", "username", "auto_enabled", "frequency"),
        OnlineSpeechApi.PREFS to setOf("base_url", "model", "app_id", "body_template", "text_path"),
    )

    /** 只留 [keys] 里的键（[prefsToJson] 格式）。 */
    internal fun keepKeys(json: String, keys: Set<String>): String {
        val root = org.json.JSONObject(json)
        root.keys().asSequence().toList().filter { it !in keys }.forEach { root.remove(it) }
        return root.toString()
    }

    /** 描述本机部署/安装状态或指向未备份插件的键：导出不写，导入不覆盖（另含 `device_` 前缀的本机硬件判定）。 */
    internal val DEVICE_STATE_KEYS = setOf(
        "deployment_done", "builtin_schemas_merged", "deployment_hash", "rime_assets_version",
        "setup_completed", "installed_market_ids", "stt_online_plugin_id",
        "clipboard_sync_enabled", "clipboard_sync_plugin_id", "backup_plugin_id"
    )

    /** 硬上阴：再大就不让选。实际可用值看 [maxArchiveBytes]。 */
    private const val HARD_MAX_ARCHIVE_BYTES = 512L * 1024 * 1024

    /**
     * 备份包实际上阴：恢复要先把整包读进内存（事务层是 ByteArray），所以按当前堆动态算，
     * 取「硬上阴」和「1/6 堆」的较小值，下限 32 MB。低端机（heap 256 MB）不会因为
     * 一个 300 MB 的包直接 OOM。
     */
    internal fun maxArchiveBytes(): Long {
        val byHeap = Runtime.getRuntime().maxMemory() / 6
        return minOf(HARD_MAX_ARCHIVE_BYTES, maxOf(32L * 1024 * 1024, byHeap))
    }

    private val BACKUP_SUBDIRS = setOf("learning-profiles", "handwriting_templates", "handwriting_training")

    private val HANDWRITING_FILES = setOf(
        "handwriting_templates/latin.json", "handwriting_templates/kana_och.json", "handwriting_training/samples.jsonl")

    /** filesDir 下允许经 `_xime_backup/files/` 往返的文件。 */
    internal fun isFilesEntry(relative: String, personal: Boolean): Boolean = when {
        relative == "custom-keyboard-layouts.json" -> true
        relative == DoublePinyinSchemes.FILE -> true
        relative == "user_ngram_cache.json" -> personal
        relative.startsWith("learning-profiles/") && relative.endsWith(".json") &&
            relative.count { it == '/' } == 1 -> personal
        // 手写：边写边学的模板和训练页录的样本
        relative in HANDWRITING_FILES -> personal
        else -> false
    }

    /** 剪贴板记录在备份包里的条目。 */
    internal const val CLIPBOARD_ENTRY = META_PREFIX + "clipboard.json"

    /**
     * 备份带哪些剪贴板记录（用户 11-11 转述「备份不包括剪贴板」）：固定的和分组里的总是带；普通历史只在打开
     * 「包含剪贴板历史」时带。记下的密码（敏感）一律不带；图片不带（存在缓存目录，系统随时可能清）。
     */
    internal fun clipboardBackupItems(items: List<com.kingzcheung.xime.clipboard.ClipboardItem>, includeHistory: Boolean,
        inGroup: (com.kingzcheung.xime.clipboard.ClipboardItem) -> Boolean): List<com.kingzcheung.xime.clipboard.ClipboardItem> =
        com.kingzcheung.xime.clipboard.ClipboardExport.order(
            items.filter { !it.sensitive && (includeHistory || it.isPinned || inGroup(it)) }, includePinned = true)

    /** 本机要备份的剪贴板记录，写成 JSON；没有可带的或读不到时为 null（不影响其余备份）。 */
    internal fun clipboardBackupJson(context: Context, includeHistory: Boolean): String? = runCatching {
        val groups = com.kingzcheung.xime.clipboard.ClipboardGroupStore.getInstance(context).state.value
        val all = kotlinx.coroutines.runBlocking {
            com.kingzcheung.xime.clipboard.ClipboardManager.getInstance(context).exportItems()
        }
        val items = clipboardBackupItems(all, includeHistory) { groups.groupOf("text:${it.id}") != null }
        if (items.isEmpty()) return@runCatching null
        java.io.StringWriter().also { out ->
            com.kingzcheung.xime.clipboard.ClipboardExport.write(items,
                com.kingzcheung.xime.clipboard.ClipboardExportFormat.JSON, out, System.currentTimeMillis()) { item ->
                groups.groupOf("text:${item.id}")?.name
            }
        }.toString()
    }.onFailure { Log.w(TAG, "clipboard backup skipped", it) }.getOrNull()

    /** 备份包里的剪贴板记录；没有（旧备份）或太大时为 null。 */
    internal fun clipboardEntry(bytes: ByteArray): String? = ZipInputStream(bytes.inputStream()).use { input ->
        generateSequence { input.nextEntry }.firstOrNull { incomingEntry(it.name) == CLIPBOARD_ENTRY } ?: return null
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            out.write(chunk, 0, read)
            if (out.size() > com.kingzcheung.xime.clipboard.ClipboardImport.MAX_FILE_BYTES) return null
        }
        out.toString(Charsets.UTF_8.name())
    }

    /** 分类词库目录（[CellDictionaryManager.store]）。 */
    internal const val CELLS_DIR = "cell-dictionaries"

    /**
     * 导入、下载的分类词库：词库文件、英文词表、启用状态和词条更新。随 APK 的内置词库不带，装好会自己补上。
     * 放在 `_xime_backup/cells/` 下：旧版恢复不认识这个目录会跳过，新备份在旧版上其余内容照样能恢复。
     */
    internal fun isCellEntry(name: String): Boolean =
        !name.contains('/') && !name.contains('\\') && !name.startsWith("cell_builtin_") && (name == "state.json" ||
            (name.startsWith("cell_") && (name.endsWith(".dict.yaml") || name.endsWith(".en.txt"))) ||
            (name.startsWith("word-updates-") && name.endsWith(".json")))

    /** rime 目录中属于设置（总是导出）的文件。 */
    internal fun isRimeSettingsFile(relative: String): Boolean =
        (!relative.contains('/') && relative.endsWith(".custom.yaml")) ||
            (!relative.contains('/') && relative.startsWith("custom_pinyin_") && relative.endsWith(".schema.yaml")) ||
            relative.startsWith("themes/")

    /** rime 目录中属于个人词库与学习数据的文件。 */
    internal fun isRimePersonalFile(relative: String): Boolean {
        val first = relative.substringBefore('/')
        // leveldb 的锁和运行日志不是数据，恢复时覆盖反而有害
        if (first.endsWith(".userdb")) return relative.substringAfterLast('/') !in setOf("LOCK", "LOG", "LOG.old")
        // 日语 azooKey 的学习记录（词典本身随 APK，不备份）
        if (relative.startsWith("azookey/memory/")) return true
        if (relative.contains('/')) return false
        return relative.endsWith(".userdb.txt") ||
            (relative.startsWith("custom_phrase") && relative.endsWith(".txt")) ||
            relative == EnglishWordMemory.FILE_NAME || relative == EnglishWordMemory.DEMOTED_FILE_NAME ||
            // 补充词库生成的 user_cyime_cells_* 由补充词库设置重建，不算个人词库
            (relative.startsWith("user_") && !relative.startsWith("user_cyime_cells_") && relative.endsWith(".dict.yaml"))
    }

    /** 第三方导入的方案文件（schema/dict/lua/opencc 等；Rime 部署产物与 App 基础配置不算）。 */
    internal fun isImportedRimeFile(relative: String): Boolean {
        val name = relative.substringAfterLast('/')
        if (name in setOf("installation.yaml", "default.yaml", "xime.yaml", "default.custom.yaml")) return false
        if (name.startsWith("user_")) return false
        return relative.endsWith(".schema.yaml") || relative.endsWith(".dict.yaml") ||
            relative.endsWith(".yaml") || relative.endsWith(".lua") ||
            relative.startsWith("lua/") || relative.startsWith("opencc/")
    }

    /** 内置方案在 rime 目录里的文件路径集合（从 rime-bundled-manifest.tsv 读）。 */
    internal fun bundledRimeFiles(context: Context): Set<String> = runCatching {
        context.assets.open("rime-bundled-manifest.tsv").bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                line.split('\t').lastOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            }.toSet()
        }
    }.getOrDefault(emptySet())

    /** 列出本地备份要写入的文件：条目名 → 源文件（纯 JVM，便于单测）。 */
    internal fun localBackupFiles(filesDir: File, includePersonal: Boolean, bundled: Set<String> = emptySet()): List<Pair<String, File>> {
        val result = mutableListOf<Pair<String, File>>()
        val rimeDir = File(filesDir, "rime")
        rimeDir.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(rimeDir).path.replace('\\', '/')
            val backup = when {
                isRimeSettingsFile(relative) -> true
                isRimePersonalFile(relative) -> includePersonal
                relative in bundled -> false
                isImportedRimeFile(relative) -> true
                else -> false
            }
            if (backup) result += relative to file
        }
        filesDir.walkTopDown()
            .onEnter { it == filesDir || (it.parentFile == filesDir && it.name in BACKUP_SUBDIRS) }
            .filter { it.isFile }
            .forEach { file ->
                val relative = file.relativeTo(filesDir).path.replace('\\', '/')
                if (isFilesEntry(relative, includePersonal)) result += (META_PREFIX + "files/" + relative) to file
            }
        File(filesDir, CELLS_DIR).listFiles().orEmpty()
            .filter { it.isFile && isCellEntry(it.name) }
            .forEach { result += (META_PREFIX + "cells/" + it.name) to it }
        return result.sortedBy { it.first }
    }

    internal fun stripDeviceState(json: String): String {
        val root = org.json.JSONObject(json)
        root.keys().asSequence().toList()
            .filter { it in DEVICE_STATE_KEYS || it.startsWith("device_") }
            .forEach { root.remove(it) }
        return root.toString()
    }

    /** 写本地备份包到 [output]，返回写入的文件数（不含设置 JSON）。[clipboard]：剪贴板记录 JSON，null 不写。 */
    fun writeLocalBackup(context: Context, output: OutputStream, includePersonal: Boolean, clipboard: String? = null, includePluginConfig: Boolean = false): Int {
        val files = localBackupFiles(context.filesDir, includePersonal, bundledRimeFiles(context))
        ZipOutputStream(output).use { zos ->
            fun put(name: String, bytes: ByteArray) {
                zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry()
            }
            val manifest = org.json.JSONObject()
                .put("app", "CyIME")
                .put("format", FORMAT_VERSION)
                .put("versionName", BuildConfig.VERSION_NAME)
                .put("versionCode", BuildConfig.VERSION_CODE)
                .put("createdAt", System.currentTimeMillis())
                .put("personalData", includePersonal)
                .put("models", org.json.JSONArray(installedModelIds(context)))
                .put("plugins", org.json.JSONArray().also { arr ->
                    installedPluginSummaries(context).forEach { (id, name) ->
                        arr.put(org.json.JSONObject().put("id", id).put("name", name))
                    }
                })
            put(META_PREFIX + "manifest.json", manifest.toString(2).toByteArray(Charsets.UTF_8))
            val settings = prefsToJson(context.getSharedPreferences(PREFS_NAME_SETTINGS, Context.MODE_PRIVATE))
            put(META_PREFIX + "settings.json", stripDeviceState(settings).toByteArray(Charsets.UTF_8))
            SETTINGS_PREFS.forEach { name ->
                val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                if (prefs.all.isNotEmpty()) put(META_PREFIX + "prefs/$name.json", prefsToJson(prefs).toByteArray(Charsets.UTF_8))
            }
            PARTIAL_PREFS.forEach { (name, keys) ->
                val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                if (prefs.all.keys.any { it in keys })
                    put(META_PREFIX + "prefs/$name.json", keepKeys(prefsToJson(prefs), keys).toByteArray(Charsets.UTF_8))
            }
            clipboard?.let { put(CLIPBOARD_ENTRY, it.toByteArray(Charsets.UTF_8)) }
            if (includePluginConfig) {
                pluginConfigIds(context).forEach { id ->
                    pluginConfigJson(context, id)?.let { put(META_PREFIX + "plugin_configs/$id.json", it.toByteArray(Charsets.UTF_8)) }
                }
            }
            files.forEach { (name, file) ->
                zos.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        Log.i(TAG, "Local backup written: files=${files.size} personal=$includePersonal")
        return files.size
    }

    /** 导出到用户选择的文档 Uri。 */
    fun exportTo(context: Context, uri: Uri, includePersonal: Boolean, includeClipboardHistory: Boolean = false, includePluginConfig: Boolean = false): Result<Int> = runCatching {
        val clipboard = clipboardBackupJson(context, includeClipboardHistory)
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法写入所选位置")
        output.use { writeLocalBackup(context, it, includePersonal, clipboard, includePluginConfig) }
    }.onFailure { Log.e(TAG, "exportTo failed", it) }

    /** 生成备份包字节流到内存（用于 WebDAV 上传等）。 */
    fun backupToBytes(context: Context, includePersonal: Boolean, includeClipboardHistory: Boolean = false, includePluginConfig: Boolean = false): ByteArray {
        val clipboard = clipboardBackupJson(context, includeClipboardHistory)
        val out = java.io.ByteArrayOutputStream()
        writeLocalBackup(context, out, includePersonal, clipboard, includePluginConfig)
        return out.toByteArray()
    }

    /** 把备份包直接写到文件，不经过内存（WebDAV 上传用这个，避开大包 OOM）。 */
    fun backupToFile(context: Context, target: File, includePersonal: Boolean, includeClipboardHistory: Boolean = false, includePluginConfig: Boolean = false): Int {
        val clipboard = clipboardBackupJson(context, includeClipboardHistory)
        return target.outputStream().use { writeLocalBackup(context, it, includePersonal, clipboard, includePluginConfig) }
    }

    /** 读取本地备份文件（WebDAV 下载后的临时文件），上阴同 [readBackup]。 */
    fun readBackupFile(file: File): Result<ByteArray> = runCatching {
        require(file.isFile) { "备份文件不存在" }
        require(file.length() <= maxArchiveBytes()) { "备份文件过大（本机上限 ${maxArchiveBytes() / 1024 / 1024} MB）" }
        val bytes = file.readBytes()
        require(isCyimeBackup(bytes)) { "所选文件不是 CyIME 备份" }
        bytes
    }

    /** 恢复一个本地备份文件，并把「恢复后还缺什么」一并算好交给界面。 */
    suspend fun importBackupFileWithReport(context: Context, file: File): Result<RestoreReport> {
        val bytes = readBackupFile(file).getOrElse { return Result.failure(it) }
        return importBackupWithReport(context, bytes)
    }

    /** 读取用户选择的备份包；不是 CyIME 备份时拒绝，避免把任意 zip 解进 rime 目录。 */
    fun readBackup(context: Context, uri: Uri): Result<ByteArray> = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                total += read
                require(total <= maxArchiveBytes()) { "备份文件过大（本机上限 ${maxArchiveBytes() / 1024 / 1024} MB）" }
                buffer.write(chunk, 0, read)
            }
            buffer.toByteArray()
        } ?: error("无法读取所选文件")
        require(isCyimeBackup(bytes)) { "所选文件不是 CyIME 备份" }
        bytes
    }

    internal fun isCyimeBackup(bytes: ByteArray): Boolean = runCatching {
        ZipInputStream(bytes.inputStream()).use { input ->
            generateSequence { input.nextEntry }.any { incomingEntry(it.name) == META_PREFIX + "settings.json" }
        }
    }.getOrDefault(false)

    /** 恢复后重新部署 Rime 并刷新自定义布局缓存，使设置立即生效。 */
    suspend fun importBackup(context: Context, bytes: ByteArray): Result<Unit> {
        restoreArchive(context, bytes).onFailure { return Result.failure(it) }
        // 剪贴板记录合并进来，已有的不重复；这一步失败不算恢复失败。
        runCatching {
            clipboardEntry(bytes)?.let { json ->
                com.kingzcheung.xime.clipboard.ClipboardManager.getInstance(context)
                    .importItems(com.kingzcheung.xime.clipboard.ClipboardImport.parse(json))
            }
        }.onFailure { Log.w(TAG, "restore clipboard failed", it) }
        runCatching { CustomKeyboardLayouts.load(context) }
            .onFailure { Log.w(TAG, "reload custom layouts failed", it) }
        // 自定义双拼只备份键位表，方案文件按它重新生成。
        runCatching { DoublePinyinSchemes.install(context, SchemaManager.getRimeDir(context)) }
            .onFailure { Log.w(TAG, "regenerate double pinyin schemes failed", it) }
        val deployed = runCatching { com.kingzcheung.xime.rime.RimeConfigHelper.redeploy(context) }.getOrDefault(false)
        return if (deployed) Result.success(Unit)
        else Result.failure(Exception("设置已恢复，但输入方案重新部署失败，请在方案设置中重新部署"))
    }

    /** 本机已下载的模型 id（`filesDir/models/<id>/`）；只用于写进备份清单，不依赖任何模型索引。 */
    internal fun installedModelIds(context: Context): List<String> =
        File(context.filesDir, "models").listFiles().orEmpty()
            .filter { it.isDirectory }.map { it.name }.sorted()

    /** 已安装插件的（id，显示名）。插件框架未初始化时返回空，不让备份报错。 */
    internal fun installedPluginSummaries(context: Context): List<Pair<String, String>> = runCatching {
        if (!com.kingzcheung.xime.plugin.core.runtime.PluginManager.isInitialized) {
            // 插件框架没初始化（备份可能发生在启动早期）：退到读注册表文件，别让清单默默为空。
            return@runCatching pluginsXmlSummaries(context)
        }
        com.kingzcheung.xime.plugin.core.runtime.PluginManager.getAllInstallPlugins()
            .map { it.id to it.name }.sortedBy { it.first }
    }.getOrDefault(emptyList())

    /** 从 `filesDir/plugins.xml` 读已装插件的（id，名称）；只用于备份清单，不参与插件加载。 */
    internal fun pluginsXmlSummaries(context: Context): List<Pair<String, String>> = runCatching {
        val xml = File(context.filesDir, "plugins.xml")
        if (!xml.isFile) return@runCatching emptyList<Pair<String, String>>()
        val content = xml.readText()
        Regex("<plugin>(.*?)</plugin>", RegexOption.DOT_MATCHES_ALL).findAll(content).mapNotNull { m ->
            val body = m.groupValues[1]
            val id = Regex("<id>(.*?)</id>").find(body)?.groupValues?.get(1)?.trim()
            val name = Regex("<name>(.*?)</name>").find(body)?.groupValues?.get(1)?.trim()
            id?.takeIf { it.isNotBlank() }?.let { it to (name?.takeIf { n -> n.isNotBlank() } ?: it) }
        }.sortedBy { it.first }.toList()
    }.getOrDefault(emptyList())

    /** 备份包里的 `manifest.json`；旧备份没有这个清单或读不出来时 null。 */
    internal fun backupManifest(bytes: ByteArray): org.json.JSONObject? = runCatching {
        ZipInputStream(bytes.inputStream()).use { input ->
            var entry = input.nextEntry
            while (entry != null) {
                if (incomingEntry(entry.name) == META_PREFIX + "manifest.json") {
                    val out = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(chunk)
                        if (n <= 0) break
                        out.write(chunk, 0, n)
                    }
                    return@use org.json.JSONObject(out.toString("UTF-8"))
                }
                entry = input.nextEntry
            }
            null
        }
    }.getOrNull()

    /** 恢复后要补的东西：备份里有、本机没有的模型和插件，以及需要重新填一次的密钥。 */
    data class RestoreReport(
        val missingModels: List<String> = emptyList(),
        val missingPlugins: List<String> = emptyList(),
        val needsSecrets: List<String> = emptyList(),
    ) {
        val isEmpty: Boolean get() = missingModels.isEmpty() && missingPlugins.isEmpty() && needsSecrets.isEmpty()
    }

    /**
     * 拿备份包里的 manifest 和本机现状对比，算出「重装后还要补什么」。只读，不改任何数据。
     *
     * 模型目录（models/）和插件本体（plugins/）不在备份包里，所以这一份清单就是恢复后唯一能看到
     * “少了哪几个”的地方；密钥类（WebDAV 密码、在线语音 API 密钥）备份时有意剔除，也要在这里提醒重填。
     */
    fun restoreReport(context: Context, bytes: ByteArray): RestoreReport = runCatching {
        val manifest = backupManifest(bytes) ?: return RestoreReport()

        val modelRoot = File(context.filesDir, "models")
        val models = manifest.optJSONArray("models")
        val missingModels = buildList {
            for (i in 0 until (models?.length() ?: 0)) {
                val id = models?.optString(i).orEmpty()
                if (id.isNotBlank() && !File(modelRoot, id).isDirectory) add(id)
            }
        }

        val plugins = manifest.optJSONArray("plugins")
        val missingPlugins = buildList {
            val installed = installedPluginSummaries(context).map { it.first }.toSet()
            for (i in 0 until (plugins?.length() ?: 0)) {
                val obj = plugins?.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                if (id.isBlank() || id in installed) continue
                add(obj.optString("name").ifBlank { id })
            }
        }

        val needsSecrets = buildList {
            val webdav = context.getSharedPreferences(WebDavConfig.PREFS, Context.MODE_PRIVATE)
            if (webdav.getString("base_url", "").orEmpty().isNotBlank() &&
                webdav.getString("password", null).isNullOrBlank()
            ) add("WebDAV 密码")
            val asr = context.getSharedPreferences(OnlineSpeechApi.PREFS, Context.MODE_PRIVATE)
            val asrConfigured = asr.getString("base_url", "").orEmpty().isNotBlank() ||
                asr.getString("model", "").orEmpty().isNotBlank()
            if (asrConfigured && asr.getString("api_key", "").orEmpty().isBlank()) add("在线语音 API 密钥")
        }

        RestoreReport(missingModels, missingPlugins, needsSecrets)
    }.getOrDefault(RestoreReport())

    /** 同 [importBackup]，额外把「恢复后还缺什么」一并算好交给界面。 */
    suspend fun importBackupWithReport(context: Context, bytes: ByteArray): Result<RestoreReport> =
        importBackup(context, bytes).map { restoreReport(context, bytes) }

    /** 校验 zip 条目路径并解压覆盖（rime 文件 + META 元数据路由），Android 入口。 */
    fun restoreArchive(context: Context, bytes: ByteArray): Result<Unit> {
        val filesDir = context.filesDir
        val store = object : BackupPreferenceStore {
            override fun validate(json: ByteArray) { parsePrefs(json) }
            override fun snapshot(name: String) = prefsToJson(context.getSharedPreferences(name, Context.MODE_PRIVATE)).toByteArray()
            override fun restore(name: String, json: ByteArray, replace: Boolean) {
                // 回滚快照（replace）原样写回；备份内容不覆盖本机部署状态
                val data = when {
                    replace -> json
                    name.startsWith("plugin_cfg_") -> encryptPluginConfig(json)
                    name == PREFS_NAME_SETTINGS -> stripDeviceState(json.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
                    // 只认允许的键，备份包写不进固定条目
                    name in PARTIAL_PREFS -> keepKeys(json.toString(Charsets.UTF_8), PARTIAL_PREFS.getValue(name)).toByteArray(Charsets.UTF_8)
                    else -> json
                }
                restorePrefsJson(context.getSharedPreferences(name, Context.MODE_PRIVATE), data, replace)
            }
        }
        return com.kingzcheung.xime.rime.RimeEngine.getInstance().restoreUserData {
            BackupRestoreTransaction.restore(filesDir, store, bytes)
        }.onSuccess {
            Log.i(TAG, "Restore done -> ${filesDir.path}")
        }.onFailure { e ->
            Log.e(TAG, "restoreArchive failed", e)
        }
    }

    // ---- prefs 备份/恢复（JSON，类型保留：b/i/l/f/s/ss） ----

    /** 序列化 prefs 全部键值（插件配置值为密文字符串，原样存取，不在备份链路解密）。 */
    fun prefsToJson(prefs: SharedPreferences): String {
        val root = org.json.JSONObject()
        for ((key, value) in prefs.all) {
            val entry = org.json.JSONObject()
            when (value) {
                is Boolean -> entry.put("t", "b").put("v", value)
                is Int -> entry.put("t", "i").put("v", value)
                is Long -> entry.put("t", "l").put("v", value)
                is Float -> entry.put("t", "f").put("v", value.toDouble())
                is String -> entry.put("t", "s").put("v", value)
                is Set<*> -> entry.put("t", "ss").put("v", org.json.JSONArray(value))
                else -> continue
            }
            root.put(key, entry)
        }
        return root.toString()
    }

    /** 按 [prefsToJson] 的类型标注恢复 prefs（合并写，不删除备份中不存在的新增键）。 */
    internal fun parsePrefs(json: ByteArray): Map<String, Any> {
        val root = org.json.JSONObject(json.toString(Charsets.UTF_8))
        return root.keys().asSequence().associateWith { key ->
            val entry = root.getJSONObject(key)
            when (entry.getString("t")) {
                "b" -> entry.getBoolean("v")
                "i" -> entry.getInt("v")
                "l" -> entry.getLong("v")
                "f" -> entry.getDouble("v").toFloat()
                "s" -> entry.getString("v")
                "ss" -> entry.getJSONArray("v").let { arr ->
                    (0 until arr.length()).mapTo(mutableSetOf()) { arr.getString(it) }
                }
                else -> error("不支持的设置类型: $key")
            }
        }
    }

    fun restorePrefsJson(prefs: SharedPreferences, json: ByteArray, replace: Boolean = false) {
        val values = parsePrefs(json)
        val editor = prefs.edit()
        if (replace) editor.clear()
        values.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        check(editor.commit()) { "写入设置失败" }
    }

    /** 文件管理导出的设置条目；插件配置可能含密码，插件及其注册表一律不打包。 */
    internal fun collectMetaEntries(context: Context): List<Pair<String, ByteArray>> {
        val entries = mutableListOf<Pair<String, ByteArray>>()
        val settingsPrefs = context.getSharedPreferences(PREFS_NAME_SETTINGS, Context.MODE_PRIVATE)
        entries += (META_PREFIX + "settings.json") to
            stripDeviceState(prefsToJson(settingsPrefs)).toByteArray(Charsets.UTF_8)
        return entries
    }

    /** 列出所有插件配置 id（shared_prefs 下 plugin_cfg_<id>.xml）。 */
    internal fun pluginConfigIds(context: Context): List<String> {
        val dir = File(context.dataDir, "shared_prefs")
        return dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("plugin_cfg_") && it.name.endsWith(".xml") }
            .map { it.name.removePrefix("plugin_cfg_").removeSuffix(".xml") }
            .sorted()
    }

    /** 解密后的插件配置（明文 JSON，prefsToJson 格式）；空或读不到时 null。 */
    internal fun pluginConfigJson(context: Context, pluginId: String): String? {
        val prefs = context.getSharedPreferences("plugin_cfg_$pluginId", Context.MODE_PRIVATE)
        if (prefs.all.isEmpty()) return null
        val root = org.json.JSONObject()
        for ((key, value) in prefs.all) {
            if (value !is String) continue
            val plain = SecureValueCipher.decrypt(value) ?: continue
            root.put(key, org.json.JSONObject().put("t", "s").put("v", plain))
        }
        return if (root.length() == 0) null else root.toString()
    }

    /** 恢复时把明文插件配置重新加密成密文（prefsToJson 格式）。 */
    internal fun encryptPluginConfig(json: ByteArray): ByteArray {
        val root = org.json.JSONObject(json.toString(Charsets.UTF_8))
        val out = org.json.JSONObject()
        for (key in root.keys().asSequence().toList()) {
            val entry = root.optJSONObject(key) ?: continue
            if (entry.optString("t") != "s") continue
            out.put(key, org.json.JSONObject().put("t", "s").put("v", SecureValueCipher.encrypt(entry.getString("v"))))
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }
}
