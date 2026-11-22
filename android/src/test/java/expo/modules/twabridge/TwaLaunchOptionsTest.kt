package expo.modules.twabridge

import android.content.Context
import expo.modules.kotlin.records.recordFromMap
import expo.modules.kotlin.runtime.Runtime
import expo.modules.kotlin.types.ConverterContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TwaLaunchOptionsTest {

  private object FakeConverterContext : ConverterContext {
    override val applicationContext: Context
      get() = throw UnsupportedOperationException("Not used by these converters")

    override val runtime: Runtime? = null
  }

  @Test
  fun `nested options convert with null fields`() {
    val options = recordFromMap<TwaLaunchOptionsRecord>(
      mapOf(
        "postMessage" to mapOf("sourceOrigin" to null, "targetOrigin" to null),
        "splashScreen" to mapOf(
          "image" to "https://example.com/splash.png",
          "backgroundColor" to "#0B7285"
        )
      ),
      FakeConverterContext
    )

    assertNotNull(options.postMessage)
    assertNull(options.postMessage?.sourceOrigin)
    assertNull(options.postMessage?.targetOrigin)
    assertEquals("https://example.com/splash.png", options.splashScreen?.image)
    assertEquals("#0B7285", options.splashScreen?.backgroundColor)
  }

  @Test
  fun `missing nested options stay null`() {
    val options = recordFromMap<TwaLaunchOptionsRecord>(emptyMap(), FakeConverterContext)

    assertNull(options.postMessage)
    assertNull(options.splashScreen)
  }
}
