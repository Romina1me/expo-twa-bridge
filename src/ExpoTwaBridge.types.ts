export type TwaDisplayMode = 'default' | 'immersive';

/**
 * What to do when a Trusted Web Activity cannot be launched because of a
 * browser or environment problem.
 *
 * - `custom-tabs` (default): open the URL in Android Custom Tabs with the
 *   browser chrome visible.
 * - `webview`: open the URL in a dedicated fullscreen Android WebView
 *   Activity. The WebView fallback has no PostMessage channel.
 * - `none`: reject with a descriptive error.
 *
 * Digital Asset Links verification failures and timeouts never use any
 * fallback, regardless of this setting.
 */
export type TwaFallbackMode = 'custom-tabs' | 'webview' | 'none';

/**
 * How the module treats Digital Asset Links verification before opening the
 * URL.
 */
export type TwaVerificationMode = 'strict' | 'permissive';

/**
 * Surface that actually opened the URL.
 */
export type TwaLaunchMode = 'twa' | 'custom-tabs' | 'webview';

export type TwaLaunchResult = {
  mode: TwaLaunchMode;
  /**
   * `true` only when this module confirmed Digital Asset Links verification
   * before opening a fullscreen Trusted Web Activity.
   *
   * Always `false` in `permissive` mode and whenever `mode` is `custom-tabs`
   * or `webview`: an unverified navigation is never reported as verified.
   */
  verified: boolean;
};

export type TwaPostMessageOptions = {
  /**
   * App origin used as the source of PostMessage communication. Must be
   * verified with the `delegate_permission/common.use_as_origin` Digital
   * Asset Links relation.
   *
   * Defaults to the origin of `url`.
   */
  sourceOrigin?: string;
  /**
   * Page origin that should receive PostMessage data. Defaults to
   * `sourceOrigin`.
   */
  targetOrigin?: string;
};

export type TwaSplashScreenOptions = {
  /**
   * Image shown while the Trusted Web Activity loads. Accepts a bundled asset
   * (`require('./assets/splash.png')`) or an image URI (for example the
   * `localUri` of an `expo-asset` image).
   */
  image: string | number;
  /**
   * Background color behind the image, as a hex string (`#RRGGBB` or
   * `#AARRGGBB`). Defaults to white.
   */
  backgroundColor?: string;
};

export type TwaLaunchOptions = {
  /**
   * Absolute HTTPS URL to open.
   */
  url: string;
  /**
   * How Digital Asset Links verification is treated:
   *
   * - `permissive` (default): open the URL even when verification cannot be
   *   confirmed. The module launches a real TWA intent and lets Chrome run its
   *   own Digital Asset Links check, so Chrome decides whether to show the URL
   *   as a verified fullscreen TWA or with browser UI. The result is reported
   *   with `verified: false`.
   * - `strict`: only launch after every requested origin passes `handle_all_urls`
   *   verification. Failures and timeouts reject with a typed error and never
   *   fall back.
   *
   * In both modes the URL must be an absolute HTTPS URL; an unverified origin is
   * never treated as trusted.
   */
  verification?: TwaVerificationMode;
  /**
   * `immersive` hides both the browser controls and the Android system bars.
   * `default` keeps the system bars visible.
   *
   * Defaults to `immersive`.
   */
  displayMode?: TwaDisplayMode;
  /**
   * What to do when the Trusted Web Activity cannot launch because of a
   * browser or environment problem (no TWA-capable browser, bind failure,
   * rejected session, launch exception):
   *
   * - `custom-tabs` (default): open the same URL in Android Custom Tabs with
   *   the browser chrome visible. PostMessage keeps working through the same
   *   Custom Tabs session.
   * - `webview`: open the same URL in a dedicated fullscreen Android WebView
   *   Activity. It is used only for browser/session/launch availability
   *   failures. PostMessage is unavailable on this surface: when it was
   *   requested, `onChannelError` reports
   *   `ERR_TWA_POSTMESSAGE_UNAVAILABLE`.
   * - `none`: reject with a descriptive error.
   *
   * Digital Asset Links verification failures and timeouts never fall back —
   * they always reject with `ERR_TWA_VERIFICATION_FAILED` /
   * `ERR_TWA_VERIFICATION_TIMEOUT` in `strict` mode. In `permissive` mode they
   * do not reject the launch at all.
   */
  fallback?: TwaFallbackMode;
  /**
   * Preferred browser package used to host the Trusted Web Activity (for
   * example `com.android.chrome`). When omitted, Android's browser selection
   * picks the provider.
   *
   * The package is never silently replaced: if it is missing or cannot host a
   * TWA, the configured `fallback` policy applies.
   */
  browserPackage?: string;
  /**
   * Extra HTTPS origins verified for this app that should also be shown
   * without browser controls when navigated to from `url`.
   *
   * In `strict` mode every origin must pass Digital Asset Links verification;
   * otherwise the launch rejects. In `permissive` mode these origins are passed
   * to Chrome, which verifies each one itself — they are never trusted without
   * verification.
   */
  additionalTrustedOrigins?: string[];
  /**
   * Optional splash image shown by the browser while the Trusted Web Activity
   * loads, so the URL bar does not flash before Chrome verifies the origin.
   *
   * Requires the package's config plugin (it declares the FileProvider used to
   * hand the image to Chrome). When the browser does not support TWA splash
   * screens or the image cannot be loaded, the launch proceeds without one.
   */
  splashScreen?: TwaSplashScreenOptions;
  /**
   * Enables a PostMessage channel on the same browser session, including when
   * the Custom Tabs fallback is used.
   *
   * The channel is requested after the first navigation finishes. Listen for
   * `onChannelReady` before calling `postMessage()`.
   *
   * PostMessage is not available in the WebView fallback. If the WebView
   * fallback is used, `onChannelError` reports
   * `ERR_TWA_POSTMESSAGE_UNAVAILABLE` and `postMessage()` rejects with
   * `ERR_TWA_POSTMESSAGE_NOT_READY`.
   */
  postMessage?: TwaPostMessageOptions;
};

export type TwaMessageEvent = {
  /**
   * Raw string sent by the web page through its MessagePort.
   */
  data: string;
};

export type TwaChannelErrorEvent = {
  code: string;
  message: string;
};
