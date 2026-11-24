package im.molly.plugins

import android.content.Context
import im.molly.plugins.ipc.IPluginHost
import im.molly.plugins.ipc.PluginContactDto
import im.molly.plugins.ipc.PluginFileResultDto
import im.molly.plugins.ipc.PluginHttpRequestDto
import im.molly.plugins.ipc.PluginHttpResponseDto
import im.molly.plugins.ipc.PluginMessageDto
import im.molly.plugins.ipc.PluginResultDto
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.MessageTable
import org.thoughtcrime.securesms.database.RecipientTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.sms.MessageSender
import java.io.File

/**
 * Main-process Binder implementation of [IPluginHost].
 * Enforces per-plugin permissions from [PluginGrantStore] before every action,
 * logs all allowed/denied calls to [PluginAuditJournal], and returns strictly
 * sanitized DTOs (never exposing SQLCipher passphrase, MasterSecret, KeyStore,
 * SignalDatabase, or SignalProtocolStore).
 */
class PluginHostBinder(
  private val context: Context,
  private val onUiNotice: (PluginUiNotice) -> Unit
) : IPluginHost.Stub() {

  companion object {
    private val TAG = Log.tag(PluginHostBinder::class.java)
    private const val MAX_FILE_BYTES = 1024 * 1024 // 1 MiB
    private const val MAX_QUERY_LIMIT = 100
  }

  init {
    PluginProcessGuard.assertMainProcess(context, "PluginHostBinder")
  }

  override fun hasPermission(pluginId: String, permission: String): Boolean {
    return PluginPermissions.has(context, pluginId, permission)
  }

  override fun readMessages(
    pluginId: String,
    threadId: Long,
    limit: Int,
    beforeTimestampMs: Long
  ): List<PluginMessageDto> {
    if (!PluginPermissions.checkAndAudit(
        context,
        pluginId,
        PluginPermissions.MESSAGES_READ,
        "readMessages",
        "thread=$threadId limit=$limit"
      )
    ) {
      return emptyList()
    }

    if (KeyCachingService.isLocked()) {
      return emptyList()
    }

    val safeLimit = limit.coerceIn(1, MAX_QUERY_LIMIT).toLong()
    val result = ArrayList<PluginMessageDto>()
    try {
      SignalDatabase.messages.getConversation(threadId, 0L, safeLimit, "DESC", false).use { cursor ->
        MessageTable.mmsReaderFor(cursor).use { reader ->
          var record = reader.getNext()
          while (record != null) {
            if (beforeTimestampMs <= 0L || record.dateSent < beforeTimestampMs) {
              val sender = record.fromRecipient
              result.add(
                PluginMessageDto(
                  messageId = record.id,
                  threadId = record.threadId,
                  senderId = sender.id.serialize(),
                  senderName = sender.getDisplayName(context),
                  body = record.body,
                  timestampMs = record.dateSent,
                  isOutgoing = record.isOutgoing
                )
              )
            }
            record = reader.getNext()
          }
        }
      }
    } catch (t: Throwable) {
      Log.w(TAG, "readMessages failed for plugin=$pluginId", t)
    }
    return result
  }

  override fun sendMessage(
    pluginId: String,
    threadId: Long,
    body: String?,
    replyToMessageId: Long
  ): PluginResultDto {
    if (!PluginPermissions.checkAndAudit(
        context,
        pluginId,
        PluginPermissions.MESSAGES_SEND,
        "sendMessage",
        "thread=$threadId len=${body?.length ?: 0}"
      )
    ) {
      return PluginResultDto.denied(PluginPermissions.MESSAGES_SEND)
    }

    if (body.isNullOrBlank()) {
      return PluginResultDto.error("INVALID_ARGUMENT", "Message body cannot be empty")
    }
    if (KeyCachingService.isLocked()) {
      return PluginResultDto.error("LOCKED", "Molly database is locked")
    }

    return try {
      val recipient: Recipient = SignalDatabase.threads.getRecipientForThreadId(threadId)
        ?: return PluginResultDto.error("NOT_FOUND", "Unknown threadId: $threadId")
      val outgoing = OutgoingMessage.text(recipient, body, recipient.expiresInSeconds * 1000L, System.currentTimeMillis())
      val messageId = MessageSender.send(context, outgoing, threadId, MessageSender.SendType.SIGNAL, null, null)
      PluginResultDto.ok("messageId=$messageId")
    } catch (t: Throwable) {
      Log.w(TAG, "sendMessage failed for plugin=$pluginId", t)
      PluginResultDto.error("SEND_FAILED", t.message ?: "Send failed")
    }
  }

  override fun readContacts(
    pluginId: String,
    query: String?,
    limit: Int
  ): List<PluginContactDto> {
    if (!PluginPermissions.checkAndAudit(
        context,
        pluginId,
        PluginPermissions.CONTACTS_READ,
        "readContacts",
        "query=${query.orEmpty().take(32)} limit=$limit"
      )
    ) {
      return emptyList()
    }

    if (KeyCachingService.isLocked()) {
      return emptyList()
    }

    val safeLimit = limit.coerceIn(1, MAX_QUERY_LIMIT)
    val result = ArrayList<PluginContactDto>()
    try {
      val cursor = if (query.isNullOrBlank()) {
        SignalDatabase.recipients.getSignalContacts(RecipientTable.IncludeSelfMode.Exclude)
      } else {
        SignalDatabase.recipients.queryAllContacts(query, RecipientTable.IncludeSelfMode.Exclude)
      }
      cursor?.use { c ->
        val idCol = c.getColumnIndex(RecipientTable.ID)
        while (c.moveToNext() && result.size < safeLimit) {
          if (idCol < 0) continue
          val id = c.getLong(idCol)
          val recipient = Recipient.resolved(org.thoughtcrime.securesms.recipients.RecipientId.from(id))
          result.add(
            PluginContactDto(
              recipientId = recipient.id.serialize(),
              displayName = recipient.getDisplayName(context),
              handle = recipient.e164.orElse(recipient.username.orElse("")),
              isGroup = recipient.isGroup
            )
          )
        }
      }
    } catch (t: Throwable) {
      Log.w(TAG, "readContacts failed for plugin=$pluginId", t)
    }
    return result
  }

  override fun executeHttpRequest(
    pluginId: String,
    request: PluginHttpRequestDto?
  ): PluginHttpResponseDto {
    val targetUrl = request?.url.orEmpty()
    if (!PluginPermissions.checkAndAudit(
        context,
        pluginId,
        PluginPermissions.NETWORK,
        "executeHttpRequest",
        "${request?.method ?: "GET"} $targetUrl"
      )
    ) {
      return PluginHttpResponseDto(
        success = false,
        statusCode = 403,
        errorMessage = "Permission denied: ${PluginPermissions.NETWORK}"
      )
    }

    if (request == null) {
      return PluginHttpResponseDto(
        success = false,
        statusCode = 400,
        errorMessage = "Request cannot be null"
      )
    }

    return PluginNetworkGateway.execute(context, request)
  }

  override fun readPluginFile(pluginId: String, relativePath: String?): PluginFileResultDto {
    if (!PluginPermissions.checkAndAudit(
        context,
        pluginId,
        PluginPermissions.FILES,
        "readPluginFile",
        relativePath.orEmpty()
      )
    ) {
      return PluginFileResultDto(false, null, "Permission denied: ${PluginPermissions.FILES}")
    }

    val target = resolveSafePluginFile(pluginId, relativePath)
      ?: return PluginFileResultDto(false, null, "Invalid file path")

    return try {
      if (!target.exists() || !target.isFile) {
        PluginFileResultDto(false, null, "File not found")
      } else if (target.length() > MAX_FILE_BYTES) {
        PluginFileResultDto(false, null, "File exceeds $MAX_FILE_BYTES byte limit")
      } else {
        PluginFileResultDto(true, target.readBytes(), "")
      }
    } catch (t: Throwable) {
      PluginFileResultDto(false, null, t.message ?: "Read failed")
    }
  }

  override fun writePluginFile(
    pluginId: String,
    relativePath: String?,
    data: ByteArray?
  ): PluginResultDto {
    if (!PluginPermissions.checkAndAudit(
        context,
        pluginId,
        PluginPermissions.FILES,
        "writePluginFile",
        "$relativePath (${data?.size ?: 0} bytes)"
      )
    ) {
      return PluginResultDto.denied(PluginPermissions.FILES)
    }

    if (data == null || data.size > MAX_FILE_BYTES) {
      return PluginResultDto.error("INVALID_SIZE", "Data must be 0..$MAX_FILE_BYTES bytes")
    }

    val target = resolveSafePluginFile(pluginId, relativePath)
      ?: return PluginResultDto.error("INVALID_PATH", "Invalid file path")

    return try {
      target.parentFile?.mkdirs()
      target.writeBytes(data)
      PluginResultDto.ok()
    } catch (t: Throwable) {
      PluginResultDto.error("WRITE_FAILED", t.message ?: "Write failed")
    }
  }

  override fun getSetting(pluginId: String, key: String?, defaultValue: String?): String? {
    if (!PluginPermissions.checkAndAudit(
        context,
        pluginId,
        PluginPermissions.SETTINGS,
        "getSetting",
        key.orEmpty()
      )
    ) {
      return defaultValue
    }
    if (key.isNullOrBlank()) return defaultValue
    return context.applicationContext
      .getSharedPreferences("molly_plugin_kv_$pluginId", Context.MODE_PRIVATE)
      .getString(key, defaultValue)
  }

  override fun setSetting(pluginId: String, key: String?, value: String?): PluginResultDto {
    if (!PluginPermissions.checkAndAudit(
        context,
        pluginId,
        PluginPermissions.SETTINGS,
        "setSetting",
        key.orEmpty()
      )
    ) {
      return PluginResultDto.denied(PluginPermissions.SETTINGS)
    }
    if (key.isNullOrBlank()) {
      return PluginResultDto.error("INVALID_KEY", "Setting key cannot be blank")
    }
    context.applicationContext
      .getSharedPreferences("molly_plugin_kv_$pluginId", Context.MODE_PRIVATE)
      .edit()
      .putString(key, value)
      .apply()
    return PluginResultDto.ok()
  }

  override fun postUiNotice(pluginId: String, title: String?, message: String?): PluginResultDto {
    PluginPermissions.checkAndAudit(
      context,
      pluginId,
      PluginPermissions.UI,
      "postUiNotice",
      title.orEmpty()
    )
    onUiNotice(
      PluginUiNotice(
        pluginId = pluginId,
        title = title.orEmpty(),
        message = message.orEmpty(),
        timestampMs = System.currentTimeMillis()
      )
    )
    return PluginResultDto.ok()
  }

  private fun resolveSafePluginFile(pluginId: String, relativePath: String?): File? {
    if (pluginId.isBlank() || relativePath.isNullOrBlank()) return null
    val baseDir = File(context.filesDir, "plugins/data/$pluginId").canonicalFile
    val candidate = File(baseDir, relativePath).canonicalFile
    val basePath = baseDir.path.let { if (it.endsWith(File.separator)) it else it + File.separator }
    return if (candidate.path.startsWith(basePath)) candidate else null
  }
}
