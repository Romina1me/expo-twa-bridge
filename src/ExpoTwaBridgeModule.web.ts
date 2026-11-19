import { NativeModule, registerWebModule } from 'expo';

import type { TwaLaunchOptions } from './ExpoTwaBridge.types';

class ExpoTwaBridgeModule extends NativeModule<{}> {
  async launch(): Promise<void> {
    throw new Error('Trusted Web Activities are only available on Android.');
  }
}

const ExpoTwaBridgeModuleInstance = registerWebModule(
  ExpoTwaBridgeModule,
  'ExpoTwaBridgeModule'
);

export const ExpoTwaBridge = {
  launch(_options: TwaLaunchOptions): Promise<void> {
    return ExpoTwaBridgeModuleInstance.launch();
  },
};

export default ExpoTwaBridge;
