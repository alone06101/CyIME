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
import androidx.compose.material.icons.twotone.CloudUpload
import androidx.compose.material.icons.twotone.Schedule
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
                        text = "备份包会存到服务器的 CyIME-backup/ 目录，文件名为 CyIME-日期.zip。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
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
        }
    }
}
