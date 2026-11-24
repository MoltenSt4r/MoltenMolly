package im.molly.plugins.sandbox

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import im.molly.plugins.PluginProcessGuard
import im.molly.plugins.ipc.IPluginHost
import im.molly.plugins.ipc.IPluginSandbox
import im.molly.plugins.ipc.PluginHealthDto
import im.molly.plugins.ipc.PluginManifestDto
import im.molly.plugins.ipc.PluginResultDto

/**
 * Bound Android Service running exclusively in the `:plugin_sandbox` process.
 * Communicates with the main Molly process strictly via [IPluginSandbox] and [IPluginHost] AIDL.
 */
class PluginSandboxService : Service() {

  private val startedAtMs = SystemClock.elapsedRealtime()
  private val runtime = PluginSandboxRuntime()

  private val binder = object : IPluginSandbox.Stub() {
    override fun initialize(host: IPluginHost) {
      PluginProcessGuard.assertSandboxProcess(this@PluginSandboxService, "PluginSandboxService")
      runtime.attachHost(host)
    }

    override fun loadPlugin(manifest: PluginManifestDto, sourceCode: String): PluginResultDto {
      PluginProcessGuard.assertSandboxProcess(this@PluginSandboxService, "PluginSandboxService")
      return runtime.loadPlugin(manifest, sourceCode)
    }

    override fun unloadPlugin(pluginId: String): PluginResultDto {
      return runtime.unloadPlugin(pluginId)
    }

    override fun setPluginEnabled(pluginId: String, enabled: Boolean): PluginResultDto {
      return runtime.setPluginEnabled(pluginId, enabled)
    }

    override fun dispatchEvent(pluginId: String, eventType: String, payload: Bundle?): PluginResultDto {
      return runtime.dispatchEvent(pluginId, eventType, payload)
    }

    override fun getHealth(): PluginHealthDto {
      return PluginHealthDto(
        pid = Process.myPid(),
        processName = PluginProcessGuard.getProcessName(this@PluginSandboxService),
        loadedPluginIds = runtime.loadedPluginIds(),
        uptimeMs = SystemClock.elapsedRealtime() - startedAtMs
      )
    }

    override fun shutdown() {
      runtime.shutdown()
      stopSelf()
    }
  }

  override fun onCreate() {
    super.onCreate()
    PluginProcessGuard.assertSandboxProcess(this, "PluginSandboxService")
  }

  override fun onBind(intent: Intent?): IBinder = binder

  override fun onDestroy() {
    runtime.shutdown()
    super.onDestroy()
  }
}
