package expo.modules.twabridge

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.androidbrowserhelper.trusted.ChromeLegacyUtils
import com.google.androidbrowserhelper.trusted.TwaProviderPicker

internal const val CUSTOM_TABS_SERVICE_ACTION =
  "android.support.customtabs.action.CustomTabsService"
internal const val TWA_SERVICE_CATEGORY =
  "androidx.browser.trusted.category.TrustedWebActivities"

internal enum class FallbackPolicy(val value: String) {
  CUSTOM_TABS("custom-tabs"),
  NONE("none");

  companion object {
    fun fromValue(value: String): FallbackPolicy? =
      entries.firstOrNull { it.value == value.lowercase() }
  }
}

internal enum class LaunchSurface(val mode: String) {
  TWA("twa"),
  CUSTOM_TABS("custom-tabs")
}

/**
 * What the device can do with the resolved browser package.
 */
internal data class ProviderCapabilities(
  val packageName: String?,
  val installed: Boolean,
  val supportsCustomTabs: Boolean,
  val supportsTwa: Boolean,
  val isExplicit: Boolean
)

internal enum class LaunchStrategy { TWA, CUSTOM_TABS, ERROR }

internal data class LaunchDecision(
  val strategy: LaunchStrategy,
  val errorCode: String? = null,
  val errorMessage: String? = null
)

/**
 * Chooses the launch surface from the browser capabilities and the configured
 * fallback policy.
 *
 * An explicitly requested browser is never silently replaced by another one:
 * when it is missing or unusable the configured fallback policy applies, and
 * `none` surfaces a descriptive error.
 */
internal fun decideLaunchStrategy(
  capabilities: ProviderCapabilities,
  fallback: FallbackPolicy
): LaunchDecision {
  if (capabilities.isExplicit) {
    if (!capabilities.installed) {
      return LaunchDecision(
        LaunchStrategy.ERROR,
        "ERR_TWA_BROWSER_UNAVAILABLE",
        "The requested browser package \"${capabilities.packageName}\" is not installed. " +
          "No other browser is selected automatically when browserPackage is set."
      )
    }
    if (!capabilities.supportsCustomTabs) {
      return LaunchDecision(
        LaunchStrategy.ERROR,
        "ERR_TWA_BROWSER_UNAVAILABLE",
        "The requested browser package \"${capabilities.packageName}\" does not expose an " +
          "Android Custom Tabs service, so it cannot host a Trusted Web Activity."
      )
    }
  }

  if (capabilities.supportsTwa && capabilities.packageName != null) {
    return LaunchDecision(LaunchStrategy.TWA)
  }

  if (capabilities.supportsCustomTabs && capabilities.packageName != null) {
    return when (fallback) {
      FallbackPolicy.CUSTOM_TABS -> LaunchDecision(LaunchStrategy.CUSTOM_TABS)
      FallbackPolicy.NONE -> LaunchDecision(
        LaunchStrategy.ERROR,
        "ERR_TWA_NO_BROWSER",
        "The available browser \"${capabilities.packageName}\" does not support Trusted Web " +
          "Activities. Install a TWA-capable browser (for example Chrome) or use " +
          "fallback: \"custom-tabs\"."
      )
    }
  }

  return when (fallback) {
    FallbackPolicy.CUSTOM_TABS -> LaunchDecision(
      LaunchStrategy.ERROR,
      "ERR_TWA_CUSTOM_TABS_UNAVAILABLE",
      "No browser supporting Android Custom Tabs is installed, so neither a Trusted Web " +
        "Activity nor the Custom Tabs fallback can be launched."
    )
    FallbackPolicy.NONE -> LaunchDecision(
      LaunchStrategy.ERROR,
      "ERR_TWA_NO_BROWSER",
      "No browser supporting Trusted Web Activities is installed. " +
        "A Custom Tab or regular browser is not used as a fallback."
    )
  }
}

internal object TwaProviderResolver {

  fun resolve(packageManager: PackageManager, requestedPackage: String?): ProviderCapabilities {
    if (requestedPackage != null) {
      return inspect(packageManager, requestedPackage)
    }

    val action = TwaProviderPicker.pickProvider(packageManager)
    val provider = action.provider
    return when (action.launchMode) {
      TwaProviderPicker.LaunchMode.TRUSTED_WEB_ACTIVITY ->
        ProviderCapabilities(provider, true, true, true, false)
      TwaProviderPicker.LaunchMode.CUSTOM_TAB ->
        ProviderCapabilities(provider, true, true, false, false)
      else ->
        ProviderCapabilities(provider, provider != null, false, false, false)
    }
  }

  fun inspect(packageManager: PackageManager, packageName: String): ProviderCapabilities {
    val installed = try {
      packageManager.getPackageInfo(packageName, 0)
      true
    } catch (_: PackageManager.NameNotFoundException) {
      false
    }

    if (!installed) {
      return ProviderCapabilities(packageName, false, false, false, true)
    }

    val services = queryCustomTabsServices(packageManager, packageName)
    if (services.isEmpty()) {
      return ProviderCapabilities(packageName, true, false, false, true)
    }

    val declaredCategory = services.any { info ->
      info.filter?.hasCategory(TWA_SERVICE_CATEGORY) == true
    }
    val legacySupport = runCatching {
      ChromeLegacyUtils.supportsTrustedWebActivities(packageManager, packageName)
    }.getOrDefault(false)

    return ProviderCapabilities(
      packageName,
      true,
      true,
      declaredCategory || legacySupport,
      true
    )
  }

  private fun queryCustomTabsServices(
    packageManager: PackageManager,
    packageName: String
  ): List<android.content.pm.ResolveInfo> {
    val intent = Intent(CUSTOM_TABS_SERVICE_ACTION).setPackage(packageName)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      packageManager.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
      @Suppress("DEPRECATION")
      packageManager.queryIntentServices(intent, 0)
    }
  }
}
