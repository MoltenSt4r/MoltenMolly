package im.molly.plugins

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File

/**
 * Enforces strict process boundaries between the main Molly process (which holds
 * SQLCipher passphrase, MasterSecret, KeyStore, SignalDatabase, and SignalProtocolStore)
 * and the isolated `:plugin_sandbox` process.
 */
object PluginProcessGuard {

  const val SANDBOX_PROCESS_SUFFIX = ":plugin_sandbox"

  @Volatile
  private var cachedProcessName: String? = null

  @JvmStatic
  fun getProcessName(context: Context): String {
    cachedProcessName?.let { return it }

    val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      Application.getProcessName()
    } else {
      readCmdlineProcessName() ?: resolveViaActivityManager(context)
    }

    if (!resolved.isNullOrBlank()) {
      cachedProcessName = resolved
      return resolved
    }
    return context.packageName
  }

  @JvmStatic
  fun isSandboxProcess(context: Context): Boolean {
    return getProcessName(context).endsWith(SANDBOX_PROCESS_SUFFIX)
  }

  @JvmStatic
  fun isMainProcess(context: Context): Boolean {
    return !isSandboxProcess(context)
  }

  @JvmStatic
  fun assertMainProcess(context: Context, component: String) {
    check(isMainProcess(context)) {
      "$component must only run in the main Molly process, never in $SANDBOX_PROCESS_SUFFIX"
    }
  }

  @JvmStatic
  fun assertSandboxProcess(context: Context, component: String) {
    check(isSandboxProcess(context)) {
      "$component must only run in the $SANDBOX_PROCESS_SUFFIX process"
    }
  }

  private fun readCmdlineProcessName(): String? {
    return try {
      File("/proc/self/cmdline").readText().trim('\u0000', ' ', '\n', '\r')
        .takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
      null
    }
  }

  private fun resolveViaActivityManager(context: Context): String? {
    return try {
      val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
      val pid = Process.myPid()
      am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
    } catch (_: Throwable) {
      null
    }
  }
}
