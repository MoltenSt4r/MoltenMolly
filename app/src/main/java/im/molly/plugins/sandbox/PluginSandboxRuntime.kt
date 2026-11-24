package im.molly.plugins.sandbox

import android.os.Bundle
import im.molly.plugins.ipc.IPluginHost
import im.molly.plugins.ipc.PluginContactDto
import im.molly.plugins.ipc.PluginFileResultDto
import im.molly.plugins.ipc.PluginHttpRequestDto
import im.molly.plugins.ipc.PluginHttpResponseDto
import im.molly.plugins.ipc.PluginManifestDto
import im.molly.plugins.ipc.PluginMessageDto
import im.molly.plugins.ipc.PluginResultDto
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Isolated plugin runtime executing strictly inside `:plugin_sandbox`.
 * Has zero access to SQLCipher passphrase, MasterSecret, KeyStore, SignalDatabase,
 * or SignalProtocolStore; every host capability goes through [IPluginHost] Binder IPC.
 */
class PluginSandboxRuntime {

  private data class LoadedPlugin(
    val manifest: PluginManifestDto,
    val sourceCode: String,
    @Volatile var enabled: Boolean
  )

  private val plugins = ConcurrentHashMap<String, LoadedPlugin>()
  private val executor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "molly-plugin-sandbox").apply { isDaemon = true }
  }

  @Volatile
  private var host: IPluginHost? = null

  fun attachHost(host: IPluginHost) {
    this.host = host
  }

  fun loadedPluginIds(): List<String> = plugins.keys().toList().sorted()

  fun loadPlugin(manifest: PluginManifestDto, sourceCode: String): PluginResultDto {
    if (manifest.id.isBlank()) {
      return PluginResultDto.error("INVALID_ID", "Plugin id cannot be blank")
    }
    plugins[manifest.id] = LoadedPlugin(
      manifest = manifest,
      sourceCode = sourceCode,
      enabled = manifest.enabled
    )
    return PluginResultDto.ok("Loaded ${manifest.id} in :plugin_sandbox")
  }

  fun unloadPlugin(pluginId: String): PluginResultDto {
    val removed = plugins.remove(pluginId)
    return if (removed != null) {
      PluginResultDto.ok("Unloaded $pluginId")
    } else {
      PluginResultDto.error("NOT_LOADED", "Plugin $pluginId is not loaded")
    }
  }

  fun setPluginEnabled(pluginId: String, enabled: Boolean): PluginResultDto {
    val plugin = plugins[pluginId]
      ?: return PluginResultDto.error("NOT_LOADED", "Plugin $pluginId is not loaded")
    plugin.enabled = enabled
    return PluginResultDto.ok("Plugin $pluginId enabled=$enabled")
  }

  fun dispatchEvent(pluginId: String, eventType: String, payload: Bundle?): PluginResultDto {
    val plugin = plugins[pluginId]
      ?: return PluginResultDto.error("NOT_LOADED", "Plugin $pluginId is not loaded")
    if (!plugin.enabled) {
      return PluginResultDto.error("DISABLED", "Plugin $pluginId is disabled")
    }
    val hostApi = host
      ?: return PluginResultDto.error("NO_HOST", "Host IPC not initialized")

    val future = executor.submit<PluginResultDto> {
      executeSandboxedEvent(plugin, hostApi, eventType, payload ?: Bundle.EMPTY)
    }

    return try {
      future.get(10, TimeUnit.SECONDS)
    } catch (_: TimeoutException) {
      future.cancel(true)
      PluginResultDto.error("TIMEOUT", "Plugin $pluginId timed out handling $eventType")
    } catch (t: Throwable) {
      PluginResultDto.error("EXECUTION_ERROR", t.message ?: "Execution failed")
    }
  }

  fun shutdown() {
    plugins.clear()
    host = null
    executor.shutdownNow()
  }

  /**
   * Executes a sandboxed plugin event using only [IPluginHost] IPC calls.
   * Built-in/scripted directive support allows deterministic testing and event routing
   * without ever exposing main-process secrets or direct Java reflection to plugins.
   */
  private fun executeSandboxedEvent(
    plugin: LoadedPlugin,
    hostApi: IPluginHost,
    eventType: String,
    payload: Bundle
  ): PluginResultDto {
    val pluginId = plugin.manifest.id
    return when (eventType) {
      "ping" -> {
        hostApi.postUiNotice(pluginId, plugin.manifest.name, "Sandbox ping OK")
      }
      "ipc.read_messages" -> {
        val threadId = payload.getLong("threadId", -1L)
        val limit = payload.getInt("limit", 10)
        val messages: List<PluginMessageDto> = hostApi.readMessages(pluginId, threadId, limit, 0L)
        PluginResultDto.ok("count=${messages.size}")
      }
      "ipc.send_message" -> {
        val threadId = payload.getLong("threadId", -1L)
        val body = payload.getString("body", "")
        hostApi.sendMessage(pluginId, threadId, body, -1L)
      }
      "ipc.read_contacts" -> {
        val query = payload.getString("query", "")
        val limit = payload.getInt("limit", 20)
        val contacts: List<PluginContactDto> = hostApi.readContacts(pluginId, query, limit)
        PluginResultDto.ok("count=${contacts.size}")
      }
      "ipc.http_request" -> {
        val url = payload.getString("url", "")
        val method = payload.getString("method", "GET")
        val response: PluginHttpResponseDto = hostApi.executeHttpRequest(
          pluginId,
          PluginHttpRequestDto(url = url, method = method)
        )
        if (response.success) {
          PluginResultDto.ok("status=${response.statusCode} proxied=${response.proxied}")
        } else {
          PluginResultDto.error("HTTP_${response.statusCode}", response.errorMessage)
        }
      }
      "ipc.read_file" -> {
        val path = payload.getString("path", "")
        val fileResult: PluginFileResultDto = hostApi.readPluginFile(pluginId, path)
        if (fileResult.success) {
          PluginResultDto.ok("bytes=${fileResult.data?.size ?: 0}")
        } else {
          PluginResultDto.error("FILE_ERROR", fileResult.errorMessage)
        }
      }
      "ipc.write_file" -> {
        val path = payload.getString("path", "")
        val data = payload.getByteArray("data") ?: ByteArray(0)
        hostApi.writePluginFile(pluginId, path, data)
      }
      "ipc.get_setting" -> {
        val key = payload.getString("key", "")
        val value = hostApi.getSetting(pluginId, key, "")
        PluginResultDto.ok(value ?: "")
      }
      "ipc.set_setting" -> {
        val key = payload.getString("key", "")
        val value = payload.getString("value", "")
        hostApi.setSetting(pluginId, key, value)
      }
      else -> PluginResultDto.ok("Handled $eventType for $pluginId")
    }
  }
}
