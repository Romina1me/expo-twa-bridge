package expo.modules.twabridge

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Dedicated WebView surface used only by the explicit `fallback: 'webview'`
 * policy. It loads the same HTTPS URL as the TWA, hides the Android system
 * bars in immersive mode, handles Android back navigation through the WebView
 * history, and never exposes a JavaScript interface.
 */
class TwaWebViewActivity : Activity() {

  companion object {
    const val EXTRA_URL = "expo.modules.twabridge.TwaWebViewActivity.EXTRA_URL"
    const val EXTRA_IMMERSIVE =
      "expo.modules.twabridge.TwaWebViewActivity.EXTRA_IMMERSIVE"

    private const val STATE_URL = "expo.modules.twabridge.TwaWebViewActivity.STATE_URL"
  }

  private var webView: WebView? = null
  private var errorView: TextView? = null
  private var immersive = true

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val requestedUrl = intent.getStringExtra(EXTRA_URL)
    if (requestedUrl == null || parseHttpsUrl(requestedUrl) == null) {
      Log.w(TAG, "The WebView fallback received a non-HTTPS URL; finishing.")
      finish()
      return
    }

    immersive = intent.getBooleanExtra(EXTRA_IMMERSIVE, true)

    try {
      setupContent(savedInstanceState, requestedUrl)
    } catch (error: Exception) {
      Log.e(TAG, "The WebView fallback could not be initialized.", error)
      showFatalError(
        "WebView unavailable",
        error.message ?: "The Android WebView could not be initialized."
      )
    }
  }

  private fun setupContent(savedInstanceState: Bundle?, requestedUrl: String) {
    val root = FrameLayout(this)
    val error = TextView(this).apply {
      setBackgroundColor(Color.WHITE)
      setTextColor(Color.DKGRAY)
      textSize = 16f
      gravity = android.view.Gravity.CENTER
      setPadding(48, 48, 48, 48)
      visibility = View.GONE
    }

    val web = WebView(this)
    webView = web
    configure(web)

    root.addView(
      web,
      FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
      )
    )
    root.addView(
      error,
      FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
      )
    )
    setContentView(root)
    errorView = error
    SystemBarsCompat.apply(this, immersive)

    BackCompat.register(this)

    val restored = savedInstanceState?.let {
      try {
        web.restoreState(it)
      } catch (error: Exception) {
        Log.w(TAG, "Could not restore the WebView state.", error)
        null
      }
    }
    if (restored == null) {
      val restoreUrl = savedInstanceState?.getString(STATE_URL) ?: requestedUrl
      web.loadUrl(restoreUrl)
    }
  }

  @SuppressLint("SetJavaScriptEnabled")
  @Suppress("DEPRECATION")
  private fun configure(web: WebView) {
    web.settings.apply {
      javaScriptEnabled = true
      domStorageEnabled = true
      databaseEnabled = true
      allowFileAccess = false
      allowContentAccess = false
      allowFileAccessFromFileURLs = false
      allowUniversalAccessFromFileURLs = false
      javaScriptCanOpenWindowsAutomatically = false
      setSupportMultipleWindows(false)
      mediaPlaybackRequiresUserGesture = true
      mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
      setGeolocationEnabled(false)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        safeBrowsingEnabled = true
      }
    }
    web.webViewClient = BridgeWebViewClient()
  }

  @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
  override fun onBackPressed() {
    handleBack()
  }

  private fun handleBack() {
    val web = webView
    if (web != null && web.canGoBack()) {
      web.goBack()
    } else {
      finish()
    }
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus && immersive) {
      SystemBarsCompat.apply(this, immersive)
    }
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    if (immersive) {
      SystemBarsCompat.apply(this, immersive)
    }
  }

  override fun onPause() {
    webView?.onPause()
    super.onPause()
  }

  override fun onResume() {
    super.onResume()
    webView?.onResume()
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    webView?.saveState(outState)
    webView?.url?.let { outState.putString(STATE_URL, it) }
  }

  override fun onDestroy() {
    webView?.let { web ->
      (web.parent as? ViewGroup)?.removeView(web)
      web.stopLoading()
      web.loadUrl("about:blank")
      web.clearHistory()
      web.removeAllViews()
      web.destroy()
    }
    webView = null
    errorView = null
    super.onDestroy()
  }

  private fun showError(title: String, detail: String) {
    val view = errorView ?: return
    view.text = "$title\n\n$detail"
    view.visibility = View.VISIBLE
  }

  private fun hideError() {
    errorView?.visibility = View.GONE
  }

  private fun showFatalError(title: String, detail: String) {
    val view = TextView(this).apply {
      setBackgroundColor(Color.WHITE)
      setTextColor(Color.DKGRAY)
      textSize = 16f
      gravity = android.view.Gravity.CENTER
      setPadding(48, 48, 48, 48)
      text = "$title\n\n$detail"
    }
    setContentView(view)
    SystemBarsCompat.apply(this, immersive)
  }

  private inner class BridgeWebViewClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(
      view: WebView,
      request: WebResourceRequest
    ): Boolean = handleNavigation(request.url)

    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
      handleNavigation(Uri.parse(url))

    override fun onPageStarted(
      view: WebView,
      url: String?,
      favicon: Bitmap?
    ) {
      hideError()
    }

    override fun onReceivedError(
      view: WebView,
      request: WebResourceRequest,
      error: WebResourceError
    ) {
      if (request.isForMainFrame) {
        showError(
          "Page could not be loaded",
          error.description?.toString() ?: "The WebView reported a loading error."
        )
      }
    }

    override fun onReceivedHttpError(
      view: WebView,
      request: WebResourceRequest,
      errorResponse: WebResourceResponse
    ) {
      if (request.isForMainFrame) {
        showError(
          "Page could not be loaded",
          "The server returned HTTP ${errorResponse.statusCode}."
        )
      }
    }

    override fun onReceivedSslError(
      view: WebView,
      handler: SslErrorHandler,
      error: SslError
    ) {
      handler.cancel()
      showError(
        "Secure connection failed",
        "The WebView could not verify the site's TLS certificate."
      )
    }

    override fun onRenderProcessGone(
      view: WebView,
      detail: RenderProcessGoneDetail
    ): Boolean {
      Log.e(
        TAG,
        "The WebView renderer process stopped (didCrash=${detail.didCrash()})."
      )
      (view.parent as? ViewGroup)?.removeView(view)
      view.destroy()
      if (webView === view) {
        webView = null
      }
      showError(
        "Page could not be displayed",
        "The WebView renderer stopped unexpectedly. Press back to leave."
      )
      return true
    }
  }

  /**
   * Keeps HTTPS navigation inside the WebView, blocks insecure or local
   * schemes, and hands other schemes to the system.
   */
  private fun handleNavigation(uri: Uri): Boolean =
    when (uri.scheme?.lowercase()) {
      "https", "blob" -> false
      "http" -> {
        showError(
          "Insecure navigation blocked",
          "The WebView fallback only opens HTTPS URLs."
        )
        true
      }
      "file", "content", "javascript", "data" -> true
      else -> {
        try {
          startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (error: Exception) {
          Log.w(TAG, "Could not open external URI: $uri", error)
        }
        true
      }
    }

  /**
   * Keeps API 30+ window-inset classes out of the main Activity so they are
   * only loaded on devices that provide them.
   */
  private object SystemBarsCompat {
    fun apply(activity: TwaWebViewActivity, immersive: Boolean) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Api30.apply(activity, immersive)
      } else {
        Legacy.apply(activity, immersive)
      }

      if (immersive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        activity.window.attributes = activity.window.attributes.apply {
          layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
      }
    }

    private object Api30 {
      @Suppress("DEPRECATION")
      fun apply(activity: TwaWebViewActivity, immersive: Boolean) {
        val window = activity.window
        window.setDecorFitsSystemWindows(!immersive)

        val bars = android.view.WindowInsets.Type.statusBars() or
          android.view.WindowInsets.Type.navigationBars()
        // Read the controller from the decor view, which also forces the decor
        // to exist before PhoneWindow.getInsetsController() is reached.
        window.decorView.windowInsetsController?.apply {
          if (immersive) {
            hide(bars)
            systemBarsBehavior =
              android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
          } else {
            show(bars)
          }
        }
      }
    }

    private object Legacy {
      @Suppress("DEPRECATION")
      fun apply(activity: TwaWebViewActivity, immersive: Boolean) {
        activity.window.decorView.systemUiVisibility = if (immersive) {
          View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        } else {
          View.SYSTEM_UI_FLAG_VISIBLE
        }
      }
    }
  }

  /**
   * Registers the API 33+ predictive-back callback without loading that class
   * on older devices. `onBackPressed()` remains the fallback path.
   */
  private object BackCompat {
    fun register(activity: TwaWebViewActivity) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Api33.register(activity)
      }
    }

    private object Api33 {
      fun register(activity: TwaWebViewActivity) {
        activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(
          android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
        ) {
          activity.handleBack()
        }
      }
    }
  }
}
