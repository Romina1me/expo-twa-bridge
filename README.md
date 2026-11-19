# expo-twa-bridge

A strict Expo native module for opening HTTPS websites in an Android Trusted
Web Activity (TWA) through Chrome, without a WebView.

The launcher refuses to open anything other than a genuine, verified Trusted
Web Activity. It never falls back to Expo WebBrowser, a Custom Tab, a WebView,
or a regular browser activity.

## Installation

```sh
npx expo install expo-twa-bridge
```

Add the config plugin to your app config:

```json
{
  "expo": {
    "plugins": ["expo-twa-bridge"]
  }
}
```

Then regenerate the native project:

```sh
npx expo prebuild
```

## Usage

```ts
import { ExpoTwaBridge } from 'expo-twa-bridge';

await ExpoTwaBridge.launch({
  url: 'https://example.com',
  // 'immersive' (default) hides browser controls and Android system bars.
  // 'default' keeps the system bars visible.
  displayMode: 'immersive',
  // Extra HTTPS origins that must also pass Digital Asset Links verification.
  additionalTrustedOrigins: ['https://app.example.com'],
});
```

The promise resolves only after every requested origin has passed Digital Asset
Links verification and the TWA intent has been handed to the browser. It
throws a `TwaLaunchError` with one of the following codes:

| Code                           | Reason                                                                 |
| ------------------------------ | ---------------------------------------------------------------------- |
| `ERR_TWA_NO_ACTIVITY`          | No foreground Android `Activity` to launch from.                       |
| `ERR_TWA_INVALID_URL`          | The URL is not an absolute HTTPS URL.                                  |
| `ERR_TWA_INVALID_DISPLAY_MODE` | `displayMode` is not `immersive` or `default`.                         |
| `ERR_TWA_INVALID_ORIGIN`       | An additional origin is not an absolute HTTPS origin.                  |
| `ERR_TWA_NO_BROWSER`           | No browser supporting Trusted Web Activities is installed.             |
| `ERR_TWA_VERIFICATION_FAILED`  | Digital Asset Links verification failed for an origin (missing file).  |
| `ERR_TWA_VERIFICATION_TIMEOUT` | The browser did not finish verification in time.                       |
| `ERR_TWA_LAUNCH_FAILED`        | Session setup or the TWA launch itself failed.                         |
| `ERR_TWA_UNSUPPORTED`          | Called on a platform other than Android.                               |

```ts
import { ExpoTwaBridge, TwaLaunchError } from 'expo-twa-bridge';

try {
  await ExpoTwaBridge.launch({ url: 'https://figfit.ir' });
} catch (error) {
  if (
    error instanceof TwaLaunchError &&
    error.code === 'ERR_TWA_VERIFICATION_FAILED'
  ) {
    // The site does not publish a valid assetlinks.json. Nothing was opened.
    console.warn(error.message);
  }
}
```

## Strict verification (no fallback)

Before launching, the module:

1. Selects a browser that advertises Trusted Web Activity support.
2. Binds its `CustomTabsService` and creates a `CustomTabsSession`.
3. Calls `CustomTabsSession.validateRelationship()` with
   `CustomTabsService.RELATION_HANDLE_ALL_URLS` for the requested origin and
   every `additionalTrustedOrigins` entry.
4. Launches only when **all** results are `validated = true`, using
   `TrustedWebActivityIntentBuilder`.

There are no `fallback` or `enableFallback` options. If verification fails, no
browser UI is opened at all; the promise rejects with
`ERR_TWA_VERIFICATION_FAILED` (or another explicit error).

The module keeps ownership of the `CustomTabsSession` so future PostMessage and
MessagePort support can reuse this connection.

## Fullscreen display modes

`immersive` maps to
`TrustedWebActivityDisplayMode.ImmersiveMode(isSticky = true, cutout = SHORT_EDGES)`
and is the default. It hides both browser controls and the Android system bars.
`default` maps to `TrustedWebActivityDisplayMode.DefaultMode`, which keeps the
system bars visible.

This is the AndroidX TWA display-mode API, passed through
`TrustedWebActivityIntentBuilder.setDisplayMode()` — not a WebView and not
manual `View.SYSTEM_UI_FLAG_*` manipulation.

The module does not implement a splash screen. Keep using Expo's splash screen
configuration separately.

## Requirements

- Android only.
- Chrome or another browser that supports Trusted Web Activities.
- The page must be served over HTTPS.
- The website must verify the app through
  [Digital Asset Links](https://developers.google.com/digital-asset-links):
  host `/.well-known/assetlinks.json` with the app's package name and signing
  certificate SHA-256 fingerprint, using the
  `delegate_permission/common.handle_all_urls` relation.

## Diagnosing verification failures

The app now checks verification before launching, so a failure is reported as
`ERR_TWA_VERIFICATION_FAILED` instead of opening a browser.

1. Fetch the file from the device's network:
   `curl https://example.com/.well-known/assetlinks.json`.
2. Check the JSON contains a statement with
   `"relation": ["delegate_permission/common.handle_all_urls"]`,
   `"namespace": "android_app"`, `"package_name": "expo.modules.twabridge.example"`
   (or your published package), and the SHA-256 of the certificate that signed
   the installed APK. Debug and release builds usually have different keys.
3. Fingerprint the installed APK:
   `apksigner verify --print-certs app-debug.apk`.
4. Watch the module logs:
   `adb logcat -s ExpoTwaBridge` reports the selected provider, display mode,
   origins, and each `validated=true/false` result.
5. Watch Chrome's own diagnostics:
   `adb logcat | grep -iE "digitalassetlinks|digital_asset_links|trustedwebactivity"`.

Verification is performed by the browser against the live origin. If Chrome
returns `404` for `assetlinks.json`, the module reports
`ERR_TWA_VERIFICATION_FAILED` for that origin.

The config plugin only adds the Android 11+ package visibility declarations
needed to discover browsers and Custom Tabs services. No website SDK, web
plugin, or manifest URL is required.

## Roadmap

This milestone only launches the TWA. Future milestones will add bidirectional
communication using Custom Tabs PostMessage and `MessagePort` on the same
session.
