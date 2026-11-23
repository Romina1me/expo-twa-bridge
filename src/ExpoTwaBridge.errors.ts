export type TwaErrorCode =
  | 'ERR_TWA_NO_ACTIVITY'
  | 'ERR_TWA_INVALID_URL'
  | 'ERR_TWA_INVALID_DISPLAY_MODE'
  | 'ERR_TWA_INVALID_FALLBACK_MODE'
  | 'ERR_TWA_INVALID_VERIFICATION_MODE'
  | 'ERR_TWA_INVALID_SPLASH_SCREEN'
  | 'ERR_TWA_INVALID_ORIGIN'
  | 'ERR_TWA_NO_BROWSER'
  | 'ERR_TWA_BROWSER_UNAVAILABLE'
  | 'ERR_TWA_CUSTOM_TABS_UNAVAILABLE'
  | 'ERR_TWA_FALLBACK_FAILED'
  | 'ERR_TWA_VERIFICATION_FAILED'
  | 'ERR_TWA_VERIFICATION_UNAVAILABLE'
  | 'ERR_TWA_VERIFICATION_TIMEOUT'
  | 'ERR_TWA_LAUNCH_FAILED'
  | 'ERR_TWA_POSTMESSAGE_INVALID_ORIGIN'
  | 'ERR_TWA_POSTMESSAGE_VERIFICATION_FAILED'
  | 'ERR_TWA_POSTMESSAGE_UNAVAILABLE'
  | 'ERR_TWA_POSTMESSAGE_NOT_READY'
  | 'ERR_TWA_POSTMESSAGE_FAILED'
  | 'ERR_TWA_POSTMESSAGE_TIMEOUT'
  | 'ERR_TWA_UNSUPPORTED';

/**
 * Error thrown by `ExpoTwaBridge.launch()` or `ExpoTwaBridge.postMessage()`.
 *
 * In `strict` verification mode, Digital Asset Links failures are thrown as
 * `ERR_TWA_VERIFICATION_FAILED`; the TWA never opens in that case. In
 * `permissive` mode (the default) verification results are not launch errors;
 * the browser decides how to present the URL.
 *
 * `ERR_TWA_NO_BROWSER`, `ERR_TWA_BROWSER_UNAVAILABLE`,
 * `ERR_TWA_CUSTOM_TABS_UNAVAILABLE` and `ERR_TWA_FALLBACK_FAILED` describe
 * browser/environment problems; with `fallback: 'custom-tabs'` or
 * `fallback: 'webview'` the launch is retried on that surface before these
 * are reported. Verification failures and timeouts never fall back.
 */
export class TwaLaunchError extends Error {
  readonly code: TwaErrorCode;

  constructor(code: TwaErrorCode, message: string) {
    super(message);
    this.name = 'TwaLaunchError';
    this.code = code;
  }
}

export function toTwaLaunchError(error: unknown): TwaLaunchError {
  if (error instanceof TwaLaunchError) {
    return error;
  }

  if (error instanceof Error) {
    const code = (error as Error & { code?: string }).code;
    return new TwaLaunchError(
      (code as TwaErrorCode | undefined) ?? 'ERR_TWA_LAUNCH_FAILED',
      error.message
    );
  }

  // Native module rejections can arrive as plain objects carrying a `code`
  // and `message` without being an `Error` instance. Preserve the code so the
  // module's descriptive error contract holds.
  if (typeof error === 'object' && error !== null) {
    const record = error as { code?: unknown; message?: unknown };
    const code =
      typeof record.code === 'string'
        ? (record.code as TwaErrorCode)
        : undefined;
    const message =
      typeof record.message === 'string'
        ? (record.message as string)
        : String(record.message ?? '');
    return new TwaLaunchError(code ?? 'ERR_TWA_LAUNCH_FAILED', message);
  }

  return new TwaLaunchError('ERR_TWA_LAUNCH_FAILED', String(error));
}
