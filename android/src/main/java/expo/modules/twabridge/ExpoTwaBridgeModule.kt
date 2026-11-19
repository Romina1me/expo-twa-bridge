package expo.modules.twabridge

import android.util.Log
import android.view.WindowManager
import androidx.browser.trusted.TrustedWebActivityDisplayMode
import com.google.androidbrowserhelper.trusted.TwaProviderPicker
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

private const val DISPLAY_MODE_DEFAULT = "default"
private const val DISPLAY_MODE_IMMERSIVE = "immersive"

class ExpoTwaBridgeModule : Module() {
  private var launcher: StrictTwaLauncher? = null

  override fun definition() = ModuleDefinition {
    Name("ExpoTwaBridge")

    AsyncFunction("launch") {
        url: String,
        displayMode: String,
        additionalTrustedOrigins: List<String>,
        promise: Promise ->
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

      val mode = parseDisplayMode(displayMode)
      if (mode == null) {
        promise.reject(
          "ERR_TWA_INVALID_DISPLAY_MODE",
          "displayMode must be either \"immersive\" or \"default\".",
          null
        )
        return@AsyncFunction
      }

      val trustedOrigins = parseTrustedOrigins(additionalTrustedOrigins)
      if (trustedOrigins == null) {
        promise.reject(
          "ERR_TWA_INVALID_ORIGIN",
          "additionalTrustedOrigins must contain absolute HTTPS origins without a path.",
          null
        )
        return@AsyncFunction
      }

      // Custom Tabs service binding and Activity launching must happen on the UI thread.
      activity.runOnUiThread {
        try {
          val provider = TwaProviderPicker.pickProvider(activity.packageManager)
          val providerPackage = provider.provider

          if (
            provider.launchMode != TwaProviderPicker.LaunchMode.TRUSTED_WEB_ACTIVITY ||
              providerPackage.isNullOrBlank()
          ) {
            promise.reject(
              "ERR_TWA_NO_BROWSER",
              "No browser supporting Trusted Web Activities is installed. " +
                "A Custom Tab or regular browser will not be used as a fallback.",
              null
            )
            return@runOnUiThread
          }

          launcher?.destroy()

          Log.i(
            TAG,
            "Verifying TWA relationship: provider=$providerPackage, displayMode=$displayMode, " +
              "additionalTrustedOrigins=$trustedOrigins, url=$url"
          )

          val strictLauncher = StrictTwaLauncher(activity)
          launcher = strictLauncher

          strictLauncher.launch(
            providerPackage,
            uri,
            mode,
            trustedOrigins,
            object : StrictTwaLauncher.Callback {
              override fun onLaunched() {
                Log.i(TAG, "TWA launched with verified origins.")
                promise.resolve(null)
              }

              override fun onError(code: String, message: String, cause: Throwable?) {
                promise.reject(code, message, cause)
              }
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

  private fun parseDisplayMode(value: String): TrustedWebActivityDisplayMode? =
    when (value.lowercase()) {
      DISPLAY_MODE_IMMERSIVE -> TrustedWebActivityDisplayMode.ImmersiveMode(
        true,
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
      )
      DISPLAY_MODE_DEFAULT -> TrustedWebActivityDisplayMode.DefaultMode()
      else -> null
    }

  private fun parseTrustedOrigins(origins: List<String>): List<String>? {
    val result = mutableListOf<String>()
    for (origin in origins) {
      val normalized = parseOrigin(origin) ?: return null
      if (!result.contains(normalized)) {
        result.add(normalized)
      }
    }
    return result
  }
}
