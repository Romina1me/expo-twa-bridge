import { useState } from 'react';
import {
  ActivityIndicator,
  Button,
  SafeAreaView,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from 'react-native';

import { ExpoTwaBridge, TwaLaunchError } from 'expo-twa-bridge';

const DEFAULT_URL = 'https://figfit.ir';

export default function App() {
  const [url, setUrl] = useState(DEFAULT_URL);
  const [immersive, setImmersive] = useState(true);
  const [isLaunching, setIsLaunching] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleLaunch() {
    setIsLaunching(true);
    setError(null);

    try {
      await ExpoTwaBridge.launch({
        url,
        displayMode: immersive ? 'immersive' : 'default',
      });
    } catch (launchError) {
      setError(
        launchError instanceof TwaLaunchError
          ? `${launchError.code}: ${launchError.message}`
          : launchError instanceof Error
            ? launchError.message
            : String(launchError)
      );
    } finally {
      setIsLaunching(false);
    }
  }

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.content}>
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
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f2f2f2',
  },
  content: {
    flex: 1,
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
});
