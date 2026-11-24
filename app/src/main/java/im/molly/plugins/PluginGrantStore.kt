package im.molly.plugins

import android.content.Context

/**
 * Main-process-only store for per-plugin permission grants.
 * Never accessible from `:plugin_sandbox`.
 */
object PluginGrantStore {

  private const val PREFS_NAME = "molly_plugin_grants"
  internal const val KEY_PERMS_PREFIX = "plugin_perms_"

  @Volatile
  private var signedStore: SignedGrantStore? = null

  internal fun get(context: Context): SignedGrantStore {
    PluginProcessGuard.assertMainProcess(context, "PluginGrantStore")
    signedStore?.let { return it }
    synchronized(this) {
      signedStore?.let { return it }
      val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      val store = SignedGrantStore(prefs) { key -> key.startsWith(KEY_PERMS_PREFIX) }
      signedStore = store
      return store
    }
  }

  internal fun permsKey(pluginId: String): String = KEY_PERMS_PREFIX + pluginId
}
