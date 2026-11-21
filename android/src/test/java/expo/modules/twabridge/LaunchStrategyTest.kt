package expo.modules.twabridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LaunchStrategyTest {

  private fun capabilities(
    packageName: String? = "com.android.chrome",
    installed: Boolean = true,
    supportsCustomTabs: Boolean = true,
    supportsTwa: Boolean = true,
    isExplicit: Boolean = false
  ) = ProviderCapabilities(
    packageName = packageName,
    installed = installed,
    supportsCustomTabs = supportsCustomTabs,
    supportsTwa = supportsTwa,
    isExplicit = isExplicit
  )

  @Test
  fun `twa capable browser always uses the twa surface`() {
    assertEquals(
      LaunchStrategy.TWA,
      decideLaunchStrategy(capabilities(), FallbackPolicy.CUSTOM_TABS).strategy
    )
    assertEquals(
      LaunchStrategy.TWA,
      decideLaunchStrategy(capabilities(), FallbackPolicy.NONE).strategy
    )
  }

  @Test
  fun `custom tabs capable browser falls back when allowed`() {
    val decision = decideLaunchStrategy(
      capabilities(supportsTwa = false),
      FallbackPolicy.CUSTOM_TABS
    )
    assertEquals(LaunchStrategy.CUSTOM_TABS, decision.strategy)
  }

  @Test
  fun `custom tabs capable browser rejects when fallback is none`() {
    val decision = decideLaunchStrategy(
      capabilities(supportsTwa = false),
      FallbackPolicy.NONE
    )
    assertEquals(LaunchStrategy.ERROR, decision.strategy)
    assertEquals("ERR_TWA_NO_BROWSER", decision.errorCode)
  }

  @Test
  fun `plain browser never gets used`() {
    val decision = decideLaunchStrategy(
      capabilities(supportsTwa = false, supportsCustomTabs = false),
      FallbackPolicy.CUSTOM_TABS
    )
    assertEquals(LaunchStrategy.ERROR, decision.strategy)
    assertEquals("ERR_TWA_CUSTOM_TABS_UNAVAILABLE", decision.errorCode)
  }

  @Test
  fun `no provider rejects with a descriptive error`() {
    val decision = decideLaunchStrategy(
      capabilities(packageName = null, installed = false, supportsTwa = false, supportsCustomTabs = false),
      FallbackPolicy.NONE
    )
    assertEquals(LaunchStrategy.ERROR, decision.strategy)
    assertEquals("ERR_TWA_NO_BROWSER", decision.errorCode)
  }

  @Test
  fun `explicit package that is not installed is never silently replaced`() {
    val decision = decideLaunchStrategy(
      capabilities(
        packageName = "com.example.browser",
        installed = false,
        supportsTwa = false,
        supportsCustomTabs = false,
        isExplicit = true
      ),
      FallbackPolicy.CUSTOM_TABS
    )
    assertEquals(LaunchStrategy.ERROR, decision.strategy)
    assertEquals("ERR_TWA_BROWSER_UNAVAILABLE", decision.errorCode)
  }

  @Test
  fun `explicit package without custom tabs support is rejected`() {
    val decision = decideLaunchStrategy(
      capabilities(
        packageName = "com.example.browser",
        supportsTwa = false,
        supportsCustomTabs = false,
        isExplicit = true
      ),
      FallbackPolicy.CUSTOM_TABS
    )
    assertEquals(LaunchStrategy.ERROR, decision.strategy)
    assertEquals("ERR_TWA_BROWSER_UNAVAILABLE", decision.errorCode)
  }

  @Test
  fun `explicit custom tabs only browser falls back with the requested package`() {
    val decision = decideLaunchStrategy(
      capabilities(
        packageName = "com.example.browser",
        supportsTwa = false,
        isExplicit = true
      ),
      FallbackPolicy.CUSTOM_TABS
    )
    assertEquals(LaunchStrategy.CUSTOM_TABS, decision.strategy)
    assertNull(decision.errorCode)
  }

  @Test
  fun `fallback policy parses known values only`() {
    assertEquals(FallbackPolicy.CUSTOM_TABS, FallbackPolicy.fromValue("custom-tabs"))
    assertEquals(FallbackPolicy.NONE, FallbackPolicy.fromValue("NONE"))
    assertNull(FallbackPolicy.fromValue("webview"))
  }
}
