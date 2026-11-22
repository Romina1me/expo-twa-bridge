package expo.modules.twabridge

import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record

/**
 * Typed argument records for `launch`. Using records instead of nested
 * `Map<String, Any?>` values keeps Expo's converter out of its `Any` path,
 * which cannot handle null properties inside nested objects.
 */
class TwaLaunchOptionsRecord : Record {
  @Field var postMessage: TwaPostMessageOptionsRecord? = null
  @Field var splashScreen: TwaSplashScreenOptionsRecord? = null
}

class TwaPostMessageOptionsRecord : Record {
  @Field var sourceOrigin: String? = null
  @Field var targetOrigin: String? = null
}

class TwaSplashScreenOptionsRecord : Record {
  @Field var image: String? = null
  @Field var backgroundColor: String? = null
}
