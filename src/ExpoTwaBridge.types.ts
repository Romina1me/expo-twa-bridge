export type TwaDisplayMode = 'default' | 'immersive';

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
   * Absolute HTTPS URL to open in the Trusted Web Activity.
   *
   * The origin must pass Digital Asset Links verification for this app.
   * Launching rejects with `ERR_TWA_VERIFICATION_FAILED` otherwise; no
   * Custom Tab, WebView or browser fallback is used.
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
   * Extra HTTPS origins verified for this app that should also be shown
   * without browser controls when navigated to from `url`.
   *
   * Every origin must pass Digital Asset Links verification; otherwise the
   * launch rejects.
   */
  additionalTrustedOrigins?: string[];
  /**
   * Enables a PostMessage channel on the same TWA session.
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
