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
 * Launches a Trusted Web Activity only after the browser has confirmed the
 * Digital Asset Links relationship (`handle_all_urls`) for every requested
 * origin.
 *
 * This deliberately owns the [CustomTabsSession] so future PostMessage and
 * MessagePort support can reuse the same connection instead of replacing the
 * launch path.
 */
internal class StrictTwaLauncher(private val activity: Activity) {

  internal interface Callback {
    fun onLaunched()
    fun onError(code: String, message: String, cause: Throwable?)
  }

  private val handler = Handler(Looper.getMainLooper())

  private var connection: CustomTabsServiceConnection? = null
  private var session: CustomTabsSession? = null

  private var uri: Uri? = null
  private var displayMode: TrustedWebActivityDisplayMode? = null
  private var additionalTrustedOrigins: List<String> = emptyList()
  private var pendingOrigins: MutableSet<String> = mutableSetOf()
  private var callback: Callback? = null
  private var settled = false

  private val timeoutRunnable = Runnable {
    fail(
      "ERR_TWA_VERIFICATION_TIMEOUT",
      "Digital Asset Links verification timed out. The browser did not respond within " +
        "${TIMEOUT_MS / 1000} seconds.",
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
      if (settled || relation != CustomTabsService.RELATION_HANDLE_ALL_URLS) {
        return
      }

      val origin = originOf(requestedOrigin) ?: return
      if (!pendingOrigins.remove(origin)) {
        return
      }

      Log.i(TAG, "Digital Asset Links result for $origin: validated=$result")

      if (!result) {
        fail(
          "ERR_TWA_VERIFICATION_FAILED",
          "Digital Asset Links verification failed for $origin. Serve " +
            "$origin/.well-known/assetlinks.json with this app's package name and " +
            "signing certificate SHA-256 for the " +
            "delegate_permission/common.handle_all_urls relation.",
          null
        )
        return
      }

      if (pendingOrigins.isEmpty()) {
        launchValidated()
      }
    }
  }

  fun launch(
    providerPackage: String,
    uri: Uri,
    displayMode: TrustedWebActivityDisplayMode,
    additionalTrustedOrigins: List<String>,
    callback: Callback
  ) {
    this.uri = uri
    this.displayMode = displayMode
    this.additionalTrustedOrigins = additionalTrustedOrigins
    this.callback = callback
    this.pendingOrigins = mutableSetOf()

    val mainOrigin = originOf(uri)
    if (mainOrigin == null) {
      fail(
        "ERR_TWA_INVALID_URL",
        "Only absolute HTTPS URLs can be opened in a Trusted Web Activity.",
        null
      )
      return
    }

    pendingOrigins.add(mainOrigin)
    pendingOrigins.addAll(additionalTrustedOrigins)

    val serviceConnection = object : CustomTabsServiceConnection() {
      override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
        if (settled) {
          return
        }

        try {
          if (!client.warmup(0)) {
            fail(
              "ERR_TWA_LAUNCH_FAILED",
              "The browser rejected the Custom Tabs warmup request.",
              null
            )
            return
          }

          val newSession = client.newSession(sessionCallback)
          if (newSession == null) {
            fail(
              "ERR_TWA_LAUNCH_FAILED",
              "The browser could not create a Custom Tabs session.",
              null
            )
            return
          }
          session = newSession

          for (origin in pendingOrigins.toList()) {
            val submitted = newSession.validateRelationship(
              CustomTabsService.RELATION_HANDLE_ALL_URLS,
              Uri.parse(origin),
              null
            )
            if (!submitted) {
              fail(
                "ERR_TWA_LAUNCH_FAILED",
                "The browser rejected Digital Asset Links verification for $origin.",
                null
              )
              return
            }
          }
        } catch (error: Exception) {
          fail(
            "ERR_TWA_LAUNCH_FAILED",
            error.message ?: "The Trusted Web Activity could not be prepared.",
            error
          )
        }
      }

      override fun onServiceDisconnected(name: ComponentName) {
        if (!settled) {
          fail(
            "ERR_TWA_NO_BROWSER",
            "The Trusted Web Activity browser disconnected before verification completed.",
            null
          )
        }
      }
    }

    connection = serviceConnection

    val bound = try {
      CustomTabsClient.bindCustomTabsService(activity, providerPackage, serviceConnection)
    } catch (error: Exception) {
      fail(
        "ERR_TWA_LAUNCH_FAILED",
        error.message ?: "Could not bind to the Trusted Web Activity browser service.",
        error
      )
      return
    }

    if (!bound) {
      fail(
        "ERR_TWA_NO_BROWSER",
        "Could not connect to the Trusted Web Activity browser service.",
        null
      )
      return
    }

    handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
  }

  fun destroy() {
    settled = true
    handler.removeCallbacks(timeoutRunnable)
    unbind()
    callback = null
    session = null
  }

  private fun launchValidated() {
    val uri = uri
    val displayMode = displayMode
    val session = session

    if (uri == null || displayMode == null || session == null) {
      fail(
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

      builder.build(session).launchTrustedWebActivity(activity)

      settled = true
      handler.removeCallbacks(timeoutRunnable)
      callback?.onLaunched()
    } catch (error: Exception) {
      fail(
        "ERR_TWA_LAUNCH_FAILED",
        error.message ?: "The Trusted Web Activity could not be launched.",
        error
      )
    }
  }

  private fun fail(code: String, message: String, cause: Throwable?) {
    if (settled) {
      return
    }
    settled = true
    handler.removeCallbacks(timeoutRunnable)
    unbind()
    session = null

    val currentCallback = callback
    callback = null
    currentCallback?.onError(code, message, cause)
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
    const val TIMEOUT_MS = 15_000L
  }
}
