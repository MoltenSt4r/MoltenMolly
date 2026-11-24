package org.thoughtcrime.securesms.components.settings.app.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import im.molly.plugins.PluginInfo
import im.molly.plugins.SandboxStatus
import org.signal.appsettings.AppSettingsLazyColumn
import org.signal.appsettings.routes.AppSettingsNavigationEvent
import org.signal.core.ui.compose.Dividers
import org.signal.core.ui.compose.Rows
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.horizontalGutters

@Composable
fun PluginsSettingsEntry(
  parentEventEmitter: (AppSettingsNavigationEvent) -> Unit
) {
  val context = LocalContext.current.applicationContext
  val viewModel: PluginsSettingsViewModel = viewModel {
    PluginsSettingsViewModel(context, parentEventEmitter)
  }
  val state by viewModel.state.collectAsStateWithLifecycle()

  LifecycleResumeEffect(Unit) {
    viewModel.onEvent(PluginsSettingsEvent.Refresh)
    onPauseOrDispose {}
  }

  PluginsSettingsScreen(
    state = state,
    onEvent = viewModel::onEvent
  )

  if (state.showInstallSheet) {
    PluginInstallSheet(
      onDismiss = { viewModel.onEvent(PluginsSettingsEvent.DismissInstallSheet) },
      onPreviewInstall = { name, version, author, desc, source, perms ->
        viewModel.previewInstall(name, version, author, desc, source, perms)
      },
      onConfirmInstall = { preview, approved ->
        viewModel.confirmInstall(preview, approved)
      }
    )
  }
}

@Composable
fun PluginsSettingsScreen(
  state: PluginsSettingsState,
  onEvent: (PluginsSettingsEvent) -> Unit
) {
  Scaffolds.Settings(
    title = "Plugins",
    navigationIcon = SignalIcons.ArrowStart.imageVector,
    onNavigationClick = { onEvent(PluginsSettingsEvent.NavigateBack) },
    collapseTopBarInSplitPane = true,
    actions = {
      IconButton(onClick = { onEvent(PluginsSettingsEvent.OpenGlobalAuditLog) }) {
        Icon(
          imageVector = SignalIcons.Recent.imageVector,
          contentDescription = "Audit Log"
        )
      }
      IconButton(onClick = { onEvent(PluginsSettingsEvent.OpenInstallSheet) }) {
        Icon(
          imageVector = SignalIcons.Plus.imageVector,
          contentDescription = "Install Plugin"
        )
      }
    }
  ) { contentPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(contentPadding)
    ) {
      AppSettingsLazyColumn(
        contentPadding = PaddingValues(bottom = 88.dp)
      ) {
        item {
          SandboxStatusCard(
            status = state.sandboxStatus,
            onRestart = { onEvent(PluginsSettingsEvent.RestartSandbox) },
            modifier = Modifier
              .horizontalGutters()
              .padding(top = 8.dp, bottom = 12.dp)
          )
        }

        item {
          Rows.TextRow(
            text = "Audit Log",
            label = "View allowed and denied plugin IPC calls",
            icon = SignalIcons.Recent.painter,
            onClick = { onEvent(PluginsSettingsEvent.OpenGlobalAuditLog) }
          )
        }

        item {
          Dividers.Default()
        }

        if (state.plugins.isEmpty()) {
          item {
            EmptyPluginsCard(
              onInstallClick = { onEvent(PluginsSettingsEvent.OpenInstallSheet) },
              modifier = Modifier
                .horizontalGutters()
                .padding(vertical = 24.dp)
            )
          }
        } else {
          item {
            Text(
              text = "Installed plugins",
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier
                .horizontalGutters()
                .padding(top = 16.dp, bottom = 8.dp)
            )
          }

          items(state.plugins, key = { it.id }) { plugin ->
            PluginListItem(
              plugin = plugin,
              grantedCount = state.grantedCounts[plugin.id] ?: 1,
              onClick = { onEvent(PluginsSettingsEvent.OpenPluginPermissions(plugin.id)) },
              onToggleEnabled = { enabled ->
                onEvent(PluginsSettingsEvent.TogglePluginEnabled(plugin.id, enabled))
              }
            )
          }
        }
      }

      FloatingActionButton(
        onClick = { onEvent(PluginsSettingsEvent.OpenInstallSheet) },
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .padding(16.dp)
      ) {
        Icon(
          imageVector = SignalIcons.Plus.imageVector,
          contentDescription = "Install Plugin"
        )
      }
    }
  }
}

@Composable
private fun SandboxStatusCard(
  status: SandboxStatus,
  onRestart: () -> Unit,
  modifier: Modifier = Modifier
) {
  val containerColor = if (status.connected) {
    MaterialTheme.colorScheme.surfaceContainerHigh
  } else {
    MaterialTheme.colorScheme.errorContainer
  }
  val contentColor = if (status.connected) {
    MaterialTheme.colorScheme.onSurface
  } else {
    MaterialTheme.colorScheme.onErrorContainer
  }

  Card(
    colors = CardDefaults.cardColors(
      containerColor = containerColor,
      contentColor = contentColor
    ),
    shape = RoundedCornerShape(16.dp),
    modifier = modifier.fillMaxWidth()
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(16.dp)
    ) {
      Icon(
        imageVector = if (status.connected) SignalIcons.LockFill.imageVector else SignalIcons.ErrorCircle.imageVector,
        contentDescription = null,
        tint = if (status.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        modifier = Modifier.size(24.dp)
      )

      Spacer(modifier = Modifier.width(12.dp))

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = if (status.connected) "Sandbox isolated & active" else "Sandbox disconnected",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold
        )
        val subtitle = if (status.connected) {
          val proc = status.processName.ifBlank { ":plugin_sandbox" }
          "$proc (PID ${status.pid}) • ${status.loadedPluginIds.size} loaded"
        } else {
          status.lastError ?: "Waiting for :plugin_sandbox process"
        }
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = contentColor.copy(alpha = 0.8f),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis
        )
      }

      TextButton(onClick = onRestart) {
        Text("Restart")
      }
    }
  }
}

@Composable
private fun PluginListItem(
  plugin: PluginInfo,
  grantedCount: Int,
  onClick: () -> Unit,
  onToggleEnabled: (Boolean) -> Unit
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .horizontalGutters()
      .padding(vertical = 12.dp)
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Text(
          text = plugin.name,
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.Medium,
          color = MaterialTheme.colorScheme.onSurface
        )
        Text(
          text = "v${plugin.version}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (plugin.isObfuscated) {
          Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = RoundedCornerShape(6.dp)
          ) {
            Text(
              text = "Obfuscated",
              style = MaterialTheme.typography.labelSmall,
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
          }
        }
      }

      if (plugin.description.isNotBlank()) {
        Text(
          text = plugin.description,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(top = 2.dp)
        )
      }

      Text(
        text = "by ${plugin.author} • $grantedCount permissions granted",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
      )
    }

    Spacer(modifier = Modifier.width(12.dp))

    Switch(
      checked = plugin.enabled,
      onCheckedChange = onToggleEnabled
    )
  }
}

@Composable
private fun EmptyPluginsCard(
  onInstallClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  Card(
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ),
    shape = RoundedCornerShape(20.dp),
    modifier = modifier.fillMaxWidth()
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier
        .fillMaxWidth()
        .padding(24.dp)
    ) {
      Icon(
        imageVector = SignalIcons.Settings.imageVector,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(36.dp)
      )
      Spacer(modifier = Modifier.height(12.dp))
      Text(
        text = "No plugins installed",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = "Plugins run in an isolated :plugin_sandbox process with strict default-deny permissions.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      Spacer(modifier = Modifier.height(16.dp))
      FilledTonalButton(onClick = onInstallClick) {
        Icon(
          imageVector = SignalIcons.Plus.imageVector,
          contentDescription = null,
          modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("Install plugin")
      }
    }
  }
}
