# expo-twa-bridge

A reusable Expo native module for opening HTTPS websites in an Android
Trusted Web Activity (TWA) through Chrome, without a WebView.

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
  url: 'https://figfit.ir',
});
```

The promise resolves once the Trusted Web Activity has been handed off to the
browser. It rejects with one of the following codes:

| Code                    | Reason                                               |
| ----------------------- | ---------------------------------------------------- |
| `ERR_TWA_NO_ACTIVITY`   | No foreground Android `Activity` to launch from.     |
| `ERR_TWA_INVALID_URL`   | The URL is not an absolute HTTPS URL.                |
| `ERR_TWA_NO_BROWSER`    | No browser supporting Trusted Web Activities.        |
| `ERR_TWA_LAUNCH_FAILED` | The browser rejected or failed to launch the TWA.    |

## Requirements

- Android only.
- Chrome or another browser that supports Trusted Web Activities.
- The page must be served over HTTPS.
- To hide the browser toolbar, the website must verify the app through
  [Digital Asset Links](https://developers.google.com/digital-asset-links):
  host `/.well-known/assetlinks.json` with the app's package name and signing
  certificate SHA-256 fingerprint. Without verification Chrome still opens the
  page, but shows the usual browser UI.

The config plugin only adds the Android 11+ package visibility declarations
needed to discover browsers and Custom Tabs services. No website SDK, web
plugin, or manifest URL is required.

## Roadmap

This first milestone only launches the TWA. Future milestones will add
bidirectional communication using Custom Tabs PostMessage and `MessagePort`.
