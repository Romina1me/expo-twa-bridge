export type TwaErrorCode =
  | 'ERR_TWA_NO_ACTIVITY'
  | 'ERR_TWA_INVALID_URL'
  | 'ERR_TWA_INVALID_DISPLAY_MODE'
  | 'ERR_TWA_INVALID_ORIGIN'
  | 'ERR_TWA_NO_BROWSER'
  | 'ERR_TWA_VERIFICATION_FAILED'
  | 'ERR_TWA_VERIFICATION_TIMEOUT'
  | 'ERR_TWA_LAUNCH_FAILED'
  | 'ERR_TWA_UNSUPPORTED';

/**
 * Error thrown by `ExpoTwaBridge.launch()`.
 *
 * Digital Asset Links verification failures are thrown as
 * `ERR_TWA_VERIFICATION_FAILED`; the TWA never opens in that case.
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

  return new TwaLaunchError('ERR_TWA_LAUNCH_FAILED', String(error));
}
