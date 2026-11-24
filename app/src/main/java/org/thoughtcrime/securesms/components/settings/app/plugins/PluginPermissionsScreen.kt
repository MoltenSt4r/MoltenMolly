package org.thoughtcrime.securesms.components.settings.app.plugins

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import im.molly.plugins.PluginPermissions
import org.signal.appsettings.AppSettingsLazyColumn
import org.signal.appsettings.routes.AppSettingsNavigationEvent
import org.signal.core.ui.compose.Dividers
import org.signal.core.ui.compose.Rows
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.horizontalGutters

@Composable
fun PluginPermissionsEntry(
  pluginId: String,
  parentEventEmitter: (AppSettingsNavigationEvent) -> Unit
) {
  val context = LocalContext.current.applicationContext
  val viewModel: PluginPermissionsViewModel = viewModel(key = "plugin_perms_$pluginId") {
    PluginPermissionsViewModel(context, pluginId, parentEventEmitter)
  }
  val state by viewModel.state.collectAsStateWithLifecycle()

  LifecycleResumeEffect(pluginId) {
    viewModel.onEvent(PluginPermissionsEvent.Refresh)
    onPauseOrDispose {}
  }

  PluginPermissionsScreen(
    state = state,
    onEvent = viewModel::onEvent
  )
}

@Composable
fun PluginPermissionsScreen(
  state: PluginPermissionsState,
  onEvent: (PluginPermissionsEvent) -> Unit
) {
  val plugin = state.plugin

  Scaffolds.Settings(
    title = plugin?.name ?: "Plugin permissions",
    navigationIcon = SignalIcons.ArrowStart.imageVector,
    onNavigationClick = { onEvent(PluginPermissionsEvent.NavigateBack) },
    collapseTopBarInSplitPane = true
  ) { contentPadding ->
    if (plugin == null) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(contentPadding)
          .horizontalGutters()
          .padding(top = 24.dp)
      ) {
        Text(
          text = "Plugin not found.",
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
      return@Settings
    }

    AppSettingsLazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(contentPadding)
    ) {
      item {
        Card(
          colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
          ),
          shape = RoundedCornerShape(16.dp),
          modifier = Modifier
            .horizontalGutters()
            .padding(top = 8.dp, bottom = 12.dp)
            .fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(16.dp)) {
            Text(
              text = "v${plugin.version} • by ${plugin.author}",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.primary
            )
            if (plugin.description.isNotBlank()) {
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = plugin.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }
        }
      }

      if (plugin.isObfuscated || state.scanResult.isObfuscated) {
        item {
          Card(
            colors = CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.errorContainer,
              contentColor = MaterialTheme.colorScheme.onErrorContainer
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
              .horizontalGutters()
              .padding(bottom = 12.dp)
              .fillMaxWidth()
          ) {
            Column(modifier = Modifier.padding(12.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                  imageVector = SignalIcons.ErrorCircle.imageVector,
                  contentDescription = null,
                  modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = "Obfuscation detected during install scan",
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.SemiBold
                )
              }
              state.scanResult.obfuscationEvidence.take(4).forEach { evidence ->
                Text(
                  text = "• $evidence",
                  style = MaterialTheme.typography.bodySmall,
                  modifier = Modifier.padding(top = 2.dp)
                )
              }
            }
          }
        }
      }

      item {
        Rows.ToggleRow(
          checked = plugin.enabled,
          text = "Enable plugin",
          label = "Run in isolated :plugin_sandbox process",
          onCheckChanged = { onEvent(PluginPermissionsEvent.ToggleEnabled(it)) }
        )
      }

      item {
        Dividers.Default()
      }

      item {
        Text(
          text = "Granular permissions",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier
            .horizontalGutters()
            .padding(top = 16.dp, bottom = 4.dp)
        )
      }

      item {
        Rows.ToggleRow(
          checked = true,
          text = PluginPermissions.describe(PluginPermissions.UI),
          label = "Always granted (cannot be revoked)",
          enabled = false,
          onCheckChanged = {}
        )
      }

      for (perm in PluginPermissions.REQUESTABLE) {
        item(key = perm) {
          val granted = perm in state.grantedPermissions
          val dangerous = PluginPermissions.isDangerous(perm)
          val counts = state.auditProfile[perm]
          val evidence = state.scanResult.capabilities[perm].orEmpty()

          PermissionToggleItem(
            permission = perm,
            title = PluginPermissions.describe(perm),
            granted = granted,
            dangerous = dangerous,
            allowedCount = counts?.getOrNull(0) ?: 0,
            deniedCount = counts?.getOrNull(1) ?: 0,
            evidence = evidence,
            onToggle = { onEvent(PluginPermissionsEvent.TogglePermission(perm, it)) }
          )
        }
      }

      item {
        Dividers.Default()
      }

      item {
        Rows.TextRow(
          text = "View plugin audit log",
          label = "Inspect allowed and denied calls for ${plugin.name}",
          icon = SignalIcons.Recent.painter,
          onClick = { onEvent(PluginPermissionsEvent.OpenAuditLog) }
        )
      }

      item {
        Rows.TextRow(
          icon = SignalIcons.Trash.imageVector,
          text = "Uninstall plugin",
          foregroundTint = MaterialTheme.colorScheme.error,
          iconTint = MaterialTheme.colorScheme.error,
          onClick = { onEvent(PluginPermissionsEvent.RequestUninstall) }
        )
      }
    }
  }

  if (state.showUninstallDialog && plugin != null) {
    AlertDialog(
      onDismissRequest = { onEvent(PluginPermissionsEvent.DismissUninstallDialog) },
      title = { Text("Uninstall ${plugin.name}?") },
      text = { Text("This removes the plugin source, sandboxed files, and all granted permissions.") },
      confirmButton = {
        TextButton(onClick = { onEvent(PluginPermissionsEvent.ConfirmUninstall) }) {
          Text("Uninstall", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = {
        TextButton(onClick = { onEvent(PluginPermissionsEvent.DismissUninstallDialog) }) {
          Text("Cancel")
        }
      }
    )
  }
}

@Composable
private fun PermissionToggleItem(
  permission: String,
  title: String,
  granted: Boolean,
  dangerous: Boolean,
  allowedCount: Int,
  deniedCount: Int,
  evidence: List<String>,
  onToggle: (Boolean) -> Unit
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .clickable { onToggle(!granted) }
      .horizontalGutters()
      .padding(vertical = 10.dp)
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Text(
          text = title,
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurface
        )
        if (dangerous) {
          Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = RoundedCornerShape(6.dp)
          ) {
            Text(
              text = "Sensitive",
              style = MaterialTheme.typography.labelSmall,
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
          }
        }
      }

      Text(
        text = "$permission • Allowed: $allowedCount, Denied: $deniedCount",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp)
      )

      evidence.take(2).forEach { line ->
        Text(
          text = "• $line",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }

    Spacer(modifier = Modifier.width(12.dp))

    Switch(
      checked = granted,
      onCheckedChange = onToggle
    )
  }
}
