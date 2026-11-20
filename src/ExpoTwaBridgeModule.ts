import { NativeModule, requireNativeModule } from 'expo';
import type { EventSubscription } from 'expo';

import {
  TwaLaunchError,
  toTwaLaunchError,
  type TwaErrorCode,
} from './ExpoTwaBridge.errors';
import type {
  TwaChannelErrorEvent,
  TwaLaunchOptions,
  TwaMessageEvent,
} from './ExpoTwaBridge.types';

type ExpoTwaBridgeModuleEvents = {
  onMessage(event: TwaMessageEvent): void;
  onChannelReady(): void;
  onChannelError(event: TwaChannelErrorEvent): void;
  onChannelClosed(): void;
};

declare class ExpoTwaBridgeNativeModule extends NativeModule<ExpoTwaBridgeModuleEvents> {
  launch(
    url: string,
    displayMode: string,
    additionalTrustedOrigins: string[],
    enablePostMessage: boolean,
    postMessageSourceOrigin: string | null,
    postMessageTargetOrigin: string | null
  ): Promise<void>;
  postMessage(message: string): Promise<void>;
  isPostMessageReady(): Promise<boolean>;
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
    postMessage,
  }: TwaLaunchOptions): Promise<void> {
    try {
      await ExpoTwaBridgeModule.launch(
        url,
        displayMode,
        additionalTrustedOrigins,
        postMessage !== undefined,
        postMessage?.sourceOrigin ?? null,
        postMessage?.targetOrigin ?? null
      );
    } catch (error) {
      throw toTwaLaunchError(error);
    }
  },

  /**
   * Sends a raw string to the verified web page over the TWA PostMessage
   * channel. The channel must be ready; listen for `onChannelReady` first.
   */
  async postMessage(message: string): Promise<void> {
    try {
      await ExpoTwaBridgeModule.postMessage(message);
    } catch (error) {
      throw toTwaLaunchError(error);
    }
  },

  /**
   * Returns whether the PostMessage channel is open and messages can be sent.
   */
  async isPostMessageReady(): Promise<boolean> {
    return ExpoTwaBridgeModule.isPostMessageReady();
  },

  /**
   * Called for messages sent by the web page.
   */
  addMessageListener(
    listener: (event: TwaMessageEvent) => void
  ): EventSubscription {
    return ExpoTwaBridgeModule.addListener('onMessage', listener);
  },

  /**
   * Called when the PostMessage channel is ready for sending messages.
   */
  addChannelReadyListener(listener: () => void): EventSubscription {
    return ExpoTwaBridgeModule.addListener('onChannelReady', listener);
  },

  /**
   * Called when PostMessage channel setup fails (verification, missing
   * service, timeout, unsupported browser).
   */
  addChannelErrorListener(
    listener: (event: TwaChannelErrorEvent) => void
  ): EventSubscription {
    return ExpoTwaBridgeModule.addListener('onChannelError', listener);
  },

  /**
   * Called when the channel is closed (next launch, service disconnect or
   * module destruction).
   */
  addChannelClosedListener(listener: () => void): EventSubscription {
    return ExpoTwaBridgeModule.addListener('onChannelClosed', listener);
  },
};

export { TwaLaunchError };
export type { TwaErrorCode };

export default ExpoTwaBridge;
