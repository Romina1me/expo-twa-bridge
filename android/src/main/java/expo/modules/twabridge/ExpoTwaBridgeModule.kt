package expo.modules.twabridge

import android.net.Uri
import androidx.browser.trusted.TrustedWebActivityIntentBuilder
import com.google.androidbrowserhelper.trusted.TwaLauncher
import com.google.androidbrowserhelper.trusted.TwaProviderPicker
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class ExpoTwaBridgeModule : Module() {
  private var launcher: TwaLauncher? = null

  override fun definition() = ModuleDefinition {
    Name("ExpoTwaBridge")

    AsyncFunction("launch") { url: String, promise: Promise ->
      val activity = appContext.currentActivity
      if (activity == null) {
        promise.reject(
          "ERR_TWA_NO_ACTIVITY",
          "Cannot launch a Trusted Web Activity without a foreground Activity.",
          null
        )
        return@AsyncFunction
      }

      val uri = parseHttpsUrl(url)
      if (uri == null) {
        promise.reject(
          "ERR_TWA_INVALID_URL",
          "Only absolute HTTPS URLs can be opened in a Trusted Web Activity.",
          null
        )
        return@AsyncFunction
      }

      // Custom Tabs service binding and Activity launching must happen on the UI thread.
      activity.runOnUiThread {
        try {
          val provider = TwaProviderPicker.pickProvider(activity.packageManager)
          if (provider.launchMode != TwaProviderPicker.LaunchMode.TRUSTED_WEB_ACTIVITY) {
            promise.reject(
              "ERR_TWA_NO_BROWSER",
              "Chrome or another browser supporting Trusted Web Activities is not installed.",
              null
            )
            return@runOnUiThread
          }

          launcher?.destroy()

          val twaLauncher = TwaLauncher(activity, provider.provider)
          launcher = twaLauncher

          twaLauncher.launch(
            TrustedWebActivityIntentBuilder(uri),
            null,
            null,
            { promise.resolve(null) },
            TwaLauncher.FallbackStrategy { _, _, _, _ ->
              promise.reject(
                "ERR_TWA_LAUNCH_FAILED",
                "The Trusted Web Activity could not be launched.",
                null
              )
            }
          )
        } catch (error: Exception) {
          promise.reject(
            "ERR_TWA_LAUNCH_FAILED",
            error.message ?: "The Trusted Web Activity could not be launched.",
            error
          )
        }
      }
    }

    OnDestroy {
      launcher?.destroy()
      launcher = null
    }
  }

  private fun parseHttpsUrl(url: String): Uri? {
    val uri = Uri.parse(url)
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
      return null
    }
    return uri
  }
}
