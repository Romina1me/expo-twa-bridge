# expo-twa-bridge

A strict Expo native module for opening HTTPS websites in an Android Trusted
Web Activity (TWA) through Chrome, without a WebView.

The launcher refuses to open anything other than a genuine, verified Trusted
Web Activity. It never falls back to Expo WebBrowser, a Custom Tab, a WebView,
or a regular browser activity.

It can also open a bidirectional `postMessage` channel on the **same**
`CustomTabsSession` used to launch the TWA.

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

The plugin adds the Android 11+ package visibility queries and the
`androidx.browser.customtabs.PostMessageService` service.

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
  // Optional: enable the PostMessage channel.
  // sourceOrigin defaults to the URL origin, targetOrigin to sourceOrigin.
  postMessage: {
    targetOrigin: 'https://example.com',
  },
});
```

The launch promise resolves only after every requested origin has passed
Digital Asset Links verification and the TWA intent has been handed to the
browser. It throws a `TwaLaunchError` with one of the following codes:

| Code                                    | Reason                                                                 |
| --------------------------------------- | ---------------------------------------------------------------------- |
| `ERR_TWA_NO_ACTIVITY`                   | No foreground Android `Activity` to launch from.                       |
| `ERR_TWA_INVALID_URL`                   | The URL is not an absolute HTTPS URL.                                  |
| `ERR_TWA_INVALID_DISPLAY_MODE`          | `displayMode` is not `immersive` or `default`.                         |
| `ERR_TWA_INVALID_ORIGIN`                | An additional origin is not an absolute HTTPS origin.                  |
| `ERR_TWA_NO_BROWSER`                    | No browser supporting Trusted Web Activities is installed.             |
| `ERR_TWA_VERIFICATION_FAILED`           | Digital Asset Links verification failed for an origin (missing file).  |
| `ERR_TWA_VERIFICATION_TIMEOUT`          | The browser did not finish verification in time.                       |
| `ERR_TWA_LAUNCH_FAILED`                 | Session setup or the TWA launch itself failed.                         |
| `ERR_TWA_POSTMESSAGE_INVALID_ORIGIN`    | A PostMessage origin is not an absolute HTTPS origin.                  |
| `ERR_TWA_POSTMESSAGE_VERIFICATION_FAILED` | `use_as_origin` Digital Asset Links check failed.                    |
| `ERR_TWA_POSTMESSAGE_UNAVAILABLE`       | Browser rejected the channel (old Chrome, missing service).            |
| `ERR_TWA_POSTMESSAGE_NOT_READY`         | `postMessage()` called before the channel is ready.                    |
| `ERR_TWA_POSTMESSAGE_FAILED`            | The browser rejected the message.                                      |
| `ERR_TWA_POSTMESSAGE_TIMEOUT`           | The channel did not become ready in time.                              |
| `ERR_TWA_UNSUPPORTED`                   | Called on a platform other than Android.                               |

```ts
import { ExpoTwaBridge, TwaLaunchError } from 'expo-twa-bridge';

try {
  await ExpoTwaBridge.launch({ url: 'https://example.com' });
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
2. Binds its `CustomTabsService` and creates a single `CustomTabsSession`.
3. Calls `CustomTabsSession.validateRelationship()` with
   `CustomTabsService.RELATION_HANDLE_ALL_URLS` for the requested origin and
   every `additionalTrustedOrigins` entry.
4. Launches only when **all** results are `validated = true`, using
   `TrustedWebActivityIntentBuilder`.

There are no `fallback` or `enableFallback` options. If verification fails, no
browser UI is opened at all; the promise rejects with
`ERR_TWA_VERIFICATION_FAILED` (or another explicit error).

The same session is reused for PostMessage. No second session, browser
connection, WebView, or Custom Tab is created.

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

## PostMessage

PostMessage runs over the existing TWA session and requires browser
verification with the `delegate_permission/common.use_as_origin` Digital Asset
Links relation.

### JavaScript API

```ts
import { ExpoTwaBridge } from 'expo-twa-bridge';

const subscriptions = [
  ExpoTwaBridge.addChannelReadyListener(() => {
    // The web page is ready to receive messages.
  }),
  ExpoTwaBridge.addChannelClosedListener(() => {
    // Next launch, service disconnect, or module destruction.
  }),
  ExpoTwaBridge.addChannelErrorListener(({ code, message }) => {
    console.warn(code, message);
  }),
  ExpoTwaBridge.addMessageListener(({ data }) => {
    // Raw string sent from the web page.
  }),
];

await ExpoTwaBridge.launch({
  url: 'https://example.com',
  postMessage: {},
});

await ExpoTwaBridge.postMessage('PING');
// later: subscriptions.forEach((sub) => sub.remove());
```

`postMessage()` rejects with `ERR_TWA_POSTMESSAGE_NOT_READY` until
`addChannelReadyListener` has fired. Messages are raw strings; typed envelopes
can be layered on top with `JSON.stringify` / `JSON.parse`.

### Native → Web and Web → Native

- Native → Web: `CustomTabsSession.postMessage(message, null)`.
- Web → Native: `CustomTabsCallback.onPostMessage(message, extras)`.

The channel is requested only after:

1. the TWA intent was launched,
2. `onNavigationEvent(NAVIGATION_FINISHED)` fired, and
3. `validateRelationship(RELATION_USE_AS_ORIGIN, sourceOrigin)` returned
   `true`.

A 25-second timeout produces `ERR_TWA_POSTMESSAGE_TIMEOUT` if the channel never
becomes ready. Channel failures do not close the TWA itself; they are reported
as events.

### Web page side

The page must accept the first message and keep the provided `MessagePort`:

```js
window.addEventListener('message', (event) => {
  const port = event.ports[0];
  if (!port) return;

  port.onmessage = (messageEvent) => {
    const message = messageEvent.data;

    if (message === 'PING') {
      port.postMessage('PONG');
    }
  };

  // Native → Web initial message.
  port.postMessage('PING');
});
```

## Requirements

- Android only.
- Chrome or another browser that supports Trusted Web Activities.
- Chrome 115+ for TWA PostMessage.
- The page must be served over HTTPS.
- The website must verify the app through
  [Digital Asset Links](https://developers.google.com/digital-asset-links):
  host `/.well-known/assetlinks.json` with the app's package name and signing
  certificate SHA-256 fingerprint.

For fullscreen TWA and PostMessage, include both relations:

```json
[{
  "relation": [
    "delegate_permission/common.handle_all_urls",
    "delegate_permission/common.use_as_origin"
  ],
  "target": {
    "namespace": "android_app",
    "package_name": "expo.modules.twabridge.example",
    "sha256_cert_fingerprints": ["AA:BB:CC:..."]
  }
}]
```

## Diagnosing verification failures

The app checks verification before launching, so a failure is reported as
`ERR_TWA_VERIFICATION_FAILED` instead of opening a browser. PostMessage
verification failures are reported through `onChannelError` as
`ERR_TWA_POSTMESSAGE_VERIFICATION_FAILED`.

1. Fetch the file from the device's network:
   `curl https://example.com/.well-known/assetlinks.json`.
2. Check the JSON contains the correct package, certificate SHA-256, and
   relations. Debug and release builds usually have different keys.
3. Fingerprint the installed APK:
   `apksigner verify --print-certs app-debug.apk`.
4. Watch the module logs:
   `adb logcat -s ExpoTwaBridge` reports the provider, display mode, origins,
   `handle_all_urls` results, `use_as_origin` results, and channel state.
5. Watch Chrome's diagnostics:
   `adb logcat | grep -iE "digitalassetlinks|digital_asset_links|trustedwebactivity"`.

Verification is performed by the browser against the live origin. If Chrome
returns `404` for `assetlinks.json`, the module reports
`ERR_TWA_VERIFICATION_FAILED` for that origin.

## Limitations

- The TWA page must be on an origin verified with `handle_all_urls`; PostMessage
  additionally requires `use_as_origin`.
- The channel is created once per launch, after the first navigation finishes.
  There is no automatic reconnection; relaunch the TWA after a service
  disconnect.
- Messages sent before `onChannelReady` are rejected, not queued.
- Fullscreen/immersive behavior and strict verification are unchanged.

## Roadmap

- Typed message envelopes and native command handlers.
- Reconnection strategy for long-lived channels.
- PostMessage on additional trusted origins.
