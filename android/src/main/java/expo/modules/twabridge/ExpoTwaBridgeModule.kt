package expo.modules.twabridge

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.view.WindowManager
import androidx.browser.customtabs.CustomTabsService
import androidx.browser.trusted.TrustedWebActivityDisplayMode
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

private const val DISPLAY_MODE_DEFAULT = "default"
private const val DISPLAY_MODE_IMMERSIVE = "immersive"
private val DEFAULT_SPLASH_BACKGROUND_COLOR = Color.WHITE

private data class PostMessageConfig(
  val sourceOrigin: String,
  val targetOrigin: String
)

class ExpoTwaBridgeModule : Module() {
  private var launcher: StrictTwaLauncher? = null

  override fun definition() = ModuleDefinition {
    Name("ExpoTwaBridge")

    Events("onMessage", "onChannelReady", "onChannelError", "onChannelClosed")

    AsyncFunction("launch") {
        url: String,
        displayMode: String,
        fallback: String,
        browserPackage: String?,
        additionalTrustedOrigins: List<String>,
        options: TwaLaunchOptionsRecord?,
        verification: String,
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

      val fallbackPolicy = FallbackPolicy.fromValue(fallback)
      if (fallbackPolicy == null) {
        promise.reject(
          "ERR_TWA_INVALID_FALLBACK_MODE",
          "fallback must be either \"custom-tabs\" or \"none\".",
          null
        )
        return@AsyncFunction
      }

      val verificationMode = VerificationMode.fromValue(verification)
      if (verificationMode == null) {
        promise.reject(
          "ERR_TWA_INVALID_VERIFICATION_MODE",
          "verification must be either \"strict\" or \"permissive\".",
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

      val postMessageOptions = options?.postMessage
      val postMessageConfig = parsePostMessageConfig(uri, postMessageOptions)
      if (postMessageOptions != null && postMessageConfig == null) {
        promise.reject(
          "ERR_TWA_POSTMESSAGE_INVALID_ORIGIN",
          "PostMessage origins must be absolute HTTPS origins without a path.",
          null
        )
        return@AsyncFunction
      }

      val splashScreenOptions = options?.splashScreen
      val splashScreenConfig = parseSplashScreenConfig(splashScreenOptions)
      if (splashScreenOptions != null && splashScreenConfig == null) {
        promise.reject(
          "ERR_TWA_INVALID_SPLASH_SCREEN",
          "splashScreen.image must resolve to a non-empty image URI.",
          null
        )
        return@AsyncFunction
      }

      val requestedPackage = browserPackage?.takeIf { it.isNotBlank() }

      // Custom Tabs service binding and Activity launching must happen on the UI thread.
      activity.runOnUiThread {
        try {
          launcher?.destroy()

          val capabilities =
            TwaProviderResolver.resolve(activity.packageManager, requestedPackage)
          val decision = decideLaunchStrategy(capabilities, fallbackPolicy)
          val providerPackage = capabilities.packageName

          Log.i(
            TAG,
            "Launch request: url=$url, fallback=${fallbackPolicy.value}, " +
              "verification=${verificationMode.value}, " +
              "requestedPackage=$requestedPackage, resolvedProvider=$providerPackage, " +
              "twa=${capabilities.supportsTwa}, customTabs=${capabilities.supportsCustomTabs}, " +
              "decision=${decision.strategy}"
          )

          when (decision.strategy) {
            LaunchStrategy.ERROR -> {
              promise.reject(
                decision.errorCode ?: "ERR_TWA_LAUNCH_FAILED",
                decision.errorMessage ?: "The Trusted Web Activity could not be launched.",
                null
              )
            }

            LaunchStrategy.CUSTOM_TABS -> {
              if (providerPackage == null) {
                promise.reject(
                  "ERR_TWA_CUSTOM_TABS_UNAVAILABLE",
                  "No browser supporting Android Custom Tabs is installed.",
                  null
                )
                return@runOnUiThread
              }

              val customTabsLauncher = StrictTwaLauncher(activity)
              launcher = customTabsLauncher
              customTabsLauncher.launchCustomTabs(
                providerPackage,
                uri,
                postMessageConfig?.sourceOrigin,
                postMessageConfig?.targetOrigin,
                createCallback(
                  activity = activity,
                  uri = uri,
                  surface = LaunchSurface.CUSTOM_TABS,
                  fallback = fallbackPolicy,
                  fallbackPackage = providerPackage,
                  postMessageConfig = postMessageConfig,
                  promise = promise,
                  allowFallbackFromTwa = false
                )
              )
            }

            LaunchStrategy.TWA -> {
              if (providerPackage == null) {
                promise.reject(
                  "ERR_TWA_NO_BROWSER",
                  "No browser supporting Trusted Web Activities is installed.",
                  null
                )
                return@runOnUiThread
              }

              Log.i(TAG, "Verifying TWA relationship: provider=$providerPackage, url=$url")

              val twaLauncher = StrictTwaLauncher(activity)
              launcher = twaLauncher
              twaLauncher.launch(
                providerPackage,
                uri,
                mode,
                trustedOrigins,
                verificationMode,
                splashScreenConfig,
                postMessageConfig?.sourceOrigin,
                postMessageConfig?.targetOrigin,
                createCallback(
                  activity = activity,
                  uri = uri,
                  surface = LaunchSurface.TWA,
                  fallback = fallbackPolicy,
                  fallbackPackage = providerPackage,
                  postMessageConfig = postMessageConfig,
                  promise = promise,
                  allowFallbackFromTwa = true
                )
              )
            }
          }
        } catch (error: Exception) {
          promise.reject(
            "ERR_TWA_LAUNCH_FAILED",
            error.message ?: "The Trusted Web Activity could not be launched.",
            error
          )
        }
      }
    }

    AsyncFunction("postMessage") { message: String, promise: Promise ->
      val currentLauncher = launcher
      if (currentLauncher == null) {
        promise.reject(
          "ERR_TWA_POSTMESSAGE_NOT_READY",
          "The PostMessage channel is not ready. Launch a TWA with postMessage enabled first.",
          null
        )
        return@AsyncFunction
      }

      val result = currentLauncher.postMessage(message)
      when (result) {
        null -> promise.reject(
          "ERR_TWA_POSTMESSAGE_NOT_READY",
          "The PostMessage channel is not ready.",
          null
        )
        CustomTabsService.RESULT_SUCCESS -> promise.resolve(null)
        else -> promise.reject(
          "ERR_TWA_POSTMESSAGE_FAILED",
          "The browser rejected the PostMessage (result code $result).",
          null
        )
      }
    }

    AsyncFunction("isPostMessageReady") {
      launcher?.isPostMessageReady() == true
    }

    OnDestroy {
      launcher?.destroy()
      launcher = null
    }
  }

  /**
   * Builds the launcher callback for a surface. A fallback-eligible TWA
   * failure is retried once with Android Custom Tabs when the fallback policy
   * allows it; verification failures are never retried.
   */
  private fun createCallback(
    activity: Activity,
    uri: Uri,
    surface: LaunchSurface,
    fallback: FallbackPolicy,
    fallbackPackage: String?,
    postMessageConfig: PostMessageConfig?,
    promise: Promise,
    allowFallbackFromTwa: Boolean
  ): StrictTwaLauncher.Callback =
    object : StrictTwaLauncher.Callback {
      override fun onLaunched() {
        promise.resolve(surface.mode)
      }

      override fun onError(
        code: String,
        message: String,
        cause: Throwable?,
        fallbackEligible: Boolean
      ) {
        val canFallback = allowFallbackFromTwa &&
          fallbackEligible &&
          fallback == FallbackPolicy.CUSTOM_TABS &&
          fallbackPackage != null

        if (!canFallback) {
          promise.reject(code, message, cause)
          return
        }

        Log.i(TAG, "$code: $message — falling back to Android Custom Tabs.")

        val fallbackLauncher = StrictTwaLauncher(activity)
        launcher = fallbackLauncher
        fallbackLauncher.launchCustomTabs(
          fallbackPackage,
          uri,
          postMessageConfig?.sourceOrigin,
          postMessageConfig?.targetOrigin,
          createCallback(
            activity = activity,
            uri = uri,
            surface = LaunchSurface.CUSTOM_TABS,
            fallback = fallback,
            fallbackPackage = fallbackPackage,
            postMessageConfig = postMessageConfig,
            promise = promise,
            allowFallbackFromTwa = false
          )
        )
      }

      override fun onChannelReady() {
        sendEvent("onChannelReady")
      }

      override fun onChannelError(code: String, message: String) {
        sendEvent(
          "onChannelError",
          mapOf("code" to code, "message" to message)
        )
      }

      override fun onChannelClosed() {
        sendEvent("onChannelClosed")
      }

      override fun onMessage(data: String) {
        sendEvent("onMessage", mapOf("data" to data))
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

  private fun parsePostMessageConfig(
    uri: Uri,
    options: TwaPostMessageOptionsRecord?
  ): PostMessageConfig? {
    if (options == null) {
      return null
    }

    val source = when {
      options.sourceOrigin == null -> originOf(uri) ?: return null
      else -> parseOrigin(options.sourceOrigin!!) ?: return null
    }
    val target = when {
      options.targetOrigin == null -> source
      else -> parseOrigin(options.targetOrigin!!) ?: return null
    }
    return PostMessageConfig(source, target)
  }

  private fun parseSplashScreenConfig(
    options: TwaSplashScreenOptionsRecord?
  ): SplashScreenConfig? {
    if (options == null) {
      return null
    }

    val image = options.image?.trim()
    if (image.isNullOrEmpty()) {
      return null
    }

    val backgroundColor = options.backgroundColor?.let { value ->
      runCatching { Color.parseColor(value) }.getOrNull()
    } ?: DEFAULT_SPLASH_BACKGROUND_COLOR

    return SplashScreenConfig(image = image, backgroundColor = backgroundColor)
  }
}
