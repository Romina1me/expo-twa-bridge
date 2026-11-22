/* eslint-env jest */

import { Image } from 'react-native';

import { ExpoTwaBridge, TwaLaunchError } from '../ExpoTwaBridgeModule';

type NativeMock = {
  launch: jest.Mock;
  postMessage: jest.Mock;
  isPostMessageReady: jest.Mock;
  addListener: jest.Mock;
};

jest.mock('expo', () => {
  const native = {
    launch: jest.fn(),
    postMessage: jest.fn(),
    isPostMessageReady: jest.fn(),
    addListener: jest.fn(() => ({ remove() {} })),
  };
  (globalThis as Record<string, unknown>).__twaNative = native;
  return { requireNativeModule: () => native };
});

const native = (globalThis as Record<string, unknown>)
  .__twaNative as NativeMock;

beforeEach(() => {
  jest.clearAllMocks();
  native.launch.mockResolvedValue('twa');
});

afterEach(() => {
  jest.restoreAllMocks();
});

describe('ExpoTwaBridge.launch', () => {
  it('defaults to permissive verification, the custom-tabs fallback, and resolves the actual mode', async () => {
    const result = await ExpoTwaBridge.launch({ url: 'https://app.figfit.ir' });

    expect(result).toEqual({ mode: 'twa', verified: false });
    expect(native.launch).toHaveBeenCalledWith(
      'https://app.figfit.ir',
      'immersive',
      'custom-tabs',
      null,
      [],
      null,
      'permissive'
    );
  });

  it('forwards verification: "strict" and reports the TWA as verified', async () => {
    const result = await ExpoTwaBridge.launch({
      url: 'https://app.figfit.ir',
      verification: 'strict',
    });

    expect(native.launch.mock.calls[0][6]).toBe('strict');
    expect(result).toEqual({ mode: 'twa', verified: true });
  });

  it('never reports a permissive launch as verified', async () => {
    const result = await ExpoTwaBridge.launch({
      url: 'https://app.figfit.ir',
      verification: 'permissive',
    });

    expect(native.launch.mock.calls[0][6]).toBe('permissive');
    expect(result.verified).toBe(false);
  });

  it('does not report a custom-tabs result as verified', async () => {
    native.launch.mockResolvedValue('custom-tabs');

    const result = await ExpoTwaBridge.launch({
      url: 'https://app.figfit.ir',
      verification: 'strict',
    });

    expect(result).toEqual({ mode: 'custom-tabs', verified: false });
  });

  it('forwards fallback: "none"', async () => {
    await ExpoTwaBridge.launch({
      url: 'https://app.figfit.ir',
      fallback: 'none',
    });

    expect(native.launch.mock.calls[0][2]).toBe('none');
  });

  it('resolves custom-tabs when the native module fell back', async () => {
    native.launch.mockResolvedValue('custom-tabs');

    const result = await ExpoTwaBridge.launch({
      url: 'https://app.figfit.ir',
      fallback: 'custom-tabs',
    });

    expect(result).toEqual({ mode: 'custom-tabs', verified: false });
  });

  it('forwards an explicit browser package and trims it', async () => {
    await ExpoTwaBridge.launch({
      url: 'https://app.figfit.ir',
      browserPackage: ' com.android.chrome ',
    });

    expect(native.launch.mock.calls[0][3]).toBe('com.android.chrome');
  });

  it('passes null when browserPackage is blank', async () => {
    await ExpoTwaBridge.launch({
      url: 'https://app.figfit.ir',
      browserPackage: '   ',
    });

    expect(native.launch.mock.calls[0][3]).toBeNull();
  });

  it('preserves PostMessage options', async () => {
    await ExpoTwaBridge.launch({
      url: 'https://app.figfit.ir',
      postMessage: { sourceOrigin: 'https://app.figfit.ir' },
    });

    expect(native.launch.mock.calls[0].slice(5)).toEqual([
      {
        postMessage: {
          sourceOrigin: 'https://app.figfit.ir',
          targetOrigin: null,
        },
        splashScreen: null,
      },
      'permissive',
    ]);
  });

  it('forwards splashScreen options', async () => {
    await ExpoTwaBridge.launch({
      url: 'https://app.figfit.ir',
      splashScreen: {
        image: 'https://app.figfit.ir/splash.png',
        backgroundColor: '#0B7285',
      },
    });

    expect(native.launch.mock.calls[0][5]).toEqual({
      postMessage: null,
      splashScreen: {
        image: 'https://app.figfit.ir/splash.png',
        backgroundColor: '#0B7285',
      },
    });
  });

  it('resolves a bundled splash image module to a URI', async () => {
    const resolve = jest
      .spyOn(Image, 'resolveAssetSource')
      .mockReturnValue({
        uri: 'file:///splash.png',
        width: 1,
        height: 1,
        scale: 1,
      });

    await ExpoTwaBridge.launch({
      url: 'https://app.figfit.ir',
      splashScreen: { image: 42 },
    });

    expect(resolve).toHaveBeenCalledWith(42);
    expect(native.launch.mock.calls[0][5]).toMatchObject({
      splashScreen: { image: 'file:///splash.png', backgroundColor: null },
    });
  });

  it('rejects a splash image that cannot be resolved', async () => {
    jest.spyOn(Image, 'resolveAssetSource').mockReturnValue({
      uri: '',
      width: 0,
      height: 0,
      scale: 1,
    });

    await expect(
      ExpoTwaBridge.launch({
        url: 'https://app.figfit.ir',
        splashScreen: { image: 99 },
      })
    ).rejects.toMatchObject({ code: 'ERR_TWA_INVALID_SPLASH_SCREEN' });
    expect(native.launch).not.toHaveBeenCalled();
  });

  it('propagates verification errors without retrying', async () => {
    native.launch.mockRejectedValue({
      code: 'ERR_TWA_VERIFICATION_FAILED',
      message: 'assetlinks missing',
    });

    await expect(
      ExpoTwaBridge.launch({
        url: 'https://app.figfit.ir',
        verification: 'strict',
      })
    ).rejects.toMatchObject({
      code: 'ERR_TWA_VERIFICATION_FAILED',
      message: 'assetlinks missing',
    });
    expect(native.launch).toHaveBeenCalledTimes(1);
  });

  it('preserves an invalid verification mode error from native', async () => {
    native.launch.mockRejectedValue({
      code: 'ERR_TWA_INVALID_VERIFICATION_MODE',
      message: 'verification must be either "strict" or "permissive".',
    });

    await expect(
      ExpoTwaBridge.launch({
        url: 'https://app.figfit.ir',
        // @ts-expect-error exercising the native validation boundary
        verification: 'lenient',
      })
    ).rejects.toMatchObject({ code: 'ERR_TWA_INVALID_VERIFICATION_MODE' });
  });

  it('wraps non-TwaLaunchError failures', async () => {
    native.launch.mockRejectedValue(new Error('boom'));

    await expect(
      ExpoTwaBridge.launch({ url: 'https://app.figfit.ir' })
    ).rejects.toBeInstanceOf(TwaLaunchError);
  });

  it('rejects unknown native launch modes instead of guessing', async () => {
    native.launch.mockResolvedValue('webview');

    await expect(
      ExpoTwaBridge.launch({ url: 'https://app.figfit.ir' })
    ).rejects.toMatchObject({ code: 'ERR_TWA_LAUNCH_FAILED' });
  });
});
