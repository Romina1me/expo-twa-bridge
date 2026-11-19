import { NativeModule, requireNativeModule } from 'expo';

import {
  TwaLaunchError,
  toTwaLaunchError,
  type TwaErrorCode,
} from './ExpoTwaBridge.errors';
import type { TwaLaunchOptions } from './ExpoTwaBridge.types';

declare class ExpoTwaBridgeNativeModule extends NativeModule<{}> {
  launch(
    url: string,
    displayMode: string,
    additionalTrustedOrigins: string[]
  ): Promise<void>;
}

const ExpoTwaBridgeModule =
  requireNativeModule<ExpoTwaBridgeNativeModule>('ExpoTwaBridge');

export const ExpoTwaBridge = {
  /**
   * Opens the given HTTPS URL in an Android Trusted Web Activity.
   *
   * The call resolves only after Digital Asset Links verification succeeds
   * for the URL's origin (and every additional trusted origin). Otherwise it
   * throws a {@link TwaLaunchError} — no Custom Tab, WebView or browser
   * fallback is ever used.
   *
   * @throws {TwaLaunchError} with code `ERR_TWA_VERIFICATION_FAILED` when the
   * website does not publish a valid `.well-known/assetlinks.json`.
   */
  async launch({
    url,
    displayMode = 'immersive',
    additionalTrustedOrigins = [],
  }: TwaLaunchOptions): Promise<void> {
    try {
      await ExpoTwaBridgeModule.launch(
        url,
        displayMode,
        additionalTrustedOrigins
      );
    } catch (error) {
      throw toTwaLaunchError(error);
    }
  },
};

export { TwaLaunchError };
export type { TwaErrorCode };

export default ExpoTwaBridge;
