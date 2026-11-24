package org.thoughtcrime.securesms.components.settings.app.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import im.molly.plugins.PluginAuditJournal
import org.signal.appsettings.routes.AppSettingsNavigationEvent
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.horizontalGutters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PluginAuditLogEntry(
  pluginId: String?,
  parentEventEmitter: (AppSettingsNavigationEvent) -> Unit
) {
  val context = LocalContext.current.applicationContext
  val viewModel: PluginAuditLogViewModel = viewModel(key = "plugin_audit_${pluginId ?: "all"}") {
    PluginAuditLogViewModel(context, pluginId, parentEventEmitter)
  }
  val state by viewModel.state.collectAsStateWithLifecycle()

  PluginAuditLogScreen(
    state = state,
    onEvent = viewModel::onEvent
  )
}

@Composable
fun PluginAuditLogScreen(
  state: PluginAuditLogState,
  onEvent: (PluginAuditLogEvent) -> Unit
) {
  val title = if (state.pluginName != null) {
    "${state.pluginName} audit log"
  } else {
    "Plugin audit log"
  }

  Scaffolds.Settings(
    title = title,
    navigationIcon = SignalIcons.ArrowStart.imageVector,
    onNavigationClick = { onEvent(PluginAuditLogEvent.NavigateBack) },
    collapseTopBarInSplitPane = true,
    actions = {
      if (state.entries.isNotEmpty()) {
        IconButton(onClick = { onEvent(PluginAuditLogEvent.RequestClear) }) {
          Icon(
            imageVector = SignalIcons.Trash.imageVector,
            contentDescription = "Clear audit log"
          )
        }
      }
    }
  ) { contentPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(contentPadding)
    ) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
          .horizontalGutters()
          .padding(vertical = 8.dp)
      ) {
        FilterChip(
          selected = state.filter == AuditFilter.ALL,
          onClick = { onEvent(PluginAuditLogEvent.SetFilter(AuditFilter.ALL)) },
          label = { Text("All") }
        )
        FilterChip(
          selected = state.filter == AuditFilter.ALLOWED,
          onClick = { onEvent(PluginAuditLogEvent.SetFilter(AuditFilter.ALLOWED)) },
          label = { Text("Allowed") }
        )
        FilterChip(
          selected = state.filter == AuditFilter.DENIED,
          onClick = { onEvent(PluginAuditLogEvent.SetFilter(AuditFilter.DENIED)) },
          label = { Text("Denied") }
        )
      }

      if (state.entries.isEmpty()) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
          modifier = Modifier
            .fillMaxSize()
            .horizontalGutters()
        ) {
          Text(
            text = "No audit events recorded",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "Allowed and denied plugin IPC calls will appear here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      } else {
        LazyColumn(
          contentPadding = PaddingValues(bottom = 24.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.fillMaxSize()
        ) {
          items(state.entries) { entry ->
            AuditEntryCard(
              entry = entry,
              showPluginId = state.pluginId == null,
              modifier = Modifier.horizontalGutters()
            )
          }
        }
      }
    }
  }

  if (state.showClearDialog) {
    AlertDialog(
      onDismissRequest = { onEvent(PluginAuditLogEvent.DismissClearDialog) },
      title = { Text("Clear audit log?") },
      text = { Text("This clears the recorded IPC audit history.") },
      confirmButton = {
        TextButton(onClick = { onEvent(PluginAuditLogEvent.ConfirmClear) }) {
          Text("Clear")
        }
      },
      dismissButton = {
        TextButton(onClick = { onEvent(PluginAuditLogEvent.DismissClearDialog) }) {
          Text("Cancel")
        }
      }
    )
  }
}

@Composable
private fun AuditEntryCard(
  entry: PluginAuditJournal.Entry,
  showPluginId: Boolean,
  modifier: Modifier = Modifier
) {
  val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
  val timeText = remember(entry.timestampMs) { timeFormatter.format(Date(entry.timestampMs)) }

  Card(
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ),
    shape = RoundedCornerShape(12.dp),
    modifier = modifier.fillMaxWidth()
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
      ) {
        Surface(
          color = if (entry.allowed) {
            MaterialTheme.colorScheme.primaryContainer
          } else {
            MaterialTheme.colorScheme.errorContainer
          },
          contentColor = if (entry.allowed) {
            MaterialTheme.colorScheme.onPrimaryContainer
          } else {
            MaterialTheme.colorScheme.onErrorContainer
          },
          shape = RoundedCornerShape(6.dp)
        ) {
          Text(
            text = if (entry.allowed) "ALLOWED" else "DENIED",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
          )
        }

        Text(
          text = entry.event,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Medium,
          modifier = Modifier.weight(1f)
        )

        if (entry.count > 1) {
          Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(6.dp)
          ) {
            Text(
              text = "×${entry.count}",
              style = MaterialTheme.typography.labelSmall,
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
          }
        }

        Text(
          text = timeText,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }

      Spacer(modifier = Modifier.height(4.dp))

      val meta = buildString {
        if (showPluginId) {
          append(entry.pluginId)
          append(" • ")
        }
        append(entry.category)
      }
      Text(
        text = meta,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary
      )

      if (entry.detail.isNotBlank()) {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = entry.detail,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }
  }
}
