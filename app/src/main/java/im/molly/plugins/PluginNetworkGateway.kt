package im.molly.plugins

import android.content.Context
import im.molly.plugins.ipc.PluginHttpRequestDto
import im.molly.plugins.ipc.PluginHttpResponseDto
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.net.Networking
import java.util.concurrent.TimeUnit

/**
 * Main-process-only HTTP gateway for plugins.
 * All plugin network requests are routed through Molly's [Networking] proxy/Tor layer
 * ([Networking.socketFactory], [Networking.proxySelectorForSocks], [Networking.dns])
 * and fail closed when Molly networking is disabled or the proxy is not ready.
 */
object PluginNetworkGateway {

  private val TAG = Log.tag(PluginNetworkGateway::class.java)
  private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024 // 2 MiB Binder-safe cap

  private val baseClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .socketFactory(Networking.socketFactory)
      .proxySelector(Networking.proxySelectorForSocks)
      .dns(Networking.dns)
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(20, TimeUnit.SECONDS)
      .writeTimeout(20, TimeUnit.SECONDS)
      .followRedirects(true)
      .followSslRedirects(true)
      .build()
  }

  @JvmStatic
  fun execute(context: Context, requestDto: PluginHttpRequestDto): PluginHttpResponseDto {
    PluginProcessGuard.assertMainProcess(context, "PluginNetworkGateway")

    if (!Networking.isEnabled) {
      return PluginHttpResponseDto(
        success = false,
        statusCode = 0,
        errorMessage = "Molly networking is disabled"
      )
    }

    val parsedUrl = requestDto.url.toHttpUrlOrNull()
      ?: return PluginHttpResponseDto(
        success = false,
        statusCode = 0,
        errorMessage = "Invalid URL: ${requestDto.url}"
      )

    if (parsedUrl.scheme != "https" && parsedUrl.scheme != "http") {
      return PluginHttpResponseDto(
        success = false,
        statusCode = 0,
        errorMessage = "Unsupported URL scheme: ${parsedUrl.scheme}"
      )
    }

    val method = requestDto.method.uppercase().ifBlank { "GET" }
    val bodyBytes = requestDto.body
    val requestBody = if (bodyBytes != null && method != "GET" && method != "HEAD") {
      bodyBytes.toRequestBody(requestDto.contentType?.toMediaTypeOrNull())
    } else {
      null
    }

    val timeoutMs = requestDto.timeoutMs.coerceIn(1_000, 60_000).toLong()
    val client = if (timeoutMs == 15_000L) {
      baseClient
    } else {
      baseClient.newBuilder()
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()
    }

    val request = Request.Builder()
      .url(parsedUrl)
      .headers(requestDto.headers.toHeaders())
      .method(method, requestBody)
      .build()

    val isProxied = Networking.socksProxy != null

    return try {
      client.newCall(request).execute().use { response ->
        val rawBytes = response.body?.let { body ->
          val source = body.source()
          source.request((MAX_RESPONSE_BYTES + 1).toLong())
          val buffer = source.buffer
          if (buffer.size > MAX_RESPONSE_BYTES) {
            return PluginHttpResponseDto(
              success = false,
              statusCode = response.code,
              errorMessage = "Response exceeds $MAX_RESPONSE_BYTES byte sandbox limit",
              proxied = isProxied
            )
          }
          buffer.readByteArray()
        }

        val responseHeaders = LinkedHashMap<String, String>()
        for (i in 0 until response.headers.size) {
          responseHeaders[response.headers.name(i)] = response.headers.value(i)
        }

        PluginHttpResponseDto(
          success = response.isSuccessful,
          statusCode = response.code,
          headers = responseHeaders,
          body = rawBytes,
          proxied = isProxied
        )
      }
    } catch (t: Throwable) {
      Log.w(TAG, "Plugin HTTP request failed for ${parsedUrl.host}", t)
      PluginHttpResponseDto(
        success = false,
        statusCode = 0,
        errorMessage = t.message ?: t.javaClass.simpleName,
        proxied = isProxied
      )
    }
  }
}
