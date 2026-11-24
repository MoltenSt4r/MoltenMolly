package org.thoughtcrime.securesms.components.settings.app.plugins

import android.os.Bundle
import android.view.View
import androidx.compose.runtime.Composable
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.findNavController
import org.signal.appsettings.routes.AppSettingsNavigationEvent
import org.signal.appsettings.routes.AppSettingsRoute
import org.signal.appsettings.routes.AppSettingsRouter
import org.signal.core.ui.compose.ComposeFragment
import org.signal.core.util.collectFlowOnViewLifecycle
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.util.navigation.safeNavigate

private const val ARG_PLUGIN_ID = "plugin_id"

/**
 * Fragment host for [PluginsSettingsEntry] in the legacy XML nav graph.
 */
class PluginsSettingsFragment : ComposeFragment() {

  private val appSettingsRouter by viewModels<AppSettingsRouter>()

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    collectFlowOnViewLifecycle(appSettingsRouter.events, Lifecycle.State.RESUMED) { event ->
      when (event) {
        AppSettingsNavigationEvent.NavigateBack -> requireActivity().onBackPressedDispatcher.onBackPressed()
        is AppSettingsNavigationEvent.NavigateToScreen -> when (val route = event.route) {
          is AppSettingsRoute.PluginsRoute.Permissions -> findNavController().safeNavigate(
            R.id.action_pluginsSettingsFragment_to_pluginPermissionsFragment,
            bundleOf(ARG_PLUGIN_ID to route.pluginId)
          )
          is AppSettingsRoute.PluginsRoute.AuditLog -> findNavController().safeNavigate(
            R.id.action_pluginsSettingsFragment_to_pluginAuditLogFragment,
            bundleOf(ARG_PLUGIN_ID to route.pluginId)
          )
          else -> error("Unexpected route: ${route.javaClass.name}")
        }
      }
    }
  }

  @Composable
  override fun FragmentContent() {
    PluginsSettingsEntry(parentEventEmitter = appSettingsRouter::onEvent)
  }
}

/**
 * Fragment host for [PluginPermissionsEntry] in the legacy XML nav graph.
 */
class PluginPermissionsFragment : ComposeFragment() {

  private val appSettingsRouter by viewModels<AppSettingsRouter>()

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    collectFlowOnViewLifecycle(appSettingsRouter.events, Lifecycle.State.RESUMED) { event ->
      when (event) {
        AppSettingsNavigationEvent.NavigateBack -> requireActivity().onBackPressedDispatcher.onBackPressed()
        is AppSettingsNavigationEvent.NavigateToScreen -> when (val route = event.route) {
          is AppSettingsRoute.PluginsRoute.AuditLog -> findNavController().safeNavigate(
            R.id.action_pluginPermissionsFragment_to_pluginAuditLogFragment,
            bundleOf(ARG_PLUGIN_ID to route.pluginId)
          )
          else -> error("Unexpected route: ${route.javaClass.name}")
        }
      }
    }
  }

  @Composable
  override fun FragmentContent() {
    val pluginId = requireArguments().getString(ARG_PLUGIN_ID) ?: ""
    PluginPermissionsEntry(
      pluginId = pluginId,
      parentEventEmitter = appSettingsRouter::onEvent
    )
  }
}

/**
 * Fragment host for [PluginAuditLogEntry] in the legacy XML nav graph.
 */
class PluginAuditLogFragment : ComposeFragment() {

  private val appSettingsRouter by viewModels<AppSettingsRouter>()

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    collectFlowOnViewLifecycle(appSettingsRouter.events, Lifecycle.State.RESUMED) { event ->
      when (event) {
        AppSettingsNavigationEvent.NavigateBack -> requireActivity().onBackPressedDispatcher.onBackPressed()
        is AppSettingsNavigationEvent.NavigateToScreen -> Unit
      }
    }
  }

  @Composable
  override fun FragmentContent() {
    val pluginId = arguments?.getString(ARG_PLUGIN_ID)
    PluginAuditLogEntry(
      pluginId = pluginId,
      parentEventEmitter = appSettingsRouter::onEvent
    )
  }
}
