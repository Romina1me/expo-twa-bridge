const { withAppBuildGradle } = require('expo/config-plugins');

const KEYSTORE_PROPS_BLOCK = `def keystorePropertiesFile = rootProject.file("../keystore.properties")
if (!keystorePropertiesFile.exists()) {
    throw new GradleException("keystore.properties not found at " + keystorePropertiesFile + ". It is required to sign this app with the figfit keystore so TWA verification (assetlinks.json) matches.")
}
def keystoreProperties = new Properties()
keystoreProperties.load(new FileInputStream(keystorePropertiesFile))

android {`;

const FIGFIT_SIGNING_CONFIG = `    signingConfigs {
        figfit {
            storeFile rootProject.file("../" + keystoreProperties['storeFile'])
            storePassword keystoreProperties['storePassword']
            keyAlias keystoreProperties['keyAlias']
            keyPassword keystoreProperties['keyPassword']
        }`;

const DEBUG_BUILD_TYPE = `        debug {
            signingConfig signingConfigs.figfit
        }`;

/**
 * Signs the debug build with the figfit keystore (example/figfit.keystore,
 * credentials in example/keystore.properties) so the installed dev build's
 * signature matches the SHA-256 fingerprint declared in assetlinks.json and
 * the TWA launches in verified fullscreen mode.
 */
const withFigfitSigning = (config) =>
  withAppBuildGradle(config, (config) => {
    let contents = config.modResults.contents;

    if (contents.includes('signingConfigs.figfit')) {
      return config;
    }

    if (!contents.includes('\nandroid {')) {
      throw new Error(
        'withFigfitSigning: could not find the `android {` block in android/app/build.gradle'
      );
    }
    if (!contents.includes('    signingConfigs {')) {
      throw new Error(
        'withFigfitSigning: could not find the `signingConfigs {` block in android/app/build.gradle'
      );
    }
    if (
      !contents.includes(
        '        debug {\n            signingConfig signingConfigs.debug\n        }'
      )
    ) {
      throw new Error(
        'withFigfitSigning: could not find the debug buildType block in android/app/build.gradle'
      );
    }

    contents = contents.replace('\nandroid {', `\n${KEYSTORE_PROPS_BLOCK}`);
    contents = contents.replace('    signingConfigs {', FIGFIT_SIGNING_CONFIG);
    contents = contents.replace(
      '        debug {\n            signingConfig signingConfigs.debug\n        }',
      DEBUG_BUILD_TYPE
    );

    config.modResults.contents = contents;
    return config;
  });

module.exports = withFigfitSigning;
