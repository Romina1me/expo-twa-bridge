import { NativeModule, registerWebModule } from 'expo';

import { TwaLaunchError } from './ExpoTwaBridge.errors';
import type { TwaLaunchOptions, TwaLaunchResult } from './ExpoTwaBridge.types';

class ExpoTwaBridgeModule extends NativeModule<Record<string, never>> {
  async launch(): Promise<void> {
    throw new TwaLaunchError(
      'ERR_TWA_UNSUPPORTED',
      'Trusted Web Activities are only available on Android.'
    );
  }

  async postMessage(): Promise<void> {
    throw new TwaLaunchError(
      'ERR_TWA_UNSUPPORTED',
      'Trusted Web Activities are only available on Android.'
    );
  }

  async isPostMessageReady(): Promise<boolean> {
    return false;
  }
}

const ExpoTwaBridgeModuleInstance = registerWebModule(
  ExpoTwaBridgeModule,
  'ExpoTwaBridgeModule'
);

export const ExpoTwaBridge = {
  async launch(_options: TwaLaunchOptions): Promise<TwaLaunchResult> {
    throw new TwaLaunchError(
      'ERR_TWA_UNSUPPORTED',
      'Trusted Web Activities are only available on Android.'
    );
  },

  async postMessage(_message: string): Promise<void> {
    await ExpoTwaBridgeModuleInstance.postMessage();
  },

  async isPostMessageReady(): Promise<boolean> {
    return false;
  },

  addMessageListener(_listener: () => void) {
    return { remove() {} };
  },

  addChannelReadyListener(_listener: () => void) {
    return { remove() {} };
  },

  addChannelErrorListener(_listener: () => void) {
    return { remove() {} };
  },

  addChannelClosedListener(_listener: () => void) {
    return { remove() {} };
  },
};

export default ExpoTwaBridge;
