// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.sync.SyncCode
import helium314.keyboard.latin.sync.SyncRepository
import helium314.keyboard.latin.sync.SyncState
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.TextInputDialog
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.SwitchPreference
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SyncSettingsScreen(
    onClickBack: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { SyncRepository(context) }
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf(repository.getCurrentState()) }
    var isLoading by remember { mutableStateOf(false) }
    var loadingMessage by remember { mutableStateOf("") }

    // Dialog state
    var showClientIdDialog by rememberSaveable { mutableStateOf(false) }
    var showEndpointDialog by rememberSaveable { mutableStateOf(false) }
    var showEnterCodeDialog by rememberSaveable { mutableStateOf(false) }
    var showResetCodeConfirm by rememberSaveable { mutableStateOf(false) }
    var showDeleteCloudConfirm by rememberSaveable { mutableStateOf(false) }
    var showOverwriteWarning by rememberSaveable { mutableStateOf(false) }

    fun refreshState() {
        state = repository.getCurrentState()
    }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.sync_settings_title),
        settings = emptyList(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            // Configuration Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.sync_setup_category),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.sync_setup_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = { showClientIdDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (repository.getServerClientId().isBlank())
                                stringResource(R.string.sync_set_client_id)
                            else
                                "Google Client ID: ...${repository.getServerClientId().takeLast(16)}"
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = { showEndpointDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (repository.getWorkerEndpoint().isBlank())
                                stringResource(R.string.sync_set_worker_url)
                            else
                                "Worker: ${repository.getWorkerEndpoint()}"
                        )
                    }
                }
            }

            // Sync Clipboard Switch
            var clipboardSync by remember { mutableStateOf(repository.isClipboardSyncEnabled()) }
            ListItem(
                headlineContent = { Text(stringResource(R.string.sync_clipboard_title)) },
                supportingContent = { Text(stringResource(R.string.sync_clipboard_desc)) },
                trailingContent = {
                    Switch(
                        checked = clipboardSync,
                        onCheckedChange = {
                            clipboardSync = it
                            repository.setClipboardSyncEnabled(it)
                        }
                    )
                }
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // State specific section
            when (val s = state) {
                is SyncState.SignedOut -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.sync_signed_out_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                val clientId = repository.getServerClientId()
                                if (clientId.isBlank()) {
                                    showClientIdDialog = true
                                    return@Button
                                }
                                isLoading = true
                                loadingMessage = "Signing in with Google..."
                                scope.launch {
                                    val res = repository.signIn(clientId)
                                    isLoading = false
                                    res.onSuccess {
                                        refreshState()
                                    }.onFailure {
                                        Toast.makeText(context, "Sign in failed: ${it.localizedMessage}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(0.85f)
                        ) {
                            Text(stringResource(R.string.sync_sign_in_google))
                        }
                    }
                }

                is SyncState.NeedCode -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        Text(
                            text = "Signed in as: ${s.email}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.sync_need_code_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = {
                                    repository.generateAndSaveCode()
                                    refreshState()
                                    Toast.makeText(context, "New sync code generated!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.sync_generate_code))
                            }

                            OutlinedButton(
                                onClick = { showEnterCodeDialog = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.sync_enter_code))
                            }
                        }
                    }
                }

                is SyncState.Ready -> {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Account: ${s.email}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = stringResource(R.string.sync_code_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = SyncCode.formatForDisplay(s.code),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 2.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                TextButton(
                                    onClick = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText("SleekType Sync Code", SyncCode.formatForDisplay(s.code)))
                                        Toast.makeText(context, "Code copied to clipboard", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Text(stringResource(R.string.sync_copy_code))
                                }
                            }

                            if (s.lastSyncedAt > 0) {
                                val dateStr = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(s.lastSyncedAt))
                                Text(
                                    text = "Last synced: $dateStr",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Action buttons: Sync To & Sync From
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                isLoading = true
                                loadingMessage = "Uploading encrypted backup..."
                                scope.launch {
                                    val res = repository.syncTo()
                                    isLoading = false
                                    res.onSuccess {
                                        refreshState()
                                        Toast.makeText(context, "Successfully backed up to cloud!", Toast.LENGTH_SHORT).show()
                                    }.onFailure {
                                        Toast.makeText(context, "Upload failed: ${it.localizedMessage}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.sync_to_cloud))
                        }

                        Button(
                            onClick = {
                                isLoading = true
                                loadingMessage = "Downloading and restoring..."
                                scope.launch {
                                    val res = repository.syncFrom()
                                    isLoading = false
                                    res.onSuccess {
                                        refreshState()
                                        Toast.makeText(context, "Restored ${it.clipboard.size} clips & settings!", Toast.LENGTH_SHORT).show()
                                    }.onFailure {
                                        Toast.makeText(context, "Restore failed: ${it.localizedMessage}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.sync_from_cloud))
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Preference(
                        name = stringResource(R.string.sync_reset_code),
                        onClick = { showResetCodeConfirm = true }
                    )
                    Preference(
                        name = stringResource(R.string.sync_delete_cloud_data),
                        onClick = { showDeleteCloudConfirm = true }
                    )
                    Preference(
                        name = stringResource(R.string.sync_sign_out),
                        onClick = {
                            scope.launch {
                                repository.signOut()
                                refreshState()
                            }
                        }
                    )
                }

                else -> {}
            }

            if (isLoading) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(loadingMessage, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    // Dialogs
    if (showClientIdDialog) {
        TextInputDialog(
            onDismissRequest = { showClientIdDialog = false },
            title = { Text(stringResource(R.string.sync_set_client_id)) },
            initialText = repository.getServerClientId(),
            onConfirmed = {
                repository.setServerClientId(it)
                showClientIdDialog = false
            }
        )
    }

    if (showEndpointDialog) {
        TextInputDialog(
            onDismissRequest = { showEndpointDialog = false },
            title = { Text(stringResource(R.string.sync_set_worker_url)) },
            initialText = repository.getWorkerEndpoint(),
            onConfirmed = {
                repository.setWorkerEndpoint(it)
                showEndpointDialog = false
            }
        )
    }

    if (showEnterCodeDialog) {
        TextInputDialog(
            onDismissRequest = { showEnterCodeDialog = false },
            title = { Text(stringResource(R.string.sync_enter_code)) },
            initialText = "",
            onConfirmed = {
                if (it.isNotBlank()) {
                    repository.savePairingCode(it)
                    refreshState()
                }
                showEnterCodeDialog = false
            }
        )
    }

    if (showResetCodeConfirm) {
        ConfirmationDialog(
            onDismissRequest = { showResetCodeConfirm = false },
            title = { Text(stringResource(R.string.sync_reset_code)) },
            content = { Text(stringResource(R.string.sync_reset_code_warning)) },
            confirmButtonText = stringResource(R.string.sync_reset),
            onConfirmed = {
                repository.generateAndSaveCode()
                refreshState()
                showResetCodeConfirm = false
                Toast.makeText(context, "New code generated. Re-sync to cloud now.", Toast.LENGTH_LONG).show()
            }
        )
    }

    if (showDeleteCloudConfirm) {
        ConfirmationDialog(
            onDismissRequest = { showDeleteCloudConfirm = false },
            title = { Text(stringResource(R.string.sync_delete_cloud_data)) },
            content = { Text(stringResource(R.string.sync_delete_cloud_warning)) },
            confirmButtonText = stringResource(R.string.delete),
            onConfirmed = {
                showDeleteCloudConfirm = false
                isLoading = true
                loadingMessage = "Deleting cloud data..."
                scope.launch {
                    val res = repository.deleteCloudBackup()
                    isLoading = false
                    res.onSuccess {
                        Toast.makeText(context, "Cloud backup deleted", Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(context, "Failed: ${it.localizedMessage}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }
}
