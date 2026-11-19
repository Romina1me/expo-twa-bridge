import { NativeModule, registerWebModule } from 'expo';

import { TwaLaunchError } from './ExpoTwaBridge.errors';
import type { TwaLaunchOptions } from './ExpoTwaBridge.types';

class ExpoTwaBridgeModule extends NativeModule<{}> {
  async launch(): Promise<void> {
    throw new TwaLaunchError(
      'ERR_TWA_UNSUPPORTED',
      'Trusted Web Activities are only available on Android.'
    );
  }
}

const ExpoTwaBridgeModuleInstance = registerWebModule(
  ExpoTwaBridgeModule,
  'ExpoTwaBridgeModule'
);

export const ExpoTwaBridge = {
  async launch(_options: TwaLaunchOptions): Promise<void> {
    await ExpoTwaBridgeModuleInstance.launch();
  },
};

export default ExpoTwaBridge;
