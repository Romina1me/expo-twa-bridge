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
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsService
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import androidx.browser.trusted.TrustedWebActivityDisplayMode
import androidx.browser.trusted.TrustedWebActivityIntentBuilder

/**
 * Single owner of the CustomTabsClient/CustomTabsSession used for both the TWA
 * launch, the Custom Tabs fallback launch and the optional PostMessage
 * channel.
 *
 * TWA launches only happen after `handle_all_urls` Digital Asset Links
 * verification succeeds for every requested origin. The Custom Tabs fallback
 * skips that verification because the browser chrome is visible, so no trust
 * relationship is needed. When PostMessage is enabled, the same session
 * additionally validates `use_as_origin` and opens a MessagePort-backed
 * channel after the first navigation finishes, on both surfaces.
 */
internal class StrictTwaLauncher(private val activity: Activity) {

  internal interface Callback {
    fun onLaunched()

    /**
     * @param fallbackEligible true when the failure is a browser/environment
     * problem that the Custom Tabs fallback could work around. Verification
     * and configuration failures are never fallback-eligible.
     */
    fun onError(code: String, message: String, cause: Throwable?, fallbackEligible: Boolean)
    fun onChannelReady()
    fun onChannelError(code: String, message: String)
    fun onChannelClosed()
    fun onMessage(data: String)
  }

  private val handler = Handler(Looper.getMainLooper())

  private var connection: CustomTabsServiceConnection? = null
  private var session: CustomTabsSession? = null

  private var providerPackage: String? = null
  private var uri: Uri? = null
  private var displayMode: TrustedWebActivityDisplayMode? = null
  private var additionalTrustedOrigins: List<String> = emptyList()
  private var verificationMode: VerificationMode = VerificationMode.PERMISSIVE
  private var splashScreen: SplashScreenConfig? = null
  private var verificationSatisfied = false
  private var splashResolved = false
  private var splashParams: Bundle? = null

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
  private var launchValidationAttempts: MutableMap<String, Int> = mutableMapOf()
  private var launchValidationTimeouts: MutableMap<String, Runnable> = mutableMapOf()
  private var callback: Callback? = null
  private var launchSettled = false

  private val launchTimeoutRunnable = Runnable {
    when (VerificationPolicy.onTimeout(verificationMode)) {
      is VerificationDecision.Reject -> failLaunch(
        "ERR_TWA_VERIFICATION_TIMEOUT",
        "Digital Asset Links verification timed out. The browser did not respond within " +
          "${LAUNCH_TIMEOUT_MS / 1000} seconds.",
        null,
        fallbackEligible = false
      )
      // Permissive only reaches this when the service never connected: a
      // connected session launches immediately. Treat it as a launch problem,
      // not a verification failure, so the fallback policy can apply.
      VerificationDecision.ProceedUnverified -> {
        if (session != null) {
          launchValidated()
        } else {
          failLaunch(
            "ERR_TWA_LAUNCH_FAILED",
            "The browser did not connect within ${LAUNCH_TIMEOUT_MS / 1000} seconds.",
            null,
            fallbackEligible = true
          )
        }
      }
      else -> Unit
    }
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

  /**
   * Verified TWA launch: binds to [providerPackage], validates
   * `handle_all_urls` for the main and additional origins, then launches.
   */
  fun launch(
    providerPackage: String,
    uri: Uri,
    displayMode: TrustedWebActivityDisplayMode,
    additionalTrustedOrigins: List<String>,
    verificationMode: VerificationMode,
    splashScreen: SplashScreenConfig?,
    postMessageSourceOrigin: String?,
    postMessageTargetOrigin: String?,
    callback: Callback
  ) {
    prepareLaunch(
      providerPackage,
      uri,
      verificationMode,
      splashScreen,
      postMessageSourceOrigin,
      postMessageTargetOrigin,
      callback
    )
    this.displayMode = displayMode
    this.additionalTrustedOrigins = additionalTrustedOrigins

    val mainOrigin = originOf(uri)
    if (mainOrigin == null) {
      failLaunch(
        "ERR_TWA_INVALID_URL",
        "Only absolute HTTPS URLs can be opened in a Trusted Web Activity.",
        null,
        fallbackEligible = false
      )
      return
    }

    pendingLaunchOrigins.add(mainOrigin)
    pendingLaunchOrigins.addAll(additionalTrustedOrigins)

    connect(providerPackage) { newSession ->
      prepareSplashScreen(newSession)

      if (VerificationPolicy.requiresPreLaunchVerification(verificationMode)) {
        for (origin in pendingLaunchOrigins.toList()) {
          launchValidationAttempts[origin] = 1
          submitLaunchValidation(origin, newSession)
        }
        submitPostMessageValidation(newSession)
      } else {
        // Permissive: launch the real TWA intent without our pre-check gate.
        // Chrome still verifies the origin itself when it handles the intent,
        // and this launch is never reported as verified.
        submitPostMessageValidation(newSession)
        onVerificationSatisfied()
      }
    }

    handler.postDelayed(launchTimeoutRunnable, LAUNCH_TIMEOUT_MS)
  }

  /**
   * Custom Tabs fallback launch: binds to [providerPackage] and opens the URL
   * with visible browser chrome. PostMessage uses the same session machinery
   * as the TWA launch, so the channel keeps working on this surface.
   */
  fun launchCustomTabs(
    providerPackage: String,
    uri: Uri,
    postMessageSourceOrigin: String?,
    postMessageTargetOrigin: String?,
    callback: Callback
  ) {
    prepareLaunch(
      providerPackage,
      uri,
      VerificationMode.PERMISSIVE,
      null,
      postMessageSourceOrigin,
      postMessageTargetOrigin,
      callback
    )

    connect(providerPackage) { newSession ->
      submitPostMessageValidation(newSession)
      openCustomTab(newSession, uri)
    }
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
    cancelAllLaunchValidationTimeouts()
    unbind()
    session = null
    channelReady = false

    if (wasChannelActive) {
      emitChannelClosed()
    }

    callback = null
  }

  private fun prepareLaunch(
    providerPackage: String,
    uri: Uri,
    verificationMode: VerificationMode,
    splashScreen: SplashScreenConfig?,
    postMessageSourceOrigin: String?,
    postMessageTargetOrigin: String?,
    callback: Callback
  ) {
    this.providerPackage = providerPackage
    this.uri = uri
    this.verificationMode = verificationMode
    this.splashScreen = splashScreen
    this.verificationSatisfied = false
    this.splashResolved = splashScreen == null
    this.splashParams = null
    this.displayMode = null
    this.additionalTrustedOrigins = emptyList()
    this.postMessageSourceOrigin = postMessageSourceOrigin
    this.postMessageTargetOrigin = postMessageTargetOrigin
    this.callback = callback
    this.pendingLaunchOrigins = mutableSetOf()
    this.launchValidationAttempts = mutableMapOf()
    this.launchValidationTimeouts = mutableMapOf()
    this.navigationFinished = false
    this.channelRequested = false
  }

  /**
   * Binds the browser's Custom Tabs service and hands a ready session to
   * [onSession]. Bind, warmup and session creation failures reject the launch
   * as fallback-eligible browser problems.
   */
  private fun connect(providerPackage: String, onSession: (CustomTabsSession) -> Unit) {
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
              null,
              fallbackEligible = true
            )
            return
          }

          val newSession = client.newSession(sessionCallback)
          if (newSession == null) {
            failLaunch(
              "ERR_TWA_LAUNCH_FAILED",
              "The browser could not create a Custom Tabs session.",
              null,
              fallbackEligible = true
            )
            return
          }
          session = newSession

          onSession(newSession)
        } catch (error: Exception) {
          failLaunch(
            "ERR_TWA_LAUNCH_FAILED",
            error.message ?: "The Trusted Web Activity could not be prepared.",
            error,
            fallbackEligible = true
          )
        }
      }

      override fun onServiceDisconnected(name: ComponentName) {
        if (!launchSettled) {
          failLaunch(
            "ERR_TWA_NO_BROWSER",
            "The browser disconnected before the launch completed.",
            null,
            fallbackEligible = true
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
        error.message ?: "Could not bind to the browser's Custom Tabs service.",
        error,
        fallbackEligible = true
      )
      return
    }

    if (!bound) {
      failLaunch(
        "ERR_TWA_NO_BROWSER",
        "Could not connect to the browser's Custom Tabs service.",
        null,
        fallbackEligible = true
      )
    }
  }

  private fun openCustomTab(currentSession: CustomTabsSession, uri: Uri) {
    val providerPackage = providerPackage ?: run {
      failLaunch(
        "ERR_TWA_FALLBACK_FAILED",
        "The Custom Tabs fallback has no target browser package.",
        null,
        fallbackEligible = false
      )
      return
    }

    try {
      val customTabsIntent = CustomTabsIntent.Builder(currentSession).build()
      customTabsIntent.intent.setPackage(providerPackage)
      customTabsIntent.launchUrl(activity, uri)

      launchSettled = true
      callback?.onLaunched()

      if (isPostMessageEnabled()) {
        handler.postDelayed(channelTimeoutRunnable, CHANNEL_TIMEOUT_MS)
      }
    } catch (error: Exception) {
      failLaunch(
        "ERR_TWA_FALLBACK_FAILED",
        error.message ?: "The Custom Tabs fallback could not be launched.",
        error,
        fallbackEligible = false
      )
    }
  }

  private fun submitPostMessageValidation(currentSession: CustomTabsSession) {
    if (!isPostMessageEnabled()) {
      return
    }
    val sourceOrigin = postMessageSourceOrigin ?: return
    val submitted = currentSession.validateRelationship(
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

  private fun handleLaunchValidation(origin: String, validated: Boolean) {
    if (launchSettled || !pendingLaunchOrigins.contains(origin)) {
      return
    }

    cancelLaunchValidationTimeout(origin)
    Log.i(TAG, "Digital Asset Links result for $origin: validated=$validated")

    val decision = VerificationPolicy.onResult(
      mode = verificationMode,
      validated = validated,
      attempt = launchValidationAttempts[origin] ?: 1,
      maxAttempts = VALIDATION_MAX_ATTEMPTS,
      failureCode = "ERR_TWA_VERIFICATION_FAILED",
      failureMessage = "Digital Asset Links verification failed for $origin. Serve " +
        "$origin/.well-known/assetlinks.json with this app's package name and " +
        "signing certificate SHA-256 for the " +
        "delegate_permission/common.handle_all_urls relation."
    )

    applyLaunchValidationDecision(origin, decision)
  }

  private fun submitLaunchValidation(origin: String, currentSession: CustomTabsSession) {
    if (launchSettled || !pendingLaunchOrigins.contains(origin)) {
      return
    }

    val submitted = currentSession.validateRelationship(
      CustomTabsService.RELATION_HANDLE_ALL_URLS,
      Uri.parse(origin),
      null
    )
    if (!submitted) {
      onLaunchValidationFailure(
        origin,
        "ERR_TWA_VERIFICATION_UNAVAILABLE",
        "The browser rejected the Digital Asset Links verification request for $origin."
      )
      return
    }

    armLaunchValidationTimeout(origin)
  }

  /**
   * Each attempt gets its own deadline so a browser that never answers still
   * consumes an attempt and is retried, instead of failing the launch without
   * exhausting [VALIDATION_MAX_ATTEMPTS].
   */
  private fun armLaunchValidationTimeout(origin: String) {
    cancelLaunchValidationTimeout(origin)

    val timeout = Runnable {
      launchValidationTimeouts.remove(origin)
      if (launchSettled || !pendingLaunchOrigins.contains(origin)) {
        return@Runnable
      }
      Log.i(TAG, "Digital Asset Links verification timed out for $origin.")
      onLaunchValidationFailure(
        origin,
        "ERR_TWA_VERIFICATION_TIMEOUT",
        "Digital Asset Links verification timed out for $origin. The browser did not " +
          "respond within ${VALIDATION_ATTEMPT_TIMEOUT_MS / 1000} seconds."
      )
    }

    launchValidationTimeouts[origin] = timeout
    handler.postDelayed(timeout, VALIDATION_ATTEMPT_TIMEOUT_MS)
  }

  private fun cancelLaunchValidationTimeout(origin: String) {
    launchValidationTimeouts.remove(origin)?.let { handler.removeCallbacks(it) }
  }

  private fun cancelAllLaunchValidationTimeouts() {
    launchValidationTimeouts.values.forEach { handler.removeCallbacks(it) }
    launchValidationTimeouts.clear()
  }

  /**
   * A Digital Asset Links result can be `false` on the first cold request even
   * when the origin serves a valid `assetlinks.json` (for example the first
   * launch right after install); a later request usually succeeds. Retry the
   * validation a few times before reporting the failure. Permissive mode never
   * blocks on this.
   */
  private fun onLaunchValidationFailure(origin: String, code: String, message: String) {
    if (launchSettled || !pendingLaunchOrigins.contains(origin)) {
      return
    }

    val decision = VerificationPolicy.onResult(
      mode = verificationMode,
      validated = false,
      attempt = launchValidationAttempts[origin] ?: 1,
      maxAttempts = VALIDATION_MAX_ATTEMPTS,
      failureCode = code,
      failureMessage = message
    )

    applyLaunchValidationDecision(origin, decision)
  }

  private fun applyLaunchValidationDecision(
    origin: String,
    decision: VerificationDecision
  ) {
    when (decision) {
      VerificationDecision.Retry -> {
        cancelLaunchValidationTimeout(origin)
        val attempts = launchValidationAttempts[origin] ?: 1
        launchValidationAttempts[origin] = attempts + 1
        Log.i(
          TAG,
          "Retrying Digital Asset Links verification for $origin " +
            "(${attempts + 1}/$VALIDATION_MAX_ATTEMPTS)."
        )
        handler.postDelayed(
          {
            val currentSession = session
            if (currentSession == null) {
              failLaunch(
                "ERR_TWA_VERIFICATION_UNAVAILABLE",
                "The browser disconnected before Digital Asset Links verification for $origin " +
                  "completed.",
                null,
                fallbackEligible = false
              )
            } else {
              submitLaunchValidation(origin, currentSession)
            }
          },
          VALIDATION_RETRY_DELAY_MS
        )
      }
      VerificationDecision.Verified -> {
        cancelLaunchValidationTimeout(origin)
        pendingLaunchOrigins.remove(origin)
        launchValidationAttempts.remove(origin)
        if (pendingLaunchOrigins.isEmpty()) {
          onVerificationSatisfied()
        }
      }
      VerificationDecision.ProceedUnverified -> {
        cancelLaunchValidationTimeout(origin)
        pendingLaunchOrigins.remove(origin)
        launchValidationAttempts.remove(origin)
        onVerificationSatisfied()
      }
      is VerificationDecision.Reject -> {
        cancelLaunchValidationTimeout(origin)
        pendingLaunchOrigins.remove(origin)
        launchValidationAttempts.remove(origin)
        failLaunch(decision.code, decision.message, null, fallbackEligible = false)
      }
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

  private fun prepareSplashScreen(currentSession: CustomTabsSession) {
    val config = splashScreen
    if (config == null) {
      splashResolved = true
      return
    }

    val provider = providerPackage
    if (provider == null || !TwaSplashScreen.areSplashScreensSupported(activity, provider)) {
      Log.i(TAG, "Splash screens are not supported by $provider; launching without one.")
      splashResolved = true
      return
    }

    TwaSplashScreen.prepare(activity, provider, currentSession, config) { params ->
      if (launchSettled) {
        return@prepare
      }
      splashParams = params
      splashResolved = true
      maybeLaunch()
    }
  }

  private fun onVerificationSatisfied() {
    if (launchSettled) {
      return
    }
    verificationSatisfied = true
    maybeLaunch()
  }

  /** Launches only after verification passed and splash preparation settled. */
  private fun maybeLaunch() {
    if (launchSettled || !verificationSatisfied || !splashResolved) {
      return
    }
    launchValidated()
  }

  private fun launchValidated() {
    val uri = uri
    val displayMode = displayMode
    val currentSession = session
    val providerPackage = providerPackage

    if (uri == null || displayMode == null || currentSession == null || providerPackage == null) {
      failLaunch(
        "ERR_TWA_LAUNCH_FAILED",
        "The Trusted Web Activity could not be prepared.",
        null,
        fallbackEligible = true
      )
      return
    }

    try {
      val builder = TrustedWebActivityIntentBuilder(uri).setDisplayMode(displayMode)
      if (additionalTrustedOrigins.isNotEmpty()) {
        builder.setAdditionalTrustedOrigins(additionalTrustedOrigins)
      }
      splashParams?.let { builder.setSplashScreenParams(it) }

      val intent = builder.build(currentSession)
      // Route the launch to the verified provider instead of the system default.
      intent.intent.setPackage(providerPackage)
      intent.launchTrustedWebActivity(activity)

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
        error,
        fallbackEligible = true
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

  private fun failLaunch(
    code: String,
    message: String,
    cause: Throwable?,
    fallbackEligible: Boolean
  ) {
    if (launchSettled) {
      return
    }
    launchSettled = true
    handler.removeCallbacks(launchTimeoutRunnable)
    handler.removeCallbacks(channelTimeoutRunnable)
    cancelAllLaunchValidationTimeouts()
    unbind()
    session = null

    // Keep the callback alive: PostMessage validation results may still be
    // delivered after the launch itself has failed, and they must reach JS.
    callback?.onError(code, message, cause, fallbackEligible)
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
    // Safety net above VALIDATION_MAX_ATTEMPTS * VALIDATION_ATTEMPT_TIMEOUT_MS
    // plus the retry delays.
    const val LAUNCH_TIMEOUT_MS = 45_000L
    const val VALIDATION_MAX_ATTEMPTS = 3
    const val VALIDATION_RETRY_DELAY_MS = 1_500L
    const val VALIDATION_ATTEMPT_TIMEOUT_MS = 10_000L
    const val CHANNEL_TIMEOUT_MS = 25_000L
  }
}
