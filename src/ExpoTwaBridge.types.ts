export type TwaDisplayMode = 'default' | 'immersive';

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
};
