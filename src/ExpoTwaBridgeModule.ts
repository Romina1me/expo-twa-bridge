import { NativeModule, requireNativeModule } from 'expo';
import type { EventSubscription } from 'expo';
import { Image } from 'react-native';

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
    options: {
      postMessage: {
        sourceOrigin: string | null;
        targetOrigin: string | null;
      } | null;
      splashScreen: {
        image: string;
        backgroundColor: string | null;
      } | null;
    } | null,
    verification: string
  ): Promise<string>;
  postMessage(message: string): Promise<void>;
  isPostMessageReady(): Promise<boolean>;
}

const ExpoTwaBridgeModule =
  requireNativeModule<ExpoTwaBridgeNativeModule>('ExpoTwaBridge');

function parseLaunchMode(value: string): TwaLaunchMode {
  if (value === 'twa' || value === 'custom-tabs' || value === 'webview') {
    return value;
  }
  throw new TwaLaunchError(
    'ERR_TWA_LAUNCH_FAILED',
    `The native module returned an unknown launch mode "${value}".`
  );
}

function resolveSplashImage(image: string | number): string {
  if (typeof image === 'string') {
    const trimmed = image.trim();
    if (trimmed.length > 0) {
      return trimmed;
    }
  } else {
    const resolved = Image.resolveAssetSource(image);
    if (resolved?.uri) {
      return resolved.uri;
    }
  }
  throw new TwaLaunchError(
    'ERR_TWA_INVALID_SPLASH_SCREEN',
    'splashScreen.image could not be resolved to an image URI.'
  );
}

export const ExpoTwaBridge = {
  /**
   * Opens the given HTTPS URL in an Android Trusted Web Activity.
   *
   * The call resolves with the surface that actually opened the URL.
   *
   * - `verification: 'permissive'` (default) launches the TWA intent even when
   *   verification cannot be confirmed. Chrome runs its own Digital Asset Links
   *   check and decides whether to show the URL fullscreen or with browser UI.
   *   The result is `verified: false`.
   * - `verification: 'strict'` resolves only after every requested origin
   *   passed Digital Asset Links verification. Failures and timeouts reject and
   *   never fall back. A strict TWA result is `verified: true`.
   *
   * When the TWA cannot launch because of a browser or environment problem and
   * `fallback` is `'custom-tabs'` (the default), the URL is opened in Android
   * Custom Tabs instead and the result is `{ mode: 'custom-tabs' }`. With
   * `fallback: 'webview'`, the URL is opened in a dedicated fullscreen WebView
   * Activity instead and the result is `{ mode: 'webview' }`. The WebView
   * fallback has no PostMessage channel; `onChannelError` reports
   * `ERR_TWA_POSTMESSAGE_UNAVAILABLE` when PostMessage was requested.
   *
   * @throws {TwaLaunchError} in `strict` mode with
   * `ERR_TWA_VERIFICATION_FAILED` / `ERR_TWA_VERIFICATION_TIMEOUT` when the
   * website does not publish a valid `.well-known/assetlinks.json`.
   */
  async launch({
    url,
    verification = 'permissive',
    displayMode = 'immersive',
    fallback = 'custom-tabs',
    browserPackage,
    additionalTrustedOrigins = [],
    splashScreen,
    postMessage,
  }: TwaLaunchOptions): Promise<TwaLaunchResult> {
    try {
      const postMessageOptions =
        postMessage === undefined
          ? null
          : {
              sourceOrigin: postMessage.sourceOrigin ?? null,
              targetOrigin: postMessage.targetOrigin ?? null,
            };
      const splashScreenOptions =
        splashScreen === undefined
          ? null
          : {
              image: resolveSplashImage(splashScreen.image),
              backgroundColor: splashScreen.backgroundColor ?? null,
            };
      const options =
        postMessageOptions === null && splashScreenOptions === null
          ? null
          : {
              postMessage: postMessageOptions,
              splashScreen: splashScreenOptions,
            };

      const mode = parseLaunchMode(
        await ExpoTwaBridgeModule.launch(
          url,
          displayMode,
          fallback,
          browserPackage && browserPackage.trim().length > 0
            ? browserPackage.trim()
            : null,
          additionalTrustedOrigins,
          options,
          verification
        )
      );
      return {
        mode,
        verified: verification === 'strict' && mode === 'twa',
      };
    } catch (error) {
      throw toTwaLaunchError(error);
    }
  },

  /**
   * Sends a raw string to the verified web page over the PostMessage
   * channel. The channel must be ready; listen for `onChannelReady` first.
   *
   * The channel is backed by the browser session, so it also works when the
   * Custom Tabs fallback opened the URL. It is never available when the
   * WebView fallback opened the URL; in that case this rejects with
   * `ERR_TWA_POSTMESSAGE_NOT_READY`.
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
