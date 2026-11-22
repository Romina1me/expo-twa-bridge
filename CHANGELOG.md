# Changelog

## Unreleased

- Add a configurable `verification` mode. `permissive` (default) launches the
  TWA intent without the module's own pre-check and lets Chrome perform its
  Digital Asset Links verification and decide fullscreen vs browser UI;
  `strict` keeps the previous behavior of requiring verification before launch.
- `launch()` now resolves with `{ mode, verified }`. `verified` is `true` only
  for a strict-mode TWA launch; a failed verification is never reported as
  successful.
- Strict verification makes three attempts, each with its own deadline, so a
  `false` result, a rejected request, or an unresponsive browser is retried
  before the launch rejects.
- Add optional TWA splash screen support (`splashScreen: { image,
  backgroundColor }`) using AndroidX `setSplashScreenParams`. The config plugin
  now declares the FileProvider used to transfer the image to Chrome.
- Packaged the PostMessage options into a single native argument so the Expo
  module bridge stays within its argument-arity limit.

## 0.1.0 (2026-11-21)

- Initial release.
- Launch HTTPS URLs in a genuine, verified Android Trusted Web Activity (TWA)
  without a WebView.
- Strict Digital Asset Links verification (`handle_all_urls`) for the URL and
  every `additionalTrustedOrigins` entry before launching; verification
  failures and timeouts never fall back.
- Fullscreen display modes: `immersive` (default) hides browser controls and
  Android system bars; `default` keeps the system bars visible.
- Optional custom-tabs fallback (`fallback: 'custom-tabs'`, the default) for
  browser/environment launch failures, with `fallback: 'none'` to reject.
- Optional `browserPackage` to pin the hosting browser.
- Optional bidirectional `postMessage` channel over the same `CustomTabsSession`
  (also on the Custom Tabs fallback), with `use_as_origin` verification.
- Expo config plugin that adds the Android 11+ package visibility queries and
  the `androidx.browser.customtabs.PostMessageService` service.
