/* eslint-env jest */

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

import { ExpoTwaBridge, TwaLaunchError } from '../ExpoTwaBridgeModule';

const native = (globalThis as Record<string, unknown>)
  .__twaNative as NativeMock;

beforeEach(() => {
  jest.clearAllMocks();
  native.launch.mockResolvedValue('twa');
});

describe('ExpoTwaBridge.launch', () => {
  it('defaults to the custom-tabs fallback and resolves the actual mode', async () => {
    const result = await ExpoTwaBridge.launch({ url: 'https://app.figfit.ir' });

    expect(result).toEqual({ mode: 'twa' });
    expect(native.launch).toHaveBeenCalledWith(
      'https://app.figfit.ir',
      'immersive',
      'custom-tabs',
      null,
      [],
      false,
      null,
      null
    );
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

    expect(result).toEqual({ mode: 'custom-tabs' });
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
      true,
      'https://app.figfit.ir',
      null,
    ]);
  });

  it('propagates verification errors without retrying', async () => {
    native.launch.mockRejectedValue({
      code: 'ERR_TWA_VERIFICATION_FAILED',
      message: 'assetlinks missing',
    });

    await expect(
      ExpoTwaBridge.launch({ url: 'https://app.figfit.ir' })
    ).rejects.toMatchObject({
      code: 'ERR_TWA_VERIFICATION_FAILED',
      message: 'assetlinks missing',
    });
    expect(native.launch).toHaveBeenCalledTimes(1);
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
