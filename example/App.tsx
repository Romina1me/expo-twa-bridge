import { useEffect, useState } from 'react';
import {
  ActivityIndicator,
  Button,
  Pressable,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from 'react-native';

import { ExpoTwaBridge, TwaLaunchError } from 'expo-twa-bridge';
import type { TwaFallbackMode, TwaLaunchMode } from 'expo-twa-bridge';

const DEFAULT_URL = 'https://app.figfit.ir/';

const FALLBACK_LABELS: Record<TwaFallbackMode, string> = {
  'custom-tabs': 'Custom Tabs',
  webview: 'WebView',
  none: 'None',
};

const FALLBACK_MODES: TwaFallbackMode[] = ['custom-tabs', 'webview', 'none'];

// Simulates a device with no TWA-capable browser so the example can always
// show the WebView fallback.
const PREVIEW_WEBVIEW_BROWSER_PACKAGE = 'com.expo.twa.bridge.preview';

export default function App() {
  const [url, setUrl] = useState(DEFAULT_URL);
  const [immersive, setImmersive] = useState(true);
  const [fallback, setFallback] = useState<TwaFallbackMode>('webview');
  const [browserPackage, setBrowserPackage] = useState('');
  const [isLaunching, setIsLaunching] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [launchMode, setLaunchMode] = useState<TwaLaunchMode | null>(null);

  const [channelReady, setChannelReady] = useState(false);
  const [channelError, setChannelError] = useState<string | null>(null);
  const [lastMessage, setLastMessage] = useState<string | null>(null);

  useEffect(() => {
    const subscriptions = [
      ExpoTwaBridge.addChannelReadyListener(() => {
        setChannelReady(true);
        setChannelError(null);
      }),
      ExpoTwaBridge.addChannelClosedListener(() => {
        setChannelReady(false);
      }),
      ExpoTwaBridge.addChannelErrorListener((event) => {
        setChannelReady(false);
        setChannelError(`${event.code}: ${event.message}`);
      }),
      ExpoTwaBridge.addMessageListener((event) => {
        setLastMessage(event.data);

        // Minimal PING/PONG: reply to the web page when it sends PING.
        if (event.data === 'PING') {
          ExpoTwaBridge.postMessage('PONG').catch((postError) => {
            setChannelError(formatError(postError));
          });
        }
      }),
    ];

    return () => subscriptions.forEach((subscription) => subscription.remove());
  }, []);

  async function handleLaunch() {
    setIsLaunching(true);
    setError(null);
    setLaunchMode(null);
    setChannelReady(false);
    setChannelError(null);
    setLastMessage(null);

    try {
      const result = await ExpoTwaBridge.launch({
        url,
        verification: 'strict',
        displayMode: immersive ? 'immersive' : 'default',
        fallback,
        // Selecting WebView forces the availability failure so the example
        // always shows the WebView fallback instead of a real TWA.
        browserPackage:
          fallback === 'webview'
            ? PREVIEW_WEBVIEW_BROWSER_PACKAGE
            : browserPackage.trim() || undefined,
        splashScreen: {
          image: require('./assets/splash.png'),
          backgroundColor: '#0B7285',
        },
        postMessage: {},
      });
      setLaunchMode(result.mode);
    } catch (launchError) {
      setError(formatError(launchError));
    } finally {
      setIsLaunching(false);
    }
  }

  async function handleSendPing() {
    try {
      await ExpoTwaBridge.postMessage('PING');
      setChannelError(null);
    } catch (postError) {
      setChannelError(formatError(postError));
    }
  }

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView contentContainerStyle={styles.content}>
        <Text style={styles.title}>Trusted Web Activity</Text>

        <TextInput
          style={styles.input}
          value={url}
          onChangeText={setUrl}
          autoCapitalize="none"
          autoCorrect={false}
          keyboardType="url"
          editable={!isLaunching}
          placeholder="https://example.com"
        />

        <View style={styles.row}>
          <Text style={styles.rowLabel}>Immersive (hide system bars)</Text>
          <Switch
            value={immersive}
            onValueChange={setImmersive}
            disabled={isLaunching}
          />
        </View>

        <View style={styles.fallbackGroup}>
          <Text style={styles.rowLabel}>Fallback when a TWA cannot launch</Text>
          <View style={styles.fallbackOptions}>
            {FALLBACK_MODES.map((mode) => (
              <Pressable
                key={mode}
                accessibilityRole="button"
                accessibilityState={{ selected: fallback === mode }}
                disabled={isLaunching}
                onPress={() => setFallback(mode)}
                style={[
                  styles.fallbackOption,
                  fallback === mode && styles.fallbackOptionSelected,
                ]}
              >
                <Text
                  style={[
                    styles.fallbackOptionText,
                    fallback === mode && styles.fallbackOptionTextSelected,
                  ]}
                >
                  {FALLBACK_LABELS[mode]}
                </Text>
              </Pressable>
            ))}
          </View>
          {fallback === 'webview' ? (
            <Text style={styles.hint}>
              Preview mode: an unavailable browser package is passed so the
              fullscreen WebView fallback opens. PostMessage is unavailable.
            </Text>
          ) : null}
        </View>

        <TextInput
          style={styles.input}
          value={browserPackage}
          onChangeText={setBrowserPackage}
          autoCapitalize="none"
          autoCorrect={false}
          editable={!isLaunching}
          placeholder="Browser package, e.g. com.android.chrome"
        />

        {isLaunching ? (
          <View style={styles.loading}>
            <ActivityIndicator />
            <Text style={styles.loadingText}>
              Opening {fallback === 'webview' ? 'WebView' : 'Trusted Web Activity'}…
            </Text>
          </View>
        ) : (
          <Button
            title={fallback === 'webview' ? 'Open WebView' : 'Open TWA'}
            onPress={handleLaunch}
            disabled={url.trim().length === 0}
          />
        )}

        {error ? <Text style={styles.error}>{error}</Text> : null}
        {launchMode ? (
          <Text style={styles.status}>
            Opened in:{' '}
            {launchMode === 'twa'
              ? 'Trusted Web Activity'
              : launchMode === 'custom-tabs'
                ? 'Custom Tab'
                : 'WebView fallback'}
          </Text>
        ) : null}

        <View style={styles.card}>
          <Text style={styles.cardTitle}>PostMessage</Text>
          <Text style={styles.status}>
            Channel: {channelReady ? 'ready' : 'not connected'}
          </Text>
          <Text style={styles.message}>
            Last message: {lastMessage ?? 'none'}
          </Text>
          {channelError ? (
            <Text style={styles.error}>{channelError}</Text>
          ) : null}
          <Button
            title="Send PING"
            onPress={handleSendPing}
            disabled={!channelReady}
          />
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

function formatError(value: unknown): string {
  if (value instanceof TwaLaunchError) {
    return `${value.code}: ${value.message}`;
  }
  if (value instanceof Error) {
    return value.message;
  }
  return String(value);
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f2f2f2',
  },
  content: {
    flexGrow: 1,
    justifyContent: 'center',
    padding: 24,
    gap: 16,
  },
  title: {
    fontSize: 22,
    fontWeight: '600',
  },
  input: {
    backgroundColor: '#ffffff',
    borderColor: '#cccccc',
    borderRadius: 8,
    borderWidth: 1,
    paddingHorizontal: 12,
    paddingVertical: 10,
  },
  row: {
    alignItems: 'center',
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  rowLabel: {
    color: '#333333',
    flex: 1,
    marginRight: 12,
  },
  fallbackGroup: {
    gap: 8,
  },
  fallbackOptions: {
    flexDirection: 'row',
    gap: 8,
  },
  fallbackOption: {
    alignItems: 'center',
    backgroundColor: '#ffffff',
    borderColor: '#cccccc',
    borderRadius: 8,
    borderWidth: 1,
    flex: 1,
    paddingVertical: 10,
  },
  fallbackOptionSelected: {
    backgroundColor: '#0B7285',
    borderColor: '#0B7285',
  },
  fallbackOptionText: {
    color: '#333333',
  },
  fallbackOptionTextSelected: {
    color: '#ffffff',
    fontWeight: '600',
  },
  hint: {
    color: '#555555',
    fontSize: 12,
  },
  loading: {
    alignItems: 'center',
    flexDirection: 'row',
    gap: 8,
    justifyContent: 'center',
  },
  loadingText: {
    color: '#555555',
  },
  error: {
    color: '#b00020',
  },
  card: {
    backgroundColor: '#ffffff',
    borderRadius: 10,
    gap: 12,
    padding: 16,
  },
  cardTitle: {
    fontSize: 18,
    fontWeight: '600',
  },
  status: {
    color: '#333333',
  },
  message: {
    color: '#555555',
  },
});
