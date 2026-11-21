import { ConfigPlugin, withAndroidManifest } from 'expo/config-plugins';

const CUSTOM_TABS_SERVICE_ACTION =
  'android.support.customtabs.action.CustomTabsService';
const VIEW_ACTION = 'android.intent.action.VIEW';
const BROWSABLE_CATEGORY = 'android.intent.category.BROWSABLE';
const POST_MESSAGE_SERVICE = 'androidx.browser.customtabs.PostMessageService';

/**
 * Adds the package visibility declarations required on Android 11+ and the
 * PostMessageService used by the Custom Tabs PostMessage channel.
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

    const application = manifest.application?.[0];
    if (application) {
      const services = (application.service ??= []);
      if (
        !services.some(
          (service) => service.$['android:name'] === POST_MESSAGE_SERVICE
        )
      ) {
        services.push({
          $: {
            'android:name': POST_MESSAGE_SERVICE,
            'android:exported': 'true',
          },
        });
      }
    }

    return config;
  });

export default withExpoTwaBridge;
