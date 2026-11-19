import { NativeModule, requireNativeModule } from 'expo';

import type { TwaLaunchOptions } from './ExpoTwaBridge.types';

declare class ExpoTwaBridgeNativeModule extends NativeModule<{}> {
  launch(url: string): Promise<void>;
}

const ExpoTwaBridgeModule =
  requireNativeModule<ExpoTwaBridgeNativeModule>('ExpoTwaBridge');

export const ExpoTwaBridge = {
  /**
   * Opens the given HTTPS URL in an Android Trusted Web Activity.
   *
   * The URL must be an absolute HTTPS URL whose origin is verified for this
   * app (or the browser was verified by the user), otherwise Chrome shows the
   * usual browser toolbar.
   */
  launch({ url }: TwaLaunchOptions): Promise<void> {
    return ExpoTwaBridgeModule.launch(url);
  },
};

export default ExpoTwaBridge;
