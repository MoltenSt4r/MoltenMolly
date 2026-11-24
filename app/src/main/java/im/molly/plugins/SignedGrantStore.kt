package im.molly.plugins

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.signal.core.util.logging.Log
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Signs and verifies plugin grant records using an AndroidKeyStore HMAC-SHA256 key
 * so that grant entries in SharedPreferences cannot be silently tampered with.
 */
internal class SignedGrantStore(
  private val delegate: android.content.SharedPreferences,
  private val isGrantKey: (String) -> Boolean
) {
  companion object {
    private val TAG = Log.tag(SignedGrantStore::class.java)
    private const val KEY_ALIAS = "molly_plugin_grants_v1"
    private const val SIG_PREFIX = "sig:"
  }

  private val verifiedCache = ConcurrentHashMap<String, Boolean>()

  @Volatile
  private var cachedKey: SecretKey? = null

  @Volatile
  private var keystoreUnavailable = false

  private fun getOrCreateKey(): SecretKey? {
    cachedKey?.let { return it }
    if (keystoreUnavailable) return null
    synchronized(this) {
      cachedKey?.let { return it }
      if (keystoreUnavailable) return null
      return try {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = if (keyStore.containsAlias(KEY_ALIAS)) {
          keyStore.getKey(KEY_ALIAS, null) as SecretKey
        } else {
          val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
          generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN).build())
          generator.generateKey()
        }
        cachedKey = key
        key
      } catch (t: Throwable) {
        keystoreUnavailable = true
        Log.w(TAG, "AndroidKeyStore unavailable; falling back to unsigned grant store", t)
        null
      }
    }
  }

  private fun sign(name: String, value: String): String? {
    val key = getOrCreateKey() ?: return null
    return try {
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(key)
      val payload = "$name\u0000$value".toByteArray(StandardCharsets.UTF_8)
      Base64.encodeToString(mac.doFinal(payload), Base64.NO_WRAP)
    } catch (t: Throwable) {
      Log.w(TAG, "Failed to sign grant key: $name", t)
      null
    }
  }

  private fun verify(name: String, value: String): Boolean {
    if (!isGrantKey(name)) return true
    if (getOrCreateKey() == null) return true
    val signature = delegate.getString(SIG_PREFIX + name, null) ?: return false
    val cacheKey = "$name\u0000$value\u0000$signature"
    verifiedCache[cacheKey]?.let { return it }
    val expected = sign(name, value) ?: return false
    val ok = MessageDigest.isEqual(
      expected.toByteArray(StandardCharsets.UTF_8),
      signature.toByteArray(StandardCharsets.UTF_8)
    )
    verifiedCache[cacheKey] = ok
    if (!ok) {
      Log.w(TAG, "Grant signature mismatch for key $name; treating as ungranted")
    }
    return ok
  }

  fun contains(key: String): Boolean {
    val raw = delegate.getString(key, null) ?: return false
    return verify(key, raw)
  }

  fun getString(key: String): String? {
    val raw = delegate.getString(key, null) ?: return null
    return if (verify(key, raw)) raw else null
  }

  fun putString(key: String, value: String) {
    val editor = delegate.edit().putString(key, value)
    if (isGrantKey(key)) {
      val sig = sign(key, value)
      if (sig != null) {
        editor.putString(SIG_PREFIX + key, sig)
        verifiedCache["$key\u0000$value\u0000$sig"] = true
      }
    }
    editor.apply()
  }

  fun remove(key: String) {
    delegate.edit()
      .remove(key)
      .remove(SIG_PREFIX + key)
      .apply()
  }
}
