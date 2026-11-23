# expo-twa-bridge

A strict-first Expo native module for opening HTTPS websites in an Android
Trusted Web Activity (TWA) through Chrome.

The package launches a genuine Trusted Web Activity. With
`verification: 'strict'` it requires Digital Asset Links verification to pass
before the browser is launched; with the default `verification: 'permissive'`
it launches the TWA intent and lets Chrome perform its own verification, so
Chrome decides whether to show the URL fullscreen or with browser UI. If the
browser cannot host a TWA, it can instead open the URL in Android Custom Tabs
with the browser chrome visible (the default), or in a dedicated fullscreen
WebView Activity when `fallback: 'webview'` is explicitly configured. It never
uses Expo WebBrowser or a regular browser activity, and a failed verification
is never reported as verified.

It can also open a bidirectional `postMessage` channel on the **same**
`CustomTabsSession` used to launch the TWA (and, on the Custom Tabs fallback,
the same session is used there). The WebView fallback has no PostMessage
channel.

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
  // 'permissive' (default) launches the TWA intent even when verification
  // cannot be confirmed and lets Chrome decide on fullscreen vs browser UI.
  // 'strict' requires Digital Asset Links verification before launching.
  verification: 'permissive',
  // 'immersive' (default) hides browser controls and Android system bars.
  // 'default' keeps the system bars visible.
  displayMode: 'immersive',
  // Extra HTTPS origins that should also be shown without browser controls.
  additionalTrustedOrigins: ['https://app.example.com'],
  // Optional: when the browser cannot host a TWA, fall back to Android Custom
  // Tabs (chrome visible, the default) or to a dedicated fullscreen WebView.
  // 'none' rejects instead. The WebView fallback has no PostMessage channel.
  fallback: 'custom-tabs', // or 'webview'
  // Optional: pin the browser package that hosts the TWA (e.g. Chrome).
  // browserPackage: 'com.android.chrome',
  // Optional: splash image shown by Chrome while the TWA loads. `image` accepts
  // a bundled asset (require) or an image URI. Requires the config plugin.
  splashScreen: {
    image: require('./assets/splash.png'),
    backgroundColor: '#0B7285',
  },
  // Optional: enable the PostMessage channel.
  // sourceOrigin defaults to the URL origin, targetOrigin to sourceOrigin.
  postMessage: {
    targetOrigin: 'https://example.com',
  },
});
```

The launch promise resolves with the surface that opened the URL and whether
this module confirmed verification:

```ts
const { mode, verified } = await ExpoTwaBridge.launch({
  url: 'https://example.com',
  verification: 'strict',
});
// mode: 'twa' | 'custom-tabs' | 'webview'
// verified: true only for a strict-mode TWA launch
```

It throws a `TwaLaunchError` with one of the following codes:

| Code                                    | Reason                                                                 |
| --------------------------------------- | ---------------------------------------------------------------------- |
| `ERR_TWA_NO_ACTIVITY`                   | No foreground Android `Activity` to launch from.                       |
| `ERR_TWA_INVALID_URL`                   | The URL is not an absolute HTTPS URL.                                  |
| `ERR_TWA_INVALID_DISPLAY_MODE`          | `displayMode` is not `immersive` or `default`.                         |
| `ERR_TWA_INVALID_FALLBACK_MODE`         | `fallback` is not `custom-tabs`, `webview`, or `none`.                 |
| `ERR_TWA_INVALID_VERIFICATION_MODE`     | `verification` is not `strict` or `permissive`.                        |
| `ERR_TWA_INVALID_SPLASH_SCREEN`         | `splashScreen.image` could not be resolved to an image URI.            |
| `ERR_TWA_INVALID_ORIGIN`                | An additional origin is not an absolute HTTPS origin.                  |
| `ERR_TWA_NO_BROWSER`                    | No browser supporting Trusted Web Activities is installed.             |
| `ERR_TWA_BROWSER_UNAVAILABLE`           | The requested `browserPackage` is missing or has no Custom Tabs service. |
| `ERR_TWA_CUSTOM_TABS_UNAVAILABLE`       | No browser supporting Android Custom Tabs is installed.                |
| `ERR_TWA_VERIFICATION_FAILED`           | `strict` mode: Digital Asset Links verification failed for the origin. |
| `ERR_TWA_VERIFICATION_UNAVAILABLE`      | `strict` mode: the browser rejected the verification request.          |
| `ERR_TWA_VERIFICATION_TIMEOUT`          | `strict` mode: the browser did not finish verification in time.        |
| `ERR_TWA_FALLBACK_FAILED`               | The Custom Tabs or WebView fallback could not be launched.             |
| `ERR_TWA_LAUNCH_FAILED`                 | Session setup or the TWA launch itself failed.                         |
| `ERR_TWA_POSTMESSAGE_INVALID_ORIGIN`    | A PostMessage origin is not an absolute HTTPS origin.                  |
| `ERR_TWA_POSTMESSAGE_VERIFICATION_FAILED` | `use_as_origin` Digital Asset Links check failed.                    |
| `ERR_TWA_POSTMESSAGE_UNAVAILABLE`       | Browser rejected the channel, or the WebView fallback is active.       |
| `ERR_TWA_POSTMESSAGE_NOT_READY`         | `postMessage()` called before the channel is ready, or on the WebView fallback. |
| `ERR_TWA_POSTMESSAGE_FAILED`            | The browser rejected the message.                                      |
| `ERR_TWA_POSTMESSAGE_TIMEOUT`           | The channel did not become ready in time.                              |
| `ERR_TWA_UNSUPPORTED`                   | Called on a platform other than Android.                               |

```ts
import { ExpoTwaBridge, TwaLaunchError } from 'expo-twa-bridge';

try {
  await ExpoTwaBridge.launch({
    url: 'https://example.com',
    verification: 'strict',
  });
} catch (error) {
  if (
    error instanceof TwaLaunchError &&
    error.code === 'ERR_TWA_VERIFICATION_FAILED'
  ) {
    // Strict verification failed for the requested origin. Nothing was opened.
    console.warn(error.message);
  }
}
```

## Verification modes

`verification` controls whether the module gates the launch on its own Digital
Asset Links pre-check:

- **`permissive` (default)** — the module validates the URL and origins, binds
  a browser session, and launches the real `TrustedWebActivityIntent`. Chrome
  still performs its own Digital Asset Links verification when it handles that
  intent: it shows the URL fullscreen **only** when the origin's
  `assetlinks.json` matches, otherwise it keeps browser UI. Verification
  failures and timeouts are not launch errors, and an unverified launch is
  reported as `verified: false`.
- **`strict`** — the module calls `validateRelationship()` with
  `RELATION_HANDLE_ALL_URLS` for the URL origin and every additional origin, and
  only launches when **all** results are `validated = true`. Failures and
  timeouts reject with a typed error and never fall back. A successful strict
  TWA launch resolves with `verified: true`.

Neither mode bypasses Android or Chrome verification: permissive only stops
*this module* from refusing to launch. A failed verification is never reported
as a successful verification. PostMessage stays strict in both modes — the
`use_as_origin` relation must verify before the channel opens.

## Launch and fallback

Before launching a TWA, the module:

1. Resolves a browser — an explicit `browserPackage`, or the system default.
2. Binds its `CustomTabsService` and creates a single `CustomTabsSession`.
3. In `strict` mode, calls `CustomTabsSession.validateRelationship()` with
   `CustomTabsService.RELATION_HANDLE_ALL_URLS` for the requested origin and
   every `additionalTrustedOrigins` entry.
4. In `strict` mode, launches only when **all** results are `validated = true`.
   In `permissive` mode it launches without that gate and lets Chrome verify
   during navigation. Either way it uses `TrustedWebActivityIntentBuilder`;
   the promise resolves with `{ mode: 'twa', verified }`.

`validateRelationship()` is resolved by the browser, which fetches the origin's
`assetlinks.json` and checks that this app's package name and signing
certificate SHA-256 appear for the `handle_all_urls` relation. That browser-side
check is what establishes the app-to-origin relationship before anything is
opened in `strict` mode.

The browser can report a transient negative result on the first cold request
(for example the first launch after installing the app) even when the file is
correct. In `strict` mode the module makes up to **three** verification
attempts; each attempt has its own 10-second deadline, so a `false` result, a
rejected request, or an unresponsive browser consumes an attempt and is
retried before the launch is rejected.

When the browser cannot host a TWA because of a browser or environment problem
(no TWA-capable browser, bind failure, rejected session, launch exception) and
`fallback` is `'custom-tabs'` (the default), the same URL is opened in Android
Custom Tabs with the browser chrome visible, and the promise resolves with
`{ mode: 'custom-tabs', verified: false }`. Set `fallback: 'none'` to reject
instead. PostMessage keeps working on this fallback through the same Custom Tabs
session.

When `fallback` is `'webview'`, those same availability failures open the URL
in a dedicated fullscreen Android WebView Activity instead, and the promise
resolves with `{ mode: 'webview', verified: false }`. The WebView fallback:

- loads the original requested HTTPS URL;
- honors `displayMode`: `immersive` hides both Android system bars, `default`
  keeps them visible;
- keeps HTTPS navigation inside the WebView, blocks cleartext `http://`
  navigation, cancels TLS errors, and shows an in-Activity error message when
  the main frame fails to load;
- uses the Android back button for WebView history before finishing the
  Activity; and
- disables file/content access and never exposes a JavaScript interface.

The WebView fallback has **no** PostMessage channel. If `postMessage` was
requested, `onChannelError` reports `ERR_TWA_POSTMESSAGE_UNAVAILABLE` and
`postMessage()` rejects with `ERR_TWA_POSTMESSAGE_NOT_READY`. Custom Tabs or a
TWA is required for PostMessage. Use `fallback: 'none'` to reject instead of
opening any fallback.

In `strict` mode, Digital Asset Links verification failures and timeouts
**never** fall back — the promise always rejects with
`ERR_TWA_VERIFICATION_FAILED` / `ERR_TWA_VERIFICATION_TIMEOUT` and no browser
UI or WebView is opened. In `permissive` mode verification cannot reject the
launch, so only real browser/launch failures use the fallback policy. Expo
WebBrowser and a regular browser activity are never used; the WebView is used
only when `fallback: 'webview'` is configured.

An explicit `browserPackage` is never silently replaced: if it is missing or
cannot host a TWA, the configured `fallback` policy applies.

`launch()` resolves with the surface that actually opened the URL and whether
verification was confirmed:

```ts
import { ExpoTwaBridge } from 'expo-twa-bridge';

const { mode, verified } = await ExpoTwaBridge.launch({
  url: 'https://example.com',
  verification: 'strict',
});
// mode: 'twa' | 'custom-tabs' | 'webview'
// verified: true only when strict verification passed and a TWA opened

if (mode === 'twa' && verified) {
  // Verified fullscreen Trusted Web Activity, browser chrome hidden.
  onVerifiedTwaOpened();
} else if (mode === 'twa') {
  // A TWA intent opened, but verification was not confirmed by this module
  // (permissive mode). Chrome decided how to present the URL.
  onTwaOpened();
} else if (mode === 'custom-tabs') {
  // The Custom Tabs fallback opened the URL with the browser UI visible.
  onFallbackOpened();
} else {
  // The fullscreen WebView fallback opened the URL. It is not a verified TWA
  // and has no PostMessage channel.
  onWebViewFallbackOpened();
}
```

`verified` is always `false` in `permissive` mode and for `custom-tabs` and
`webview` results, so an unverified navigation is never reported as verified.

Whenever `mode` is `'custom-tabs'` or `'webview'`, the session is **not** a
verified fullscreen TWA — the fallback was used only because the browser could
not host a TWA. The WebView fallback is fullscreen but still unverified. Set
`fallback: 'none'` if a non-TWA surface must never be opened.

## Fullscreen display modes

`immersive` maps to
`TrustedWebActivityDisplayMode.ImmersiveMode(isSticky = true, cutout = SHORT_EDGES)`
and is the default. It hides both browser controls and the Android system bars.
`default` maps to `TrustedWebActivityDisplayMode.DefaultMode`, which keeps the
system bars visible.

For a TWA this is the AndroidX TWA display-mode API, passed through
`TrustedWebActivityIntentBuilder.setDisplayMode()`. The WebView fallback honors
the same setting with the equivalent immersive system-bar flags in its
dedicated Activity.

## Splash screen

Pass `splashScreen` to show an image while Chrome loads the Trusted Web
Activity. This covers the moment where Chrome's own Digital Asset Links check
would otherwise flash the URL bar before switching to fullscreen. The splash
screen is a TWA feature; the WebView fallback ignores it.

```ts
await ExpoTwaBridge.launch({
  url: 'https://example.com',
  splashScreen: {
    image: require('./assets/splash.png'),
    backgroundColor: '#0B7285',
  },
});
```

- `image` accepts a bundled asset (`require('./assets/splash.png')`, resolved
  through React Native's asset resolver) or an image URI (`https`, `file`,
  `content`, `data:`, or an Android resource name).
- The image is drawn at its natural size, **centered**, on top of
  `backgroundColor` (hex `#RRGGBB` / `#AARRGGBB`, default white). It is not
  stretched.
- The image is copied to the app's `files/twa_splash/` directory and handed to
  Chrome through the `FileProvider` declared by the package's config plugin, so
  it works in debug and release builds. Run `npx expo prebuild` after installing
  the package.
- If the browser does not support TWA splash screens (or the image cannot be
  loaded), the launch proceeds without a splash screen. No error is thrown.
- This is the AndroidX `TrustedWebActivityIntentBuilder.setSplashScreenParams`
  API. It does not bypass or replace Chrome's verification.

## PostMessage

PostMessage runs over the existing TWA session and requires browser
verification with the `delegate_permission/common.use_as_origin` Digital Asset
Links relation.

### WebView fallback

PostMessage is not available with `fallback: 'webview'`. The WebView Activity
does not create a `CustomTabsSession`, does not inject a JavaScript interface,
and does not emulate the channel. When the WebView fallback is used,
`onChannelError` reports `ERR_TWA_POSTMESSAGE_UNAVAILABLE` and `postMessage()`
rejects with `ERR_TWA_POSTMESSAGE_NOT_READY`. Use `fallback: 'custom-tabs'` or a
TWA when PostMessage is required. A secure WebView PostMessage implementation
would have to be added explicitly; this package deliberately does not expose
one.

`additionalTrustedOrigins` controls which origins the **TWA may navigate to**
(each must pass `handle_all_urls` verification — this module verifies them in
`strict` mode and Chrome verifies them itself in `permissive` mode); it does
**not** enable PostMessage for those origins. The current PostMessage
implementation supports only the configured `sourceOrigin` / `targetOrigin`
pair (both default to the URL origin). PostMessage on additional trusted
origins is not supported.

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
`addChannelReadyListener` has fired, and whenever the WebView fallback opened
the URL. Messages are raw strings; typed envelopes can be layered on top with
`JSON.stringify` / `JSON.parse`.

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

Trusted Web Activity PostMessage hands the page a `MessagePort` from the
verified native app. The page must accept the first message and keep the
provided port:

```js
window.addEventListener('message', (event) => {
  const port = event.ports[0];
  if (!port) return;

  port.onmessage = (messageEvent) => {
    let frame;
    try {
      frame = JSON.parse(messageEvent.data);
    } catch {
      return; // Not one of our envelopes.
    }
    // React only to messages that match the expected app protocol.
    if (frame.type !== 'PING') return;
    port.postMessage(JSON.stringify({ type: 'PONG' }));
  };

  // Native → Web initial message.
  port.postMessage(JSON.stringify({ type: 'PING' }));
});
```

Do not treat every `message` event as coming from the native bridge just because
`event.ports[0]` is present: the page may also receive unrelated `postMessage`
events (for example from its own scripts or embeds) on the same `window`.
Neither checking `event.ports[0]` nor matching a message type authenticates the
sender — the envelope in the example only gates on an application-level
protocol, it does not prove which app (or other caller) produced a message.
Treat every received message as untrusted application data.

Also, do not rely on `event.origin` to authenticate the native sender on this
handshake: the TWA PostMessage handshake does not guarantee that `event.origin`
reflects the app origin (Chromium may report an opaque or empty origin), so an
`event.origin` check would be misleading rather than secure. Prefer the
application-level envelope.

The real identity relationship is established by Digital Asset Links. Before the
channel is opened, the browser verifies
`delegate_permission/common.use_as_origin` for the configured source origin
against the app's `assetlinks.json` (see the channel steps above). That relation
binds this application (identified by its Android package name and signing
certificate SHA-256 fingerprint) to the configured origin, telling the browser
it may open a message channel for that origin. It verifies the
application-to-origin relationship; it does not authenticate individual
messages, and the web page cannot re-derive that identity from a message.

Finally, because the channel lives in the page's origin, any script that runs in
that origin can read and send bridge messages. Avoid loading untrusted or
third-party scripts into a page that uses this bridge — the bridge capability is
effectively exposed to the page's code.

## Requirements

- Android only.
- Chrome or another browser that supports Trusted Web Activities.
- TWA PostMessage additionally requires a browser that supports the TWA
  PostMessage API (for example Chrome 115+). Verifying the TWA does not imply
  the browser also supports PostMessage.
- `fallback: 'webview'` requires an Android System WebView provider (normally
  installed on Android; Chrome or another browser can provide it). The WebView
  fallback does not require a TWA-capable browser.
- The page must be served over HTTPS.
- For a verified fullscreen TWA (and for `verification: 'strict'`), the website
  must verify the app through
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

In `strict` mode the app checks verification before launching, so a failure is
reported as `ERR_TWA_VERIFICATION_FAILED` instead of opening a browser. In
`permissive` mode (the default) a verification failure is not a launch error;
the browser opens the URL and decides whether to show fullscreen or browser UI,
and the result is `verified: false`. PostMessage verification failures are
reported through `onChannelError` as
`ERR_TWA_POSTMESSAGE_VERIFICATION_FAILED` in both modes.

Strict verification failures and timeouts never use `fallback: 'custom-tabs'`
or `fallback: 'webview'`; only browser/session/launch availability failures do.

1. Fetch the file from the device's network:
   `curl https://example.com/.well-known/assetlinks.json`.
2. Check the JSON contains the correct package, certificate SHA-256, and
   relations. Debug and release builds usually have different keys.
3. Fingerprint the installed APK:
   `apksigner verify --print-certs app-debug.apk`.
4. Watch the module logs:
   `adb logcat -s ExpoTwaBridge` reports the provider, display mode, origins,
   verification mode, `handle_all_urls` results, `use_as_origin` results, and
   channel state.
5. Watch Chrome's diagnostics:
   `adb logcat | grep -iE "digitalassetlinks|digital_asset_links|trustedwebactivity"`.

Verification is performed by the browser against the live origin. In `strict`
mode, if Chrome returns `404` for `assetlinks.json`, the module reports
`ERR_TWA_VERIFICATION_FAILED` for that origin. Use `verification: 'strict'`
while diagnosing to surface a mismatch as an error.

Common causes of `ERR_TWA_VERIFICATION_FAILED`:

- Missing or inaccessible `assetlinks.json` at `/.well-known/assetlinks.json`
  (HTTP 404/403, or unreachable from the device).
- Incorrect Android package name in `target.package_name`.
- Incorrect signing certificate SHA-256 fingerprint in
  `target.sha256_cert_fingerprints` (debug and release builds usually have
  different keys).
- Missing the required Digital Asset Links relation
  (`delegate_permission/common.handle_all_urls`, and
  `delegate_permission/common.use_as_origin` when PostMessage is enabled).

## Limitations

- PostMessage is limited to the single configured `sourceOrigin` / `targetOrigin`
  pair; `additionalTrustedOrigins` does not create PostMessage channels for
  those origins.
- The TWA page must be on an origin verified with `handle_all_urls`; PostMessage
  additionally requires `use_as_origin`.
- The channel is created once per launch, after the first navigation finishes.
  There is no automatic reconnection; relaunch the TWA after a service
  disconnect.
- Messages sent before `onChannelReady` are rejected, not queued.
- The WebView fallback has no PostMessage channel and is never reported as a
  verified TWA. It is used only for browser/session/launch availability
  failures, never for verification failures.
- Permissive mode does not make Chrome trust an origin; Chrome still verifies
  the URL and may keep browser UI. Use `verification: 'strict'` when a verified
  fullscreen TWA is mandatory.

## Roadmap

- Typed message envelopes and native command handlers.
- Reconnection strategy for long-lived channels.
- PostMessage on additional trusted origins.
