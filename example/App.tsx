import { useEffect, useState } from 'react';
import {
  ActivityIndicator,
  Button,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from 'react-native';

import { ExpoTwaBridge, TwaLaunchError } from 'expo-twa-bridge';

const DEFAULT_URL = 'https://app.figfit.ir/';

export default function App() {
  const [url, setUrl] = useState(DEFAULT_URL);
  const [immersive, setImmersive] = useState(true);
  const [isLaunching, setIsLaunching] = useState(false);
  const [error, setError] = useState<string | null>(null);

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
    setChannelReady(false);
    setChannelError(null);
    setLastMessage(null);

    try {
      await ExpoTwaBridge.launch({
        url,
        displayMode: immersive ? 'immersive' : 'default',
        postMessage: {},
      });
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

        {isLaunching ? (
          <View style={styles.loading}>
            <ActivityIndicator />
            <Text style={styles.loadingText}>Opening Trusted Web Activity…</Text>
          </View>
        ) : (
          <Button
            title="Open TWA"
            onPress={handleLaunch}
            disabled={url.trim().length === 0}
          />
        )}

        {error ? <Text style={styles.error}>{error}</Text> : null}

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
