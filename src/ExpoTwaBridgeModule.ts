import { NativeModule, requireNativeModule } from 'expo';
import type { EventSubscription } from 'expo';

import {
  TwaLaunchError,
  toTwaLaunchError,
  type TwaErrorCode,
} from './ExpoTwaBridge.errors';
import type {
  TwaChannelErrorEvent,
  TwaLaunchMode,
  TwaLaunchOptions,
  TwaLaunchResult,
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
    fallback: string,
    browserPackage: string | null,
    additionalTrustedOrigins: string[],
    enablePostMessage: boolean,
    postMessageSourceOrigin: string | null,
    postMessageTargetOrigin: string | null
  ): Promise<string>;
  postMessage(message: string): Promise<void>;
  isPostMessageReady(): Promise<boolean>;
}

const ExpoTwaBridgeModule =
  requireNativeModule<ExpoTwaBridgeNativeModule>('ExpoTwaBridge');

function parseLaunchMode(value: string): TwaLaunchMode {
  if (value === 'twa' || value === 'custom-tabs') {
    return value;
  }
  throw new TwaLaunchError(
    'ERR_TWA_LAUNCH_FAILED',
    `The native module returned an unknown launch mode "${value}".`
  );
}

export const ExpoTwaBridge = {
  /**
   * Opens the given HTTPS URL in an Android Trusted Web Activity.
   *
   * The call resolves with the surface that actually opened the URL. A TWA
   * resolves only after Digital Asset Links verification succeeds for the
   * URL's origin (and every additional trusted origin).
   *
   * When the TWA cannot launch because of a browser or environment problem
   * and `fallback` is `'custom-tabs'` (the default), the URL is opened in
   * Android Custom Tabs instead and the result is `{ mode: 'custom-tabs' }`.
   * Verification failures and timeouts never fall back.
   *
   * @throws {TwaLaunchError} with code `ERR_TWA_VERIFICATION_FAILED` when the
   * website does not publish a valid `.well-known/assetlinks.json`.
   */
  async launch({
    url,
    displayMode = 'immersive',
    fallback = 'custom-tabs',
    browserPackage,
    additionalTrustedOrigins = [],
    postMessage,
  }: TwaLaunchOptions): Promise<TwaLaunchResult> {
    try {
      const mode = await ExpoTwaBridgeModule.launch(
        url,
        displayMode,
        fallback,
        browserPackage && browserPackage.trim().length > 0
          ? browserPackage.trim()
          : null,
        additionalTrustedOrigins,
        postMessage !== undefined,
        postMessage?.sourceOrigin ?? null,
        postMessage?.targetOrigin ?? null
      );
      return { mode: parseLaunchMode(mode) };
    } catch (error) {
      throw toTwaLaunchError(error);
    }
  },

  /**
   * Sends a raw string to the verified web page over the PostMessage
   * channel. The channel must be ready; listen for `onChannelReady` first.
   *
   * The channel is backed by the browser session, so it also works when the
   * Custom Tabs fallback opened the URL.
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
