export type TwaDisplayMode = 'default' | 'immersive';

/**
 * What to do when a Trusted Web Activity cannot be launched because of a
 * browser or environment problem.
 */
export type TwaFallbackMode = 'custom-tabs' | 'none';

/**
 * Surface that actually opened the URL.
 */
export type TwaLaunchMode = 'twa' | 'custom-tabs';

export type TwaLaunchResult = {
  mode: TwaLaunchMode;
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

export type TwaLaunchOptions = {
  /**
   * Absolute HTTPS URL to open.
   */
  url: string;
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
   * - `none`: reject with a descriptive error.
   *
   * Digital Asset Links verification failures and timeouts never fall back —
   * they always reject with `ERR_TWA_VERIFICATION_FAILED` /
   * `ERR_TWA_VERIFICATION_TIMEOUT`.
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
   * Every origin must pass Digital Asset Links verification; otherwise the
   * launch rejects.
   */
  additionalTrustedOrigins?: string[];
  /**
   * Enables a PostMessage channel on the same browser session, including when
   * the Custom Tabs fallback is used.
   *
   * The channel is requested after the first navigation finishes. Listen for
   * `onChannelReady` before calling `postMessage()`.
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
