package im.molly.plugins

import android.content.Context
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Granular per-plugin permission model for Molly.
 * Enforces strict default-deny on the main-process side before any IPC action.
 */
object PluginPermissions {

  /** Render plugin UI notices, settings, and dialogs. Always granted and cannot be revoked. */
  const val UI = "ui"

  /** Read messages and thread summaries via IPC DTOs. */
  const val MESSAGES_READ = "messages.read"

  /** Send messages via IPC after explicit user grant. */
  const val MESSAGES_SEND = "messages.send"

  /** Read contact/recipient summaries via IPC DTOs. */
  const val CONTACTS_READ = "contacts.read"

  /** Perform HTTP requests routed through Molly's proxy/Tor layer. */
  const val NETWORK = "network"

  /** Read and write files scoped to the plugin's sandboxed directory. */
  const val FILES = "files"

  /** Read and write plugin/app settings exposed over IPC. */
  const val SETTINGS = "settings"

  @JvmField
  val ALL: List<String> = Collections.unmodifiableList(
    listOf(UI, MESSAGES_READ, MESSAGES_SEND, CONTACTS_READ, NETWORK, FILES, SETTINGS)
  )

  @JvmField
  val REQUESTABLE: List<String> = Collections.unmodifiableList(
    listOf(MESSAGES_READ, MESSAGES_SEND, CONTACTS_READ, NETWORK, FILES, SETTINGS)
  )

  private val KNOWN: Set<String> = ALL.toSet()

  private val cache = ConcurrentHashMap<String, Set<String>>()

  @JvmStatic
  fun isKnown(permission: String?): Boolean = permission != null && permission in KNOWN

  @JvmStatic
  fun isDangerous(permission: String): Boolean =
    permission == MESSAGES_SEND || permission == NETWORK || permission == FILES

  @JvmStatic
  fun describe(permission: String?): String = when (permission) {
    UI -> "User interface & notices"
    MESSAGES_READ -> "Read messages"
    MESSAGES_SEND -> "Send messages"
    CONTACTS_READ -> "Read contacts"
    NETWORK -> "Network access (via Molly proxy/Tor)"
    FILES -> "Plugin file storage"
    SETTINGS -> "Read & write settings"
    null -> "Unknown permission"
    else -> permission
  }

  @JvmStatic
  fun sanitize(permissions: Collection<String>?): List<String> {
    if (permissions.isNullOrEmpty()) return emptyList()
    return ALL.filter { it in permissions }
  }

  @JvmStatic
  fun getGranted(context: Context, pluginId: String): Set<String> {
    PluginProcessGuard.assertMainProcess(context, "PluginPermissions")
    cache[pluginId]?.let { return it }

    val store = PluginGrantStore.get(context)
    val raw = store.getString(PluginGrantStore.permsKey(pluginId))
    val granted = LinkedHashSet<String>().apply {
      add(UI)
      if (!raw.isNullOrBlank()) {
        raw.split(',')
          .map { it.trim() }
          .filter { it.isNotEmpty() && isKnown(it) }
          .forEach { add(it) }
      }
    }
    val immutable = Collections.unmodifiableSet(granted)
    cache[pluginId] = immutable
    return immutable
  }

  @JvmStatic
  fun setGranted(context: Context, pluginId: String, permissions: Collection<String>) {
    PluginProcessGuard.assertMainProcess(context, "PluginPermissions")
    val clean = sanitize(permissions).filter { it != UI }
    PluginGrantStore.get(context).putString(
      PluginGrantStore.permsKey(pluginId),
      clean.joinToString(",")
    )
    cache.remove(pluginId)
  }

  @JvmStatic
  fun grant(context: Context, pluginId: String, permission: String) {
    if (!isKnown(permission)) return
    val updated = LinkedHashSet(getGranted(context, pluginId))
    updated.add(permission)
    setGranted(context, pluginId, updated)
  }

  @JvmStatic
  fun revoke(context: Context, pluginId: String, permission: String) {
    if (permission == UI) return
    val updated = LinkedHashSet(getGranted(context, pluginId))
    updated.remove(permission)
    setGranted(context, pluginId, updated)
  }

  @JvmStatic
  fun clear(context: Context, pluginId: String) {
    PluginProcessGuard.assertMainProcess(context, "PluginPermissions")
    PluginGrantStore.get(context).remove(PluginGrantStore.permsKey(pluginId))
    cache.remove(pluginId)
  }

  @JvmStatic
  fun has(context: Context, pluginId: String?, permission: String?): Boolean {
    if (permission == UI) return true
    if (pluginId.isNullOrBlank() || !isKnown(permission)) return false
    return permission in getGranted(context, pluginId)
  }

  /**
   * Checks permission on the main-process side and records the outcome in [PluginAuditJournal].
   */
  @JvmStatic
  fun checkAndAudit(
    context: Context,
    pluginId: String,
    permission: String,
    action: String,
    detail: String = ""
  ): Boolean {
    val allowed = has(context, pluginId, permission)
    PluginAuditJournal.record(
      pluginId = pluginId,
      event = action,
      category = permission,
      detail = detail,
      allowed = allowed
    )
    return allowed
  }
}
