package org.thoughtcrime.securesms.components.settings.app.plugins

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import org.signal.appsettings.routes.AppSettingsNavigationEvent
import org.signal.appsettings.routes.AppSettingsRoute
import org.signal.core.ui.compose.split.detailEntry

/**
 * Registers the routes within the plugins section of app settings.
 */
fun EntryProviderScope<NavKey>.registerPluginsRoutes(parentEventEmitter: (AppSettingsNavigationEvent) -> Unit) {
  detailEntry<AppSettingsRoute.PluginsRoute.Plugins> {
    PluginsSettingsEntry(parentEventEmitter = parentEventEmitter)
  }

  detailEntry<AppSettingsRoute.PluginsRoute.Permissions> { route ->
    PluginPermissionsEntry(
      pluginId = route.pluginId,
      parentEventEmitter = parentEventEmitter
    )
  }

  detailEntry<AppSettingsRoute.PluginsRoute.AuditLog> { route ->
    PluginAuditLogEntry(
      pluginId = route.pluginId,
      parentEventEmitter = parentEventEmitter
    )
  }
}
