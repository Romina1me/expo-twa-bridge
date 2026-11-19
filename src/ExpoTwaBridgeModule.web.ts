import { registerWebModule, NativeModule } from 'expo';

// ExpoTwaBridgeModule is not available on the web platform.
class ExpoTwaBridgeModule extends NativeModule<{}> {}

export default registerWebModule(ExpoTwaBridgeModule, 'ExpoTwaBridgeModule');
