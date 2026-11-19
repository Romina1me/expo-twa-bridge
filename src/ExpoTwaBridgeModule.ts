import { NativeModule, requireNativeModule } from 'expo';

declare class ExpoTwaBridgeModule extends NativeModule<{}> {}

export default requireNativeModule<ExpoTwaBridgeModule>('ExpoTwaBridge');
