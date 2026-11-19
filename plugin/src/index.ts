import { ConfigPlugin, withAndroidManifest } from 'expo/config-plugins';

const CUSTOM_TABS_SERVICE_ACTION =
  'android.support.customtabs.action.CustomTabsService';
const VIEW_ACTION = 'android.intent.action.VIEW';
const BROWSABLE_CATEGORY = 'android.intent.category.BROWSABLE';

/**
 * Adds the package visibility declarations required on Android 11+ so that
 * Android Browser Helper can discover installed browsers, Custom Tabs
 * providers and Trusted Web Activity providers.
 */
const withExpoTwaBridge: ConfigPlugin = (config) =>
  withAndroidManifest(config, (config) => {
    const manifest = config.modResults.manifest;
    const queries = (manifest.queries ??= []);

    const hasIntent = (action: string, category?: string) =>
      queries.some((query) =>
        query.intent?.some(
          (intent) =>
            intent.action?.some((item) => item.$['android:name'] === action) &&
            (!category ||
              intent.category?.some(
                (item) => item.$['android:name'] === category
              ))
        )
      );

    if (!hasIntent(CUSTOM_TABS_SERVICE_ACTION)) {
      queries.push({
        intent: [
          {
            action: [{ $: { 'android:name': CUSTOM_TABS_SERVICE_ACTION } }],
          },
        ],
      });
    }

    if (!hasIntent(VIEW_ACTION, BROWSABLE_CATEGORY)) {
      queries.push({
        intent: [
          {
            action: [{ $: { 'android:name': VIEW_ACTION } }],
            category: [{ $: { 'android:name': BROWSABLE_CATEGORY } }],
            data: [
              { $: { 'android:scheme': 'http' } },
              { $: { 'android:scheme': 'https' } },
            ],
          },
        ],
      });
    }

    return config;
  });

export default withExpoTwaBridge;
