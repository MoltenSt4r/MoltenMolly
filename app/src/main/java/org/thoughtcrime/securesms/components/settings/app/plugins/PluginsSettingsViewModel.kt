package org.thoughtcrime.securesms.components.settings.app.plugins

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import im.molly.plugins.PluginInfo
import im.molly.plugins.PluginInstallPreview
import im.molly.plugins.PluginManager
import im.molly.plugins.PluginUiNotice
import im.molly.plugins.SandboxStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.signal.appsettings.routes.AppSettingsNavigationEvent
import org.signal.appsettings.routes.AppSettingsRoute
import org.signal.core.ui.compose.EventDrivenViewModel
import org.signal.core.util.logging.Log

@Immutable
data class PluginsSettingsState(
  val plugins: List<PluginInfo> = emptyList(),
  val grantedCounts: Map<String, Int> = emptyMap(),
  val sandboxStatus: SandboxStatus = SandboxStatus(connected = false),
  val uiNotices: List<PluginUiNotice> = emptyList(),
  val showInstallSheet: Boolean = false
)

sealed interface PluginsSettingsEvent {
  data object NavigateBack : PluginsSettingsEvent
  data object Refresh : PluginsSettingsEvent
  data object RestartSandbox : PluginsSettingsEvent
  data object OpenInstallSheet : PluginsSettingsEvent
  data object DismissInstallSheet : PluginsSettingsEvent
  data object OpenGlobalAuditLog : PluginsSettingsEvent
  data class OpenPluginPermissions(val pluginId: String) : PluginsSettingsEvent
  data class TogglePluginEnabled(val pluginId: String, val enabled: Boolean) : PluginsSettingsEvent
}

class PluginsSettingsViewModel(
  context: Context,
  private val parentEventEmitter: (AppSettingsNavigationEvent) -> Unit
) : EventDrivenViewModel<PluginsSettingsEvent>(TAG) {

  companion object {
    private val TAG = Log.tag(PluginsSettingsViewModel::class)
  }

  private val pluginManager = PluginManager.getInstance(context)

  private val _state = MutableStateFlow(PluginsSettingsState())
  val state: StateFlow<PluginsSettingsState> = _state.asStateFlow()

  init {
    viewModelScope.launch {
      combine(
        pluginManager.pluginsFlow,
        pluginManager.sandboxStatusFlow,
        pluginManager.uiNoticesFlow
      ) { plugins, sandbox, notices ->
        val counts = plugins.associate { plugin ->
          plugin.id to pluginManager.getGrantedPermissions(plugin.id).size
        }
        Triple(plugins, sandbox, Pair(counts, notices))
      }.collect { (plugins, sandbox, extra) ->
        _state.update {
          it.copy(
            plugins = plugins,
            sandboxStatus = sandbox,
            grantedCounts = extra.first,
            uiNotices = extra.second
          )
        }
      }
    }
  }

  fun previewInstall(
    name: String,
    version: String,
    author: String,
    description: String,
    sourceCode: String,
    declaredPermissions: Collection<String>
  ): PluginInstallPreview {
    return pluginManager.previewInstall(
      name = name,
      version = version,
      author = author,
      description = description,
      sourceCode = sourceCode,
      declaredPermissions = declaredPermissions
    )
  }

  fun confirmInstall(
    preview: PluginInstallPreview,
    approvedPermissions: Collection<String>
  ) {
    pluginManager.confirmInstall(preview, approvedPermissions, enabled = true)
    _state.update { it.copy(showInstallSheet = false) }
  }

  override suspend fun processEvent(event: PluginsSettingsEvent) {
    when (event) {
      PluginsSettingsEvent.NavigateBack -> parentEventEmitter(AppSettingsNavigationEvent.NavigateBack)
      PluginsSettingsEvent.Refresh -> pluginManager.refreshSandboxStatus()
      PluginsSettingsEvent.RestartSandbox -> pluginManager.restartSandbox()
      PluginsSettingsEvent.OpenInstallSheet -> _state.update { it.copy(showInstallSheet = true) }
      PluginsSettingsEvent.DismissInstallSheet -> _state.update { it.copy(showInstallSheet = false) }
      PluginsSettingsEvent.OpenGlobalAuditLog -> parentEventEmitter(
        AppSettingsNavigationEvent.NavigateToScreen(AppSettingsRoute.PluginsRoute.AuditLog(null))
      )
      is PluginsSettingsEvent.OpenPluginPermissions -> parentEventEmitter(
        AppSettingsNavigationEvent.NavigateToScreen(AppSettingsRoute.PluginsRoute.Permissions(event.pluginId))
      )
      is PluginsSettingsEvent.TogglePluginEnabled -> pluginManager.setPluginEnabled(event.pluginId, event.enabled)
    }
  }
}
