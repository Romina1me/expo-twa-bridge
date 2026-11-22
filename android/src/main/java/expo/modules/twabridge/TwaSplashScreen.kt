package expo.modules.twabridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.widget.ImageView
import androidx.browser.customtabs.CustomTabsSession
import androidx.browser.customtabs.TrustedWebUtils
import androidx.browser.trusted.splashscreens.SplashScreenParamKey
import androidx.browser.trusted.splashscreens.SplashScreenVersion
import java.io.File
import java.io.FileOutputStream
import java.net.URL

internal data class SplashScreenConfig(
  val image: String,
  val backgroundColor: Int
)

/**
 * Loads a splash image, transfers it to the browser through the FileProvider
 * expected by [TrustedWebUtils.transferSplashImage], and builds the
 * [SplashScreenParamKey] bundle consumed by
 * `TrustedWebActivityIntentBuilder.setSplashScreenParams`.
 *
 * The provider itself is declared by the package's config plugin
 * (`<applicationId>.twa_splash`). When the image cannot be prepared, the
 * callback receives null and the launcher proceeds without a splash screen.
 */
internal object TwaSplashScreen {
  private const val TAG = "ExpoTwaBridge"
  private const val FOLDER_NAME = "twa_splash"
  private const val FILE_NAME = "splash_image.png"
  private const val AUTHORITY_SUFFIX = ".twa_splash"
  private const val NETWORK_TIMEOUT_MS = 5_000
  private const val FADE_OUT_DURATION_MS = 300

  fun areSplashScreensSupported(context: Context, providerPackage: String): Boolean =
    runCatching {
      TrustedWebUtils.areSplashScreensSupported(
        context,
        providerPackage,
        SplashScreenVersion.V1
      )
    }.getOrDefault(false)

  /**
   * Prepares the splash screen off the main thread. [onReady] is always called
   * on the main thread; it receives null when the splash screen cannot be used.
   */
  fun prepare(
    context: Context,
    providerPackage: String,
    session: CustomTabsSession,
    config: SplashScreenConfig,
    onReady: (Bundle?) -> Unit
  ) {
    val appContext = context.applicationContext
    val mainHandler = Handler(Looper.getMainLooper())

    Thread {
      val params = runCatching {
        val bitmap = decodeBitmap(appContext, config.image)
        if (bitmap == null) {
          Log.w(TAG, "Could not decode the splash screen image: ${config.image}")
          return@runCatching null
        }

        val directory = File(appContext.filesDir, FOLDER_NAME)
        if (!directory.exists() && !directory.mkdirs()) {
          Log.w(TAG, "Could not create the splash screen directory.")
          bitmap.recycle()
          return@runCatching null
        }

        val file = File(directory, FILE_NAME)
        FileOutputStream(file).use { output ->
          bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        bitmap.recycle()

        val transferred = TrustedWebUtils.transferSplashImage(
          appContext,
          file,
          appContext.packageName + AUTHORITY_SUFFIX,
          providerPackage,
          session
        )
        if (!transferred) {
          Log.w(TAG, "The browser rejected the splash screen image transfer.")
          return@runCatching null
        }

        buildParams(config)
      }.getOrElse { error ->
        Log.w(TAG, "Could not prepare the splash screen.", error)
        null
      }

      mainHandler.post { onReady(params) }
    }.start()
  }

  private fun buildParams(config: SplashScreenConfig): Bundle =
    Bundle().apply {
      putString(SplashScreenParamKey.KEY_VERSION, SplashScreenVersion.V1)
      putInt(SplashScreenParamKey.KEY_FADE_OUT_DURATION_MS, FADE_OUT_DURATION_MS)
      putInt(SplashScreenParamKey.KEY_BACKGROUND_COLOR, config.backgroundColor)
      // Show the image at its natural size, centered, without stretching it.
      putInt(SplashScreenParamKey.KEY_SCALE_TYPE, ImageView.ScaleType.CENTER.ordinal)
    }

  private fun decodeBitmap(context: Context, source: String): Bitmap? {
    val uri = Uri.parse(source)
    return when {
      source.startsWith("data:", ignoreCase = true) -> decodeDataUri(source)
      uri.scheme.equals("http", ignoreCase = true) ||
        uri.scheme.equals("https", ignoreCase = true) -> {
        val connection = URL(source).openConnection().apply {
          connectTimeout = NETWORK_TIMEOUT_MS
          readTimeout = NETWORK_TIMEOUT_MS
        }
        connection.getInputStream().use { BitmapFactory.decodeStream(it) }
      }
      uri.scheme.equals("file", ignoreCase = true) &&
        (uri.path ?: "").startsWith("/android_asset/") -> {
        val path = uri.path!!.removePrefix("/android_asset/")
        context.assets.open(path).use { BitmapFactory.decodeStream(it) }
      }
      !uri.scheme.isNullOrEmpty() -> {
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
      }
      else -> decodeResource(context, source)
    }
  }

  private fun decodeDataUri(source: String): Bitmap? {
    val comma = source.indexOf(',')
    if (comma < 0) {
      return null
    }
    val header = source.substring(0, comma)
    val payload = source.substring(comma + 1)
    val bytes = if (header.contains(";base64", ignoreCase = true)) {
      Base64.decode(payload, Base64.DEFAULT)
    } else {
      Uri.decode(payload).toByteArray()
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
  }

  private fun decodeResource(context: Context, source: String): Bitmap? {
    val parts = source.split('/', limit = 2)
    val type = if (parts.size == 2) parts[0] else "drawable"
    val name = if (parts.size == 2) parts[1] else source
    val id = context.resources.getIdentifier(name, type, context.packageName)
    if (id == 0) {
      return null
    }
    return BitmapFactory.decodeResource(context.resources, id)
  }
}
