package im.molly.plugins

import im.molly.plugins.ipc.PluginManifestDto

data class PluginInfo(
  val id: String,
  val name: String,
  val version: String,
  val author: String,
  val description: String,
  val declaredPermissions: List<String>,
  val enabled: Boolean,
  val installedAtMs: Long,
  val isObfuscated: Boolean = false
) {
  fun toManifestDto(): PluginManifestDto = PluginManifestDto(
    id = id,
    name = name,
    version = version,
    author = author,
    description = description,
    declaredPermissions = declaredPermissions,
    enabled = enabled
  )
}

data class PluginInstallPreview(
  val id: String,
  val name: String,
  val version: String,
  val author: String,
  val description: String,
  val sourceCode: String,
  val declaredPermissions: List<String>,
  val scanResult: PluginCapabilityScan.ScanResult
) {
  val requestedPermissions: List<String>
    get() = (declaredPermissions + scanResult.detectedPermissions)
      .distinct()
      .filter { it != PluginPermissions.UI && PluginPermissions.isKnown(it) }
}

data class PluginUiNotice(
  val pluginId: String,
  val title: String,
  val message: String,
  val timestampMs: Long
)

data class SandboxStatus(
  val connected: Boolean,
  val pid: Int = -1,
  val processName: String = "",
  val loadedPluginIds: List<String> = emptyList(),
  val lastError: String? = null
)
