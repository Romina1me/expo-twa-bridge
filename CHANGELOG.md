# Changelog

## Unreleased

- Add an optional fullscreen WebView fallback: `fallback: 'webview'`. It is
  used only for browser/session/launch availability failures, loads the
  requested HTTPS URL in a dedicated Activity, hides both Android system bars
  in immersive mode, and handles WebView history with the Android back button.
  Strict Digital Asset Links failures and timeouts still never fall back.
- The WebView fallback has no PostMessage channel. When `postMessage` is
  requested and the WebView fallback opens, `onChannelError` reports
  `ERR_TWA_POSTMESSAGE_UNAVAILABLE` and `postMessage()` rejects with
  `ERR_TWA_POSTMESSAGE_NOT_READY`.
- `launch()` now resolves `{ mode: 'webview', verified: false }` when the
  WebView fallback opens. `fallback: 'custom-tabs'` remains the default.

## 0.1.0 (2026-11-22)

- Initial release.
- Launch HTTPS URLs in a genuine Android Trusted Web Activity (TWA) without a
  WebView or Expo WebBrowser.
- `verification` mode: `permissive` (default) starts a real TWA intent and lets
  Chrome perform its own Digital Asset Links verification and decide fullscreen
  vs browser UI; `strict` requires `handle_all_urls` verification before launch.
- `launch()` resolves with `{ mode, verified }`; a failed verification is never
  reported as verified.
- Strict verification makes three attempts, each with its own deadline, before
  rejecting with `ERR_TWA_VERIFICATION_FAILED` / `ERR_TWA_VERIFICATION_TIMEOUT`.
- Additional trusted origins for TWA navigation.
- Fullscreen display modes: `immersive` (default) hides browser controls and
  Android system bars; `default` keeps the system bars visible.
- Optional custom-tabs fallback (`fallback: 'custom-tabs'`, the default) for
  browser/environment launch failures, with `fallback: 'none'` to reject.
- Optional `browserPackage` to pin the hosting browser.
- Optional bidirectional `postMessage` channel over the same `CustomTabsSession`
  (also on the Custom Tabs fallback), with `use_as_origin` verification.
- Optional TWA splash screen (`splashScreen: { image, backgroundColor }`) using
  AndroidX `setSplashScreenParams`.
- Expo config plugin that adds the Android 11+ package visibility queries, the
  `androidx.browser.customtabs.PostMessageService` service, and the FileProvider
  used to transfer the splash image to Chrome.
