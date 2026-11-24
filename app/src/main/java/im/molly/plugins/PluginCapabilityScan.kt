package im.molly.plugins

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.signal.core.util.logging.Log

/**
 * Static capability scanner for plugins on install.
 * Detects requested/used capabilities and obfuscation indicators without executing plugin code,
 * and persists the scan evidence so the UI can explain why permissions are requested.
 */
object PluginCapabilityScan {

  private val TAG = Log.tag(PluginCapabilityScan::class.java)
  private const val PREFS_NAME = "molly_plugin_scans"
  private const val KEY_PREFIX = "scan_"
  const val KEY_OBFUSCATION = "obfuscation"

  data class ScanResult(
    val capabilities: Map<String, List<String>>,
    val obfuscationEvidence: List<String>
  ) {
    val isObfuscated: Boolean get() = obfuscationEvidence.isNotEmpty()
    val detectedPermissions: List<String>
      get() = PluginPermissions.ALL.filter { it != PluginPermissions.UI && capabilities.containsKey(it) }
  }

  private val PATTERNS: Map<String, List<Pair<String, Regex>>> = mapOf(
    PluginPermissions.NETWORK to listOf(
      "URL literal" to Regex("""https?://[^\s'"]+""", RegexOption.IGNORE_CASE),
      "http/fetch call" to Regex("""\b(fetch|httpGet|httpPost|executeHttpRequest|XMLHttpRequest|okhttp|urllib|requests)\b"""),
      "socket/websocket" to Regex("""\b(WebSocket|Socket|InetAddress)\b""")
    ),
    PluginPermissions.MESSAGES_READ to listOf(
      "message read API" to Regex("""\b(readMessages|getMessages|onMessage|messages\.read)\b""")
    ),
    PluginPermissions.MESSAGES_SEND to listOf(
      "message send API" to Regex("""\b(sendMessage|replyMessage|messages\.send)\b""")
    ),
    PluginPermissions.CONTACTS_READ to listOf(
      "contacts read API" to Regex("""\b(readContacts|getContacts|findContact|contacts\.read)\b""")
    ),
    PluginPermissions.FILES to listOf(
      "file I/O API" to Regex("""\b(readPluginFile|writePluginFile|readFile|writeFile|FileInputStream|FileOutputStream)\b""")
    ),
    PluginPermissions.SETTINGS to listOf(
      "settings API" to Regex("""\b(getSetting|setSetting|SharedPreferences)\b""")
    ),
    KEY_OBFUSCATION to listOf(
      "dynamic code execution" to Regex("""\b(eval|exec|Function|__import__|DexClassLoader|Class\.forName)\s*\("""),
      "encoded payload" to Regex("""\b(atob|fromCharCode|base64\.b64decode|Base64\.decode)\b""")
    )
  )

  @JvmStatic
  fun scan(sourceCode: String, declaredPermissions: Collection<String> = emptyList()): ScanResult {
    val found = LinkedHashMap<String, MutableList<String>>()

    for (perm in PluginPermissions.sanitize(declaredPermissions)) {
      if (perm != PluginPermissions.UI) {
        found.getOrPut(perm) { mutableListOf() }.add("Declared in manifest")
      }
    }

    val lines = sourceCode.lines()
    for ((index, line) in lines.withIndex()) {
      val lineNum = index + 1
      for ((key, matchers) in PATTERNS) {
        for ((label, regex) in matchers) {
          val match = regex.find(line)
          if (match != null) {
            val evidence = "L$lineNum ($label): ${match.value.take(60)}"
            val list = found.getOrPut(key) { mutableListOf() }
            if (list.size < 10 && evidence !in list) {
              list.add(evidence)
            }
          }
        }
      }
    }

    val obfuscation = found.remove(KEY_OBFUSCATION)?.toList().orEmpty()
    val immutableCaps = found.mapValues { it.value.toList() }
    return ScanResult(immutableCaps, obfuscation)
  }

  @JvmStatic
  fun store(context: Context, pluginId: String, result: ScanResult) {
    PluginProcessGuard.assertMainProcess(context, "PluginCapabilityScan")
    try {
      val root = JSONObject()
      for ((key, evidence) in result.capabilities) {
        root.put(key, JSONArray(evidence))
      }
      if (result.obfuscationEvidence.isNotEmpty()) {
        root.put(KEY_OBFUSCATION, JSONArray(result.obfuscationEvidence))
      }
      context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_PREFIX + pluginId, root.toString())
        .apply()
    } catch (t: Throwable) {
      Log.w(TAG, "Failed to store capability scan for $pluginId", t)
    }
  }

  @JvmStatic
  fun load(context: Context, pluginId: String): ScanResult {
    PluginProcessGuard.assertMainProcess(context, "PluginCapabilityScan")
    val raw = context.applicationContext
      .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .getString(KEY_PREFIX + pluginId, null)
      ?: return ScanResult(emptyMap(), emptyList())

    return try {
      val root = JSONObject(raw)
      val caps = LinkedHashMap<String, List<String>>()
      var obfuscation: List<String> = emptyList()
      val keys = root.keys()
      while (keys.hasNext()) {
        val key = keys.next()
        val array = root.optJSONArray(key) ?: continue
        val items = (0 until array.length()).mapNotNull { i ->
          array.optString(i).takeIf { it.isNotEmpty() }
        }
        if (key == KEY_OBFUSCATION) {
          obfuscation = items
        } else if (PluginPermissions.isKnown(key)) {
          caps[key] = items
        }
      }
      ScanResult(caps, obfuscation)
    } catch (t: Throwable) {
      Log.w(TAG, "Failed to parse capability scan for $pluginId", t)
      ScanResult(emptyMap(), emptyList())
    }
  }

  @JvmStatic
  fun clear(context: Context, pluginId: String) {
    PluginProcessGuard.assertMainProcess(context, "PluginCapabilityScan")
    context.applicationContext
      .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .remove(KEY_PREFIX + pluginId)
      .apply()
  }
}
