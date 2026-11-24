package im.molly.plugins

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

/**
 * In-memory ring buffer audit journal recording every allowed and denied plugin IPC call
 * enforced on the main-process side.
 */
object PluginAuditJournal {

  private const val LIMIT = 500
  private const val MAX_DETAIL_LEN = 200

  data class Entry(
    val timestampMs: Long,
    val pluginId: String,
    val event: String,
    val category: String,
    val detail: String,
    val allowed: Boolean,
    var count: Int = 1
  )

  private val entries = ArrayDeque<Entry>(LIMIT)
  private val profile = HashMap<String, HashMap<String, IntArray>>()

  private val _revision = MutableStateFlow(0L)
  val revision: StateFlow<Long> = _revision.asStateFlow()

  @JvmStatic
  fun record(
    pluginId: String?,
    event: String,
    category: String,
    detail: String?,
    allowed: Boolean
  ) {
    if (pluginId.isNullOrBlank()) return
    val trimmed = (detail ?: "").let {
      if (it.length > MAX_DETAIL_LEN) it.substring(0, MAX_DETAIL_LEN) else it
    }
    val now = System.currentTimeMillis()
    synchronized(entries) {
      val last = entries.peekLast()
      if (last != null &&
        last.pluginId == pluginId &&
        last.event == event &&
        last.category == category &&
        last.detail == trimmed &&
        last.allowed == allowed
      ) {
        last.count += 1
      } else {
        if (entries.size >= LIMIT) {
          entries.pollFirst()
        }
        entries.addLast(Entry(now, pluginId, event, category, trimmed, allowed, 1))
      }

      val perPlugin = profile.getOrPut(pluginId) { HashMap() }
      val counters = perPlugin.getOrPut(category) { IntArray(2) }
      counters[if (allowed) 0 else 1] += 1
    }
    _revision.value = _revision.value + 1L
  }

  @JvmStatic
  fun entries(pluginId: String? = null): List<Entry> {
    synchronized(entries) {
      return entries
        .filter { pluginId == null || it.pluginId == pluginId }
        .map { it.copy() }
    }
  }

  /** Returns map of permission category -> [allowedCount, deniedCount]. */
  @JvmStatic
  fun profile(pluginId: String): Map<String, IntArray> {
    synchronized(entries) {
      val perPlugin = profile[pluginId] ?: return emptyMap()
      return perPlugin.mapValues { (_, counts) -> intArrayOf(counts[0], counts[1]) }
    }
  }

  @JvmStatic
  fun forget(pluginId: String) {
    synchronized(entries) {
      profile.remove(pluginId)
      entries.removeIf { it.pluginId == pluginId }
    }
    _revision.value = _revision.value + 1L
  }

  @JvmStatic
  fun clear(pluginId: String? = null) {
    if (pluginId != null) {
      forget(pluginId)
      return
    }
    synchronized(entries) {
      profile.clear()
      entries.clear()
    }
    _revision.value = _revision.value + 1L
  }
}
