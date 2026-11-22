import {
  ConfigPlugin,
  withAndroidManifest,
  withDangerousMod,
} from 'expo/config-plugins';
import * as fs from 'fs';
import * as path from 'path';

const CUSTOM_TABS_SERVICE_ACTION =
  'android.support.customtabs.action.CustomTabsService';
const VIEW_ACTION = 'android.intent.action.VIEW';
const BROWSABLE_CATEGORY = 'android.intent.category.BROWSABLE';
const POST_MESSAGE_SERVICE = 'androidx.browser.customtabs.PostMessageService';
const SPLASH_FILE_PROVIDER = 'androidx.core.content.FileProvider';
const SPLASH_PATHS_RESOURCE = '@xml/twa_splash_paths';
const SPLASH_AUTHORITY_SUFFIX = '.twa_splash';

const SPLASH_PATHS_XML = `<?xml version="1.0" encoding="utf-8"?>
<paths xmlns:android="http://schemas.android.com/apk/res/android">
  <files-path name="twa_splash" path="twa_splash/" />
</paths>
`;

type ManifestProvider = {
  $: Record<string, string>;
  'meta-data'?: { $: Record<string, string> }[];
};

/**
 * Adds the package visibility declarations required on Android 11+, the
 * PostMessageService used by the Custom Tabs PostMessage channel, and the
 * FileProvider used to hand a TWA splash image to Chrome.
 */
const withExpoTwaBridge: ConfigPlugin = (config) => {
  config = withAndroidManifest(config, (config) => {
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

      const applicationWithProviders = application as typeof application & {
        provider?: ManifestProvider[];
      };
      const providers = (applicationWithProviders.provider ??= []);
      // `${applicationId}` is substituted by the Android Gradle Plugin, so the
      // authority matches the runtime package name (including debug/release
      // suffixes).
      const authorities = '${applicationId}' + SPLASH_AUTHORITY_SUFFIX;
      if (
        !providers.some(
          (provider: ManifestProvider) =>
            provider.$['android:name'] === SPLASH_FILE_PROVIDER &&
            provider.$['android:authorities'] === authorities
        )
      ) {
        providers.push({
          $: {
            'android:name': SPLASH_FILE_PROVIDER,
            'android:authorities': authorities,
            'android:exported': 'false',
            'android:grantUriPermissions': 'true',
          },
          'meta-data': [
            {
              $: {
                'android:name': 'android.support.FILE_PROVIDER_PATHS',
                'android:resource': SPLASH_PATHS_RESOURCE,
              },
            },
          ],
        });
      }
    }

    return config;
  });

  config = withDangerousMod(config, [
    'android',
    async (config) => {
      const xmlDir = path.join(
        config.modRequest.platformProjectRoot,
        'app',
        'src',
        'main',
        'res',
        'xml'
      );
      await fs.promises.mkdir(xmlDir, { recursive: true });
      await fs.promises.writeFile(
        path.join(xmlDir, 'twa_splash_paths.xml'),
        SPLASH_PATHS_XML,
        'utf8'
      );
      return config;
    },
  ]);

  return config;
};

export default withExpoTwaBridge;
