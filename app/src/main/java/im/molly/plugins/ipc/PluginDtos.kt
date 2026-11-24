package im.molly.plugins.ipc

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class PluginManifestDto(
  val id: String,
  val name: String,
  val version: String,
  val author: String,
  val description: String,
  val declaredPermissions: List<String> = emptyList(),
  val enabled: Boolean = true
) : Parcelable

@Parcelize
data class PluginResultDto(
  val success: Boolean,
  val message: String = "",
  val errorCode: String = ""
) : Parcelable {
  companion object {
    @JvmStatic
    fun ok(message: String = ""): PluginResultDto = PluginResultDto(true, message, "")

    @JvmStatic
    fun denied(permission: String, detail: String = ""): PluginResultDto =
      PluginResultDto(false, detail.ifEmpty { "Permission denied: $permission" }, "PERMISSION_DENIED")

    @JvmStatic
    fun error(code: String, message: String): PluginResultDto =
      PluginResultDto(false, message, code)
  }
}

@Parcelize
data class PluginMessageDto(
  val messageId: Long,
  val threadId: Long,
  val senderId: String,
  val senderName: String,
  val body: String,
  val timestampMs: Long,
  val isOutgoing: Boolean
) : Parcelable

@Parcelize
data class PluginContactDto(
  val recipientId: String,
  val displayName: String,
  val handle: String,
  val isGroup: Boolean
) : Parcelable

@Parcelize
data class PluginHttpRequestDto(
  val url: String,
  val method: String = "GET",
  val headers: Map<String, String> = emptyMap(),
  val body: ByteArray? = null,
  val contentType: String? = null,
  val timeoutMs: Int = 15_000
) : Parcelable

@Parcelize
data class PluginHttpResponseDto(
  val success: Boolean,
  val statusCode: Int,
  val headers: Map<String, String> = emptyMap(),
  val body: ByteArray? = null,
  val errorMessage: String = "",
  val proxied: Boolean = false
) : Parcelable

@Parcelize
data class PluginFileResultDto(
  val success: Boolean,
  val data: ByteArray? = null,
  val errorMessage: String = ""
) : Parcelable

@Parcelize
data class PluginHealthDto(
  val pid: Int,
  val processName: String,
  val loadedPluginIds: List<String>,
  val uptimeMs: Long
) : Parcelable
