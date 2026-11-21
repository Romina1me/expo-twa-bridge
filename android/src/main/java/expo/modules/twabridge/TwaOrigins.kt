package expo.modules.twabridge

import android.net.Uri

internal const val TAG = "ExpoTwaBridge"

/**
 * Parses an absolute HTTPS URL.
 */
internal fun parseHttpsUrl(url: String): Uri? {
  val uri = Uri.parse(url)
  if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
    return null
  }
  return uri
}

/**
 * Normalizes `scheme://host[:port]` and returns null when the value is not an
 * absolute HTTPS origin.
 */
internal fun parseOrigin(value: String): String? {
  val uri = Uri.parse(value)
  if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
    return null
  }
  if (!uri.path.isNullOrEmpty() && uri.path != "/") {
    return null
  }
  if (uri.query != null || uri.fragment != null) {
    return null
  }
  return originOf(uri)
}

/**
 * Returns the normalized origin of an HTTPS URI, or null when it is not one.
 */
internal fun originOf(uri: Uri): String? {
  if (!uri.scheme.equals("https", ignoreCase = true)) {
    return null
  }
  val host = uri.host
  if (host.isNullOrBlank()) {
    return null
  }
  return buildString {
    append("https://")
    append(host.lowercase())
    if (uri.port != -1) {
      append(":")
      append(uri.port)
    }
  }
}
