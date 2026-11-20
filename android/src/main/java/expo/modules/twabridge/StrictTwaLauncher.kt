package expo.modules.twabridge

import android.app.Activity
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.browser.customtabs.CustomTabsCallback
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsService
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import androidx.browser.trusted.TrustedWebActivityDisplayMode
import androidx.browser.trusted.TrustedWebActivityIntentBuilder

/**
 * Single owner of the CustomTabsClient/CustomTabsSession used for both the TWA
 * launch and the optional PostMessage channel.
 *
 * The TWA is only launched after `handle_all_urls` Digital Asset Links
 * verification succeeds for every requested origin. When PostMessage is
 * enabled, the same session additionally validates `use_as_origin` and opens a
 * MessagePort-backed channel after the first navigation finishes.
 */
internal class StrictTwaLauncher(private val activity: Activity) {

  internal interface Callback {
    fun onLaunched()
    fun onError(code: String, message: String, cause: Throwable?)
    fun onChannelReady()
    fun onChannelError(code: String, message: String)
    fun onChannelClosed()
    fun onMessage(data: String)
  }

  private val handler = Handler(Looper.getMainLooper())

  private var connection: CustomTabsServiceConnection? = null
  private var session: CustomTabsSession? = null

  private var uri: Uri? = null
  private var displayMode: TrustedWebActivityDisplayMode? = null
  private var additionalTrustedOrigins: List<String> = emptyList()

  private var postMessageSourceOrigin: String? = null
  private var postMessageTargetOrigin: String? = null
  private var postMessageValidationComplete = false
  private var postMessageValidated = false
  private var navigationFinished = false
  private var channelRequested = false
  private var channelReady = false
  private var channelErrorEmitted = false
  private var channelClosedEmitted = false

  private var pendingLaunchOrigins: MutableSet<String> = mutableSetOf()
  private var callback: Callback? = null
  private var launchSettled = false

  private val launchTimeoutRunnable = Runnable {
    failLaunch(
      "ERR_TWA_VERIFICATION_TIMEOUT",
      "Digital Asset Links verification timed out. The browser did not respond within " +
        "${LAUNCH_TIMEOUT_MS / 1000} seconds.",
      null
    )
  }

  private val channelTimeoutRunnable = Runnable {
    emitChannelError(
      "ERR_TWA_POSTMESSAGE_TIMEOUT",
      "The PostMessage channel was not ready within ${CHANNEL_TIMEOUT_MS / 1000} seconds. " +
        "Check that the origin has the use_as_origin Digital Asset Links relation, the " +
        "PostMessageService is declared, and Chrome 115+ is used.",
      null
    )
  }

  private val sessionCallback = object : CustomTabsCallback() {
    override fun onRelationshipValidationResult(
      relation: Int,
      requestedOrigin: Uri,
      result: Boolean,
      extras: Bundle?
    ) {
      val origin = originOf(requestedOrigin) ?: return

      when (relation) {
        CustomTabsService.RELATION_HANDLE_ALL_URLS -> handleLaunchValidation(origin, result)
        CustomTabsService.RELATION_USE_AS_ORIGIN -> handlePostMessageValidation(origin, result)
      }
    }

    override fun onNavigationEvent(navigationEvent: Int, extras: Bundle?) {
      if (navigationEvent == NAVIGATION_FINISHED) {
        navigationFinished = true
        maybeRequestPostMessageChannel()
      }
    }

    override fun onMessageChannelReady(extras: Bundle?) {
      if (
        !isPostMessageEnabled() ||
        channelReady ||
        channelClosedEmitted ||
        channelErrorEmitted
      ) {
        return
      }

      handler.removeCallbacks(channelTimeoutRunnable)
      channelReady = true
      Log.i(TAG, "PostMessage channel ready.")
      callback?.onChannelReady()
    }

    override fun onPostMessage(message: String, extras: Bundle?) {
      if (!isPostMessageEnabled() || !channelReady || channelClosedEmitted) {
        return
      }

      Log.i(TAG, "PostMessage received (${message.length} chars).")
      callback?.onMessage(message)
    }
  }

  fun launch(
    providerPackage: String,
    uri: Uri,
    displayMode: TrustedWebActivityDisplayMode,
    additionalTrustedOrigins: List<String>,
    postMessageSourceOrigin: String?,
    postMessageTargetOrigin: String?,
    callback: Callback
  ) {
    this.uri = uri
    this.displayMode = displayMode
    this.additionalTrustedOrigins = additionalTrustedOrigins
    this.postMessageSourceOrigin = postMessageSourceOrigin
    this.postMessageTargetOrigin = postMessageTargetOrigin
    this.callback = callback
    this.pendingLaunchOrigins = mutableSetOf()

    val mainOrigin = originOf(uri)
    if (mainOrigin == null) {
      failLaunch(
        "ERR_TWA_INVALID_URL",
        "Only absolute HTTPS URLs can be opened in a Trusted Web Activity.",
        null
      )
      return
    }

    pendingLaunchOrigins.add(mainOrigin)
    pendingLaunchOrigins.addAll(additionalTrustedOrigins)

    val serviceConnection = object : CustomTabsServiceConnection() {
      override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
        if (launchSettled) {
          return
        }

        try {
          if (!client.warmup(0)) {
            failLaunch(
              "ERR_TWA_LAUNCH_FAILED",
              "The browser rejected the Custom Tabs warmup request.",
              null
            )
            return
          }

          val newSession = client.newSession(sessionCallback)
          if (newSession == null) {
            failLaunch(
              "ERR_TWA_LAUNCH_FAILED",
              "The browser could not create a Custom Tabs session.",
              null
            )
            return
          }
          session = newSession

          for (origin in pendingLaunchOrigins.toList()) {
            val submitted = newSession.validateRelationship(
              CustomTabsService.RELATION_HANDLE_ALL_URLS,
              Uri.parse(origin),
              null
            )
            if (!submitted) {
              failLaunch(
                "ERR_TWA_LAUNCH_FAILED",
                "The browser rejected Digital Asset Links verification for $origin.",
                null
              )
              return
            }
          }

          if (isPostMessageEnabled()) {
            val sourceOrigin = postMessageSourceOrigin ?: return
            val submitted = newSession.validateRelationship(
              CustomTabsService.RELATION_USE_AS_ORIGIN,
              Uri.parse(sourceOrigin),
              null
            )
            if (!submitted) {
              emitChannelError(
                "ERR_TWA_POSTMESSAGE_UNAVAILABLE",
                "The browser rejected PostMessage origin validation for $sourceOrigin.",
                null
              )
            }
          }
        } catch (error: Exception) {
          failLaunch(
            "ERR_TWA_LAUNCH_FAILED",
            error.message ?: "The Trusted Web Activity could not be prepared.",
            error
          )
        }
      }

      override fun onServiceDisconnected(name: ComponentName) {
        if (!launchSettled) {
          failLaunch(
            "ERR_TWA_NO_BROWSER",
            "The Trusted Web Activity browser disconnected before verification completed.",
            null
          )
          return
        }

        session = null
        channelReady = false
        emitChannelClosed()
      }
    }

    connection = serviceConnection

    val bound = try {
      CustomTabsClient.bindCustomTabsService(activity, providerPackage, serviceConnection)
    } catch (error: Exception) {
      failLaunch(
        "ERR_TWA_LAUNCH_FAILED",
        error.message ?: "Could not bind to the Trusted Web Activity browser service.",
        error
      )
      return
    }

    if (!bound) {
      failLaunch(
        "ERR_TWA_NO_BROWSER",
        "Could not connect to the Trusted Web Activity browser service.",
        null
      )
      return
    }

    handler.postDelayed(launchTimeoutRunnable, LAUNCH_TIMEOUT_MS)
  }

  /**
   * Sends a message to the web page through the existing session.
   *
   * Returns the browser result code, or null when the channel is not ready.
   */
  fun postMessage(message: String): Int? {
    if (!channelReady || channelClosedEmitted) {
      return null
    }
    val currentSession = session ?: return null
    return currentSession.postMessage(message, null)
  }

  fun isPostMessageReady(): Boolean = channelReady && !channelClosedEmitted

  fun destroy() {
    val wasChannelActive = isPostMessageEnabled() && (channelReady || channelRequested)

    launchSettled = true
    handler.removeCallbacks(launchTimeoutRunnable)
    handler.removeCallbacks(channelTimeoutRunnable)
    unbind()
    session = null
    channelReady = false

    if (wasChannelActive) {
      emitChannelClosed()
    }

    callback = null
  }

  private fun handleLaunchValidation(origin: String, validated: Boolean) {
    if (launchSettled) {
      return
    }
    if (!pendingLaunchOrigins.remove(origin)) {
      return
    }

    Log.i(TAG, "Digital Asset Links result for $origin: validated=$validated")

    if (!validated) {
      failLaunch(
        "ERR_TWA_VERIFICATION_FAILED",
        "Digital Asset Links verification failed for $origin. Serve " +
          "$origin/.well-known/assetlinks.json with this app's package name and " +
          "signing certificate SHA-256 for the " +
          "delegate_permission/common.handle_all_urls relation.",
        null
      )
      return
    }

    if (pendingLaunchOrigins.isEmpty()) {
      launchValidated()
    }
  }

  private fun handlePostMessageValidation(origin: String, validated: Boolean) {
    val sourceOrigin = postMessageSourceOrigin ?: return
    if (!origin.equals(sourceOrigin, ignoreCase = true) || postMessageValidationComplete) {
      return
    }

    postMessageValidationComplete = true
    postMessageValidated = validated
    Log.i(TAG, "PostMessage use_as_origin result for $origin: validated=$validated")

    if (!validated) {
      emitChannelError(
        "ERR_TWA_POSTMESSAGE_VERIFICATION_FAILED",
        "Digital Asset Links verification failed for $origin with the " +
          "delegate_permission/common.use_as_origin relation required by PostMessage.",
        null
      )
      return
    }

    maybeRequestPostMessageChannel()
  }

  private fun maybeRequestPostMessageChannel() {
    if (
      !launchSettled ||
      channelRequested ||
      channelReady ||
      channelErrorEmitted ||
      channelClosedEmitted
    ) {
      return
    }
    if (!isPostMessageEnabled() || !postMessageValidated || !navigationFinished) {
      return
    }

    val currentSession = session ?: return
    val sourceOrigin = postMessageSourceOrigin ?: return
    val targetOrigin = postMessageTargetOrigin ?: return

    channelRequested = true

    val accepted = try {
      currentSession.requestPostMessageChannel(
        Uri.parse(sourceOrigin),
        Uri.parse(targetOrigin),
        Bundle()
      )
    } catch (error: Exception) {
      emitChannelError(
        "ERR_TWA_POSTMESSAGE_UNAVAILABLE",
        error.message ?: "The browser rejected the PostMessage channel request.",
        error
      )
      return
    }

    if (!accepted) {
      emitChannelError(
        "ERR_TWA_POSTMESSAGE_UNAVAILABLE",
        "The browser rejected the PostMessage channel request. Ensure " +
          "androidx.browser.customtabs.PostMessageService is declared and the browser " +
          "supports TWA PostMessage (Chrome 115+).",
        null
      )
      return
    }

    Log.i(TAG, "Requested PostMessage channel: source=$sourceOrigin target=$targetOrigin")
  }

  private fun launchValidated() {
    val uri = uri
    val displayMode = displayMode
    val currentSession = session

    if (uri == null || displayMode == null || currentSession == null) {
      failLaunch(
        "ERR_TWA_LAUNCH_FAILED",
        "The Trusted Web Activity could not be prepared.",
        null
      )
      return
    }

    try {
      val builder = TrustedWebActivityIntentBuilder(uri).setDisplayMode(displayMode)
      if (additionalTrustedOrigins.isNotEmpty()) {
        builder.setAdditionalTrustedOrigins(additionalTrustedOrigins)
      }

      builder.build(currentSession).launchTrustedWebActivity(activity)

      launchSettled = true
      handler.removeCallbacks(launchTimeoutRunnable)
      callback?.onLaunched()

      if (isPostMessageEnabled()) {
        handler.postDelayed(channelTimeoutRunnable, CHANNEL_TIMEOUT_MS)
      }
    } catch (error: Exception) {
      failLaunch(
        "ERR_TWA_LAUNCH_FAILED",
        error.message ?: "The Trusted Web Activity could not be launched.",
        error
      )
    }
  }

  private fun isPostMessageEnabled(): Boolean =
    postMessageSourceOrigin != null && postMessageTargetOrigin != null

  private fun emitChannelError(code: String, message: String, cause: Throwable?) {
    if (channelErrorEmitted || channelClosedEmitted) {
      return
    }
    channelErrorEmitted = true
    handler.removeCallbacks(channelTimeoutRunnable)
    Log.w(TAG, "$code: $message", cause)
    callback?.onChannelError(code, message)
  }

  private fun emitChannelClosed() {
    if (channelClosedEmitted || channelErrorEmitted) {
      return
    }
    if (!channelReady && !channelRequested) {
      return
    }
    channelClosedEmitted = true
    handler.removeCallbacks(channelTimeoutRunnable)
    Log.i(TAG, "PostMessage channel closed.")
    callback?.onChannelClosed()
  }

  private fun failLaunch(code: String, message: String, cause: Throwable?) {
    if (launchSettled) {
      return
    }
    launchSettled = true
    handler.removeCallbacks(launchTimeoutRunnable)
    handler.removeCallbacks(channelTimeoutRunnable)
    unbind()
    session = null

    // Keep the callback alive: PostMessage validation results may still be
    // delivered after the launch itself has failed, and they must reach JS.
    callback?.onError(code, message, cause)
  }

  private fun unbind() {
    val currentConnection = connection ?: return
    connection = null
    try {
      activity.unbindService(currentConnection)
    } catch (_: IllegalArgumentException) {
      // The service was already unbound.
    }
  }

  private companion object {
    const val LAUNCH_TIMEOUT_MS = 15_000L
    const val CHANNEL_TIMEOUT_MS = 25_000L
  }
}
