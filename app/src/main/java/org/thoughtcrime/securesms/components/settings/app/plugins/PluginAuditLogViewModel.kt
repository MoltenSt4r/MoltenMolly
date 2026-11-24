package org.thoughtcrime.securesms.components.settings.app.plugins

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import im.molly.plugins.PluginAuditJournal
import im.molly.plugins.PluginManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.signal.appsettings.routes.AppSettingsNavigationEvent
import org.signal.core.ui.compose.EventDrivenViewModel
import org.signal.core.util.logging.Log

enum class AuditFilter {
  ALL,
  ALLOWED,
  DENIED
}

@Immutable
data class PluginAuditLogState(
  val pluginId: String? = null,
  val pluginName: String? = null,
  val filter: AuditFilter = AuditFilter.ALL,
  val entries: List<PluginAuditJournal.Entry> = emptyList(),
  val showClearDialog: Boolean = false
)

sealed interface PluginAuditLogEvent {
  data object NavigateBack : PluginAuditLogEvent
  data class SetFilter(val filter: AuditFilter) : PluginAuditLogEvent
  data object RequestClear : PluginAuditLogEvent
  data object DismissClearDialog : PluginAuditLogEvent
  data object ConfirmClear : PluginAuditLogEvent
}

class PluginAuditLogViewModel(
  context: Context,
  private val pluginId: String?,
  private val parentEventEmitter: (AppSettingsNavigationEvent) -> Unit
) : EventDrivenViewModel<PluginAuditLogEvent>(TAG) {

  companion object {
    private val TAG = Log.tag(PluginAuditLogViewModel::class)
  }

  private val pluginManager = PluginManager.getInstance(context)

  private val _state = MutableStateFlow(
    PluginAuditLogState(
      pluginId = pluginId,
      pluginName = pluginId?.let { pluginManager.getPlugin(it)?.name ?: it }
    )
  )
  val state: StateFlow<PluginAuditLogState> = _state.asStateFlow()

  init {
    viewModelScope.launch {
      PluginAuditJournal.revision.collect {
        reloadEntries()
      }
    }
  }

  private fun reloadEntries() {
    val raw = pluginManager.getAuditEntries(pluginId).asReversed()
    val filtered = when (_state.value.filter) {
      AuditFilter.ALL -> raw
      AuditFilter.ALLOWED -> raw.filter { it.allowed }
      AuditFilter.DENIED -> raw.filter { !it.allowed }
    }
    _state.update { it.copy(entries = filtered) }
  }

  override suspend fun processEvent(event: PluginAuditLogEvent) {
    when (event) {
      PluginAuditLogEvent.NavigateBack -> parentEventEmitter(AppSettingsNavigationEvent.NavigateBack)
      is PluginAuditLogEvent.SetFilter -> {
        _state.update { it.copy(filter = event.filter) }
        reloadEntries()
      }
      PluginAuditLogEvent.RequestClear -> _state.update { it.copy(showClearDialog = true) }
      PluginAuditLogEvent.DismissClearDialog -> _state.update { it.copy(showClearDialog = false) }
      PluginAuditLogEvent.ConfirmClear -> {
        _state.update { it.copy(showClearDialog = false) }
        pluginManager.clearAuditJournal(pluginId)
      }
    }
  }
}
