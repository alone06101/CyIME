package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.CloudDownload
import androidx.compose.material.icons.twotone.CloudUpload
import androidx.compose.material.icons.twotone.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.BackupManager
import com.kingzcheung.xime.settings.WebDavBackup
import com.kingzcheung.xime.settings.WebDavConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** WebDAV 云端备份设置：填服务器，手动或定时把备份上传到云端。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavBackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var baseUrl by remember { mutableStateOf(WebDavConfig.baseUrl(context)) }
    var username by remember { mutableStateOf(WebDavConfig.username(context)) }
    var password by remember { mutableStateOf(WebDavConfig.password(context)) }
    var autoEnabled by remember { mutableStateOf(WebDavConfig.autoBackupEnabled(context)) }
    var frequency by remember { mutableStateOf(WebDavConfig.frequency(context)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var cloudFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var listed by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<String?>(null) }
    /** 恢复后「还需要补什么」：模型、插件、要重填的密钥。 */
    var restoreReport by remember { mutableStateOf<BackupManager.RestoreReport?>(null) }

    fun persist() = WebDavConfig.save(context, baseUrl, username, password)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("WebDAV 备份") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).settingsVerticalScroll().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsSection(title = "服务器") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("服务器地址") },
                        placeholder = { Text("https://dav.example.com/remote.php/dav/files/xxx") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("账号") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            SettingsSection(title = "云端备份") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            persist()
                            busy = true
                            message = null
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    WebDavBackup.backupAndUpload(
                                        context,
                                        includePersonal = BackupChoices.includePersonal(context),
                                        includeClipboardHistory = BackupChoices.includeClipboardHistory(context),
                                        includePluginConfig = BackupChoices.includePluginConfig(context),
                                    )
                                }
                                busy = false
                                message = result.fold(
                                    onSuccess = { "已上传到 WebDAV（备份内容按「备份与恢复」页的选择）" },
                                    onFailure = { "上传失败：${it.message ?: "未知错误"}" }
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy
                    ) {
                        if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.TwoTone.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("备份并上传到云端", modifier = Modifier.padding(start = 6.dp))
                    }
                    Text(
                        text = "备份包存到服务器的 CyIME-backup/ 目录，文件名 CyIME-日期.zip。密码只存在本机（硬件加密），不会明文保存。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = "备份内容与「备份与恢复」页的选择一致；若勾选「包含插件配置」，云端备份包里会含 AI 密钥等明文，请确保云端只有你能访问。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    message?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            SettingsSection(title = "自动备份") {
                SettingsToggleItem(
                    icon = Icons.TwoTone.Schedule,
                    title = "自动备份到云端",
                    subtitle = "按下面选的频率自动备份并上传",
                    checked = autoEnabled,
                    onCheckedChange = { enabled ->
                        autoEnabled = enabled
                        WebDavConfig.setAutoBackup(context, enabled)
                        WebDavBackup.scheduleAutoBackup(context, enabled, frequency)
                    }
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("频率", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    WebDavConfig.Frequency.entries.forEach { f ->
                        Row(
                            modifier = Modifier.padding(start = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = frequency == f,
                                onClick = {
                                    frequency = f
                                    WebDavConfig.setFrequency(context, f)
                                    if (autoEnabled) WebDavBackup.scheduleAutoBackup(context, true, f)
                                },
                                enabled = !busy
                            )
                            Text(f.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            SettingsSection(title = "从云端恢复") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            persist()
                            busy = true
                            message = null
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { WebDavBackup.listBackups(context) }
                                busy = false
                                result.fold(
                                    onSuccess = { files ->
                                        cloudFiles = files
                                        listed = true
                                        if (files.isEmpty()) message = "云端还没有备份文件"
                                    },
                                    onFailure = { message = "获取云端列表失败：${it.message ?: "未知错误"}" }
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy
                    ) { Text("列出云端备份") }
                    if (cloudFiles.isEmpty() && listed) {
                        Text("云端还没有备份文件。", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline)
                    }
                    cloudFiles.forEach { fileName ->
                        SettingsItem(
                            icon = Icons.TwoTone.CloudDownload,
                            title = fileName,
                            subtitle = "下载并恢复（覆盖当前设置和词库）",
                            onClick = { pendingRestore = fileName }
                        )
                    }
                }
            }
        }

        pendingRestore?.let { fileName ->
            AlertDialog(
                onDismissRequest = { pendingRestore = null },
                title = { Text("从云端恢复") },
                text = { Text("下载 $fileName 并恢复？会覆盖当前设置和词库。") },
                confirmButton = {
                    TextButton(onClick = {
                        pendingRestore = null
                        busy = true
                        message = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                WebDavBackup.download(context, fileName).fold(
                                    onSuccess = { bytes -> BackupManager.importBackupWithReport(context, bytes) },
                                    onFailure = { Result.failure(it) }
                                )
                            }
                            busy = false
                            message = result.fold(
                                onSuccess = { "恢复完成，设置已生效" },
                                onFailure = { "恢复失败：${it.message ?: "未知错误"}" }
                            )
                            result.getOrNull()?.takeUnless { it.isEmpty }?.let { restoreReport = it }
                        }
                    }) { Text("下载并恢复") }
                },
                dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("取消") } }
            )
        }

        restoreReport?.let { report ->
            AlertDialog(
                onDismissRequest = { restoreReport = null },
                title = { Text("恢复完成，还需补这些") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (report.missingModels.isNotEmpty()) {
                            Text("要重新下载的模型：" + report.missingModels.joinToString("、"))
                        }
                        if (report.missingPlugins.isNotEmpty()) {
                            Text("要重新安装的插件：" + report.missingPlugins.joinToString("、"))
                        }
                        if (report.needsSecrets.isNotEmpty()) {
                            Text("要重新填一次的密码：" + report.needsSecrets.joinToString("、"))
                        }
                        Text("模型和插件本体不在备份包里，其余设置、词库、方案已恢复。")
                    }
                },
                confirmButton = { TextButton(onClick = { restoreReport = null }) { Text("知道了") } }
            )
        }
    }
}
