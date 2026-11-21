# Changelog

## 0.1.0 (2026-11-21)

- Initial release.
- Launch HTTPS URLs in a genuine, verified Android Trusted Web Activity (TWA)
  without a WebView.
- Strict Digital Asset Links verification (`handle_all_urls`) for the URL and
  every `additionalTrustedOrigins` entry before launching; verification
  failures and timeouts never fall back.
- Optional custom-tabs fallback (`fallback: 'custom-tabs'`, the default) for
  browser/environment launch failures, with `fallback: 'none'` to reject.
- Optional `browserPackage` to pin the hosting browser.
- Optional bidirectional `postMessage` channel over the same `CustomTabsSession`
  (also on the Custom Tabs fallback), with `use_as_origin` verification.
- Expo config plugin that adds the Android 11+ package visibility queries and
  the `androidx.browser.customtabs.PostMessageService` service.