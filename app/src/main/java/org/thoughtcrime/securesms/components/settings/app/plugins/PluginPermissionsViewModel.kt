package org.thoughtcrime.securesms.components.settings.app.plugins

import android.content.Context
import androidx.compose.runtime.Immutable
import im.molly.plugins.PluginCapabilityScan
import im.molly.plugins.PluginInfo
import im.molly.plugins.PluginManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.signal.appsettings.routes.AppSettingsNavigationEvent
import org.signal.appsettings.routes.AppSettingsRoute
import org.signal.core.ui.compose.EventDrivenViewModel
import org.signal.core.util.logging.Log

@Immutable
data class PluginPermissionsState(
  val plugin: PluginInfo? = null,
  val grantedPermissions: Set<String> = emptySet(),
  val scanResult: PluginCapabilityScan.ScanResult = PluginCapabilityScan.ScanResult(emptyMap(), emptyList()),
  val auditProfile: Map<String, IntArray> = emptyMap(),
  val showUninstallDialog: Boolean = false
)

sealed interface PluginPermissionsEvent {
  data object NavigateBack : PluginPermissionsEvent
  data object Refresh : PluginPermissionsEvent
  data class ToggleEnabled(val enabled: Boolean) : PluginPermissionsEvent
  data class TogglePermission(val permission: String, val granted: Boolean) : PluginPermissionsEvent
  data object OpenAuditLog : PluginPermissionsEvent
  data object RequestUninstall : PluginPermissionsEvent
  data object DismissUninstallDialog : PluginPermissionsEvent
  data object ConfirmUninstall : PluginPermissionsEvent
}

class PluginPermissionsViewModel(
  context: Context,
  private val pluginId: String,
  private val parentEventEmitter: (AppSettingsNavigationEvent) -> Unit
) : EventDrivenViewModel<PluginPermissionsEvent>(TAG) {

  companion object {
    private val TAG = Log.tag(PluginPermissionsViewModel::class)
  }

  private val pluginManager = PluginManager.getInstance(context)

  private val _state = MutableStateFlow(PluginPermissionsState())
  val state: StateFlow<PluginPermissionsState> = _state.asStateFlow()

  init {
    reload()
  }

  private fun reload() {
    _state.update {
      it.copy(
        plugin = pluginManager.getPlugin(pluginId),
        grantedPermissions = pluginManager.getGrantedPermissions(pluginId),
        scanResult = pluginManager.getCapabilityScan(pluginId),
        auditProfile = pluginManager.getAuditProfile(pluginId)
      )
    }
  }

  override suspend fun processEvent(event: PluginPermissionsEvent) {
    when (event) {
      PluginPermissionsEvent.NavigateBack -> parentEventEmitter(AppSettingsNavigationEvent.NavigateBack)
      PluginPermissionsEvent.Refresh -> reload()
      is PluginPermissionsEvent.ToggleEnabled -> {
        pluginManager.setPluginEnabled(pluginId, event.enabled)
        reload()
      }
      is PluginPermissionsEvent.TogglePermission -> {
        if (event.granted) {
          pluginManager.grantPermission(pluginId, event.permission)
        } else {
          pluginManager.revokePermission(pluginId, event.permission)
        }
        reload()
      }
      PluginPermissionsEvent.OpenAuditLog -> parentEventEmitter(
        AppSettingsNavigationEvent.NavigateToScreen(AppSettingsRoute.PluginsRoute.AuditLog(pluginId))
      )
      PluginPermissionsEvent.RequestUninstall -> _state.update { it.copy(showUninstallDialog = true) }
      PluginPermissionsEvent.DismissUninstallDialog -> _state.update { it.copy(showUninstallDialog = false) }
      PluginPermissionsEvent.ConfirmUninstall -> {
        _state.update { it.copy(showUninstallDialog = false) }
        pluginManager.uninstallPlugin(pluginId)
        parentEventEmitter(AppSettingsNavigationEvent.NavigateBack)
      }
    }
  }
}
