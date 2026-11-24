package im.molly.plugins

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import im.molly.plugins.ipc.IPluginSandbox
import im.molly.plugins.ipc.PluginResultDto
import im.molly.plugins.sandbox.PluginSandboxService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.signal.core.util.logging.Log
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Main-process coordinator for Molly's isolated plugin system.
 * Manages plugin installation, capability scanning, permission grants, audit journal access,
 * and the Binder lifecycle to [PluginSandboxService] in `:plugin_sandbox`.
 */
class PluginManager private constructor(private val context: Context) {

  companion object {
    private val TAG = Log.tag(PluginManager::class.java)
    private const val REGISTRY_PREFS = "molly_plugins_registry"
    private const val KEY_PLUGINS_JSON = "plugins_list"

    @Volatile
    private var instance: PluginManager? = null

    @JvmStatic
    fun getInstance(context: Context): PluginManager {
      PluginProcessGuard.assertMainProcess(context, "PluginManager")
      instance?.let { return it }
      return synchronized(this) {
        instance ?: PluginManager(context.applicationContext).also { instance = it }
      }
    }
  }

  private val registryPrefs = context.getSharedPreferences(REGISTRY_PREFS, Context.MODE_PRIVATE)
  private val sourceDir = File(context.filesDir, "plugins/src").apply { mkdirs() }

  private val _pluginsFlow = MutableStateFlow<List<PluginInfo>>(emptyList())
  val pluginsFlow: StateFlow<List<PluginInfo>> = _pluginsFlow.asStateFlow()

  private val _sandboxStatusFlow = MutableStateFlow(SandboxStatus(connected = false))
  val sandboxStatusFlow: StateFlow<SandboxStatus> = _sandboxStatusFlow.asStateFlow()

  private val _uiNoticesFlow = MutableStateFlow<List<PluginUiNotice>>(emptyList())
  val uiNoticesFlow: StateFlow<List<PluginUiNotice>> = _uiNoticesFlow.asStateFlow()

  private val hostBinder = PluginHostBinder(context) { notice ->
    _uiNoticesFlow.value = (_uiNoticesFlow.value + notice).takeLast(50)
  }

  @Volatile
  private var sandboxBinder: IPluginSandbox? = null
  private var bound = false

  private val deathRecipient = IBinder.DeathRecipient {
    Log.w(TAG, ":plugin_sandbox process died; resetting binder")
    sandboxBinder = null
    _sandboxStatusFlow.value = SandboxStatus(connected = false, lastError = "Sandbox process died")
  }

  private val serviceConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
      if (service == null) return
      try {
        service.linkToDeath(deathRecipient, 0)
        val sandbox = IPluginSandbox.Stub.asInterface(service)
        sandbox.initialize(hostBinder)
        sandboxBinder = sandbox
        syncEnabledPluginsToSandbox(sandbox)
        refreshSandboxStatus()
      } catch (t: Throwable) {
        Log.w(TAG, "Failed to initialize :plugin_sandbox", t)
        _sandboxStatusFlow.value = SandboxStatus(connected = false, lastError = t.message)
      }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
      sandboxBinder = null
      _sandboxStatusFlow.value = SandboxStatus(connected = false, lastError = "Disconnected")
    }
  }

  init {
    _pluginsFlow.value = loadRegistry()
    ensureSandboxBound()
  }

  fun ensureSandboxBound() {
    if (bound && sandboxBinder != null) return
    try {
      val intent = Intent(context, PluginSandboxService::class.java)
      bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    } catch (t: Throwable) {
      Log.w(TAG, "Unable to bind PluginSandboxService", t)
      _sandboxStatusFlow.value = SandboxStatus(connected = false, lastError = t.message)
    }
  }

  fun restartSandbox() {
    try {
      sandboxBinder?.shutdown()
    } catch (_: Throwable) {
    }
    if (bound) {
      try {
        context.unbindService(serviceConnection)
      } catch (_: Throwable) {
      }
      bound = false
    }
    sandboxBinder = null
    _sandboxStatusFlow.value = SandboxStatus(connected = false)
    ensureSandboxBound()
  }

  fun refreshSandboxStatus(): SandboxStatus {
    val sandbox = sandboxBinder
    if (sandbox == null) {
      val disconnected = SandboxStatus(connected = false)
      _sandboxStatusFlow.value = disconnected
      return disconnected
    }
    return try {
      val health = sandbox.health
      val status = SandboxStatus(
        connected = true,
        pid = health.pid,
        processName = health.processName,
        loadedPluginIds = health.loadedPluginIds
      )
      _sandboxStatusFlow.value = status
      status
    } catch (t: Throwable) {
      val err = SandboxStatus(connected = false, lastError = t.message)
      _sandboxStatusFlow.value = err
      err
    }
  }

  /**
   * Scans a candidate plugin without installing or executing it, returning a preview
   * with detected capabilities and obfuscation warnings for the install sheet UI.
   */
  fun previewInstall(
    name: String,
    version: String,
    author: String,
    description: String,
    sourceCode: String,
    declaredPermissions: Collection<String> = emptyList(),
    explicitId: String? = null
  ): PluginInstallPreview {
    val id = explicitId?.takeIf { it.isNotBlank() } ?: sanitizeId(name)
    val cleanDeclared = PluginPermissions.sanitize(declaredPermissions)
    val scan = PluginCapabilityScan.scan(sourceCode, cleanDeclared)
    return PluginInstallPreview(
      id = id,
      name = name.ifBlank { id },
      version = version.ifBlank { "1.0.0" },
      author = author.ifBlank { "Unknown" },
      description = description,
      sourceCode = sourceCode,
      declaredPermissions = cleanDeclared,
      scanResult = scan
    )
  }

  /**
   * Finalizes plugin installation with the exact permissions approved by the user.
   */
  fun confirmInstall(
    preview: PluginInstallPreview,
    approvedPermissions: Collection<String>,
    enabled: Boolean = true
  ): PluginInfo {
    val info = PluginInfo(
      id = preview.id,
      name = preview.name,
      version = preview.version,
      author = preview.author,
      description = preview.description,
      declaredPermissions = preview.declaredPermissions,
      enabled = enabled,
      installedAtMs = System.currentTimeMillis(),
      isObfuscated = preview.scanResult.isObfuscated
    )

    File(sourceDir, "${info.id}.plugin").writeText(preview.sourceCode)
    PluginCapabilityScan.store(context, info.id, preview.scanResult)
    PluginPermissions.setGranted(context, info.id, approvedPermissions)

    val updated = _pluginsFlow.value.filterNot { it.id == info.id } + info
    saveRegistry(updated)
    _pluginsFlow.value = updated

    if (enabled) {
      ensureSandboxBound()
      try {
        sandboxBinder?.loadPlugin(info.toManifestDto(), preview.sourceCode)
        refreshSandboxStatus()
      } catch (t: Throwable) {
        Log.w(TAG, "Failed to load newly installed plugin ${info.id} in sandbox", t)
      }
    }
    return info
  }

  fun uninstallPlugin(pluginId: String) {
    try {
      sandboxBinder?.unloadPlugin(pluginId)
    } catch (_: Throwable) {
    }
    File(sourceDir, "$pluginId.plugin").delete()
    File(context.filesDir, "plugins/data/$pluginId").deleteRecursively()
    PluginPermissions.clear(context, pluginId)
    PluginCapabilityScan.clear(context, pluginId)
    PluginAuditJournal.forget(pluginId)

    val updated = _pluginsFlow.value.filterNot { it.id == pluginId }
    saveRegistry(updated)
    _pluginsFlow.value = updated
    refreshSandboxStatus()
  }

  fun setPluginEnabled(pluginId: String, enabled: Boolean) {
    val current = _pluginsFlow.value
    val target = current.firstOrNull { it.id == pluginId } ?: return
    val updatedTarget = target.copy(enabled = enabled)
    val updated = current.map { if (it.id == pluginId) updatedTarget else it }
    saveRegistry(updated)
    _pluginsFlow.value = updated

    ensureSandboxBound()
    try {
      val sandbox = sandboxBinder
      if (sandbox != null) {
        if (enabled) {
          val src = readPluginSource(pluginId) ?: ""
          sandbox.loadPlugin(updatedTarget.toManifestDto(), src)
        } else {
          sandbox.setPluginEnabled(pluginId, false)
        }
        refreshSandboxStatus()
      }
    } catch (t: Throwable) {
      Log.w(TAG, "Failed to toggle plugin $pluginId in sandbox", t)
    }
  }

  fun getPlugins(): List<PluginInfo> = _pluginsFlow.value

  fun getPlugin(pluginId: String): PluginInfo? = _pluginsFlow.value.firstOrNull { it.id == pluginId }

  fun getGrantedPermissions(pluginId: String): Set<String> =
    PluginPermissions.getGranted(context, pluginId)

  fun setGrantedPermissions(pluginId: String, permissions: Collection<String>) {
    PluginPermissions.setGranted(context, pluginId, permissions)
  }

  fun grantPermission(pluginId: String, permission: String) {
    PluginPermissions.grant(context, pluginId, permission)
  }

  fun revokePermission(pluginId: String, permission: String) {
    PluginPermissions.revoke(context, pluginId, permission)
  }

  fun getCapabilityScan(pluginId: String): PluginCapabilityScan.ScanResult =
    PluginCapabilityScan.load(context, pluginId)

  fun getAuditEntries(pluginId: String? = null): List<PluginAuditJournal.Entry> =
    PluginAuditJournal.entries(pluginId)

  fun getAuditProfile(pluginId: String): Map<String, IntArray> =
    PluginAuditJournal.profile(pluginId)

  fun clearAuditJournal(pluginId: String? = null) {
    PluginAuditJournal.clear(pluginId)
  }

  fun dispatchEvent(pluginId: String, eventType: String, payload: Bundle = Bundle()): PluginResultDto {
    ensureSandboxBound()
    val sandbox = sandboxBinder
      ?: return PluginResultDto.error("SANDBOX_DISCONNECTED", ":plugin_sandbox is not connected")
    return try {
      sandbox.dispatchEvent(pluginId, eventType, payload)
    } catch (t: Throwable) {
      PluginResultDto.error("IPC_ERROR", t.message ?: "IPC failure")
    }
  }

  private fun syncEnabledPluginsToSandbox(sandbox: IPluginSandbox) {
    for (plugin in _pluginsFlow.value) {
      if (!plugin.enabled) continue
      val source = readPluginSource(plugin.id) ?: continue
      try {
        sandbox.loadPlugin(plugin.toManifestDto(), source)
      } catch (t: Throwable) {
        Log.w(TAG, "Failed to sync plugin ${plugin.id} to sandbox", t)
      }
    }
  }

  private fun readPluginSource(pluginId: String): String? {
    val file = File(sourceDir, "$pluginId.plugin")
    return if (file.exists()) file.readText() else null
  }

  private fun sanitizeId(name: String): String {
    val slug = name.lowercase(Locale.US)
      .replace(Regex("[^a-z0-9_]+"), "_")
      .trim('_')
      .take(32)
    return if (slug.isNotEmpty()) slug else "plugin_${UUID.randomUUID().toString().take(8)}"
  }

  private fun loadRegistry(): List<PluginInfo> {
    val raw = registryPrefs.getString(KEY_PLUGINS_JSON, null) ?: return emptyList()
    return try {
      val arr = JSONArray(raw)
      val out = ArrayList<PluginInfo>(arr.length())
      for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val permsArr = obj.optJSONArray("declaredPermissions")
        val perms = ArrayList<String>()
        if (permsArr != null) {
          for (j in 0 until permsArr.length()) {
            perms.add(permsArr.optString(j))
          }
        }
        out.add(
          PluginInfo(
            id = obj.getString("id"),
            name = obj.getString("name"),
            version = obj.optString("version", "1.0.0"),
            author = obj.optString("author", "Unknown"),
            description = obj.optString("description", ""),
            declaredPermissions = PluginPermissions.sanitize(perms),
            enabled = obj.optBoolean("enabled", true),
            installedAtMs = obj.optLong("installedAtMs", 0L),
            isObfuscated = obj.optBoolean("isObfuscated", false)
          )
        )
      }
      out
    } catch (t: Throwable) {
      Log.w(TAG, "Failed to load plugin registry", t)
      emptyList()
    }
  }

  private fun saveRegistry(plugins: List<PluginInfo>) {
    val arr = JSONArray()
    for (p in plugins) {
      val obj = JSONObject()
      obj.put("id", p.id)
      obj.put("name", p.name)
      obj.put("version", p.version)
      obj.put("author", p.author)
      obj.put("description", p.description)
      obj.put("declaredPermissions", JSONArray(p.declaredPermissions))
      obj.put("enabled", p.enabled)
      obj.put("installedAtMs", p.installedAtMs)
      obj.put("isObfuscated", p.isObfuscated)
      arr.put(obj)
    }
    registryPrefs.edit().putString(KEY_PLUGINS_JSON, arr.toString()).apply()
  }
}
