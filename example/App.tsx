import {
  addLuxListener,
  getPermissionsAsync,
  requestPermissionsAsync,
  startLuxUpdatesAsync,
  stopLuxUpdatesAsync,
} from 'expo-lux-sensor';
import React, { useEffect, useState } from 'react';
import { Button, StyleSheet, Text, View } from 'react-native';

export default function App() {
  const [lux, setLux] = useState<number | null>(null);
  const [permission, setPermission] = useState('undetermined');
  const [isRunning, setIsRunning] = useState(false);

  useEffect(() => {
    let isMounted = true;
    getPermissionsAsync().then((response) => {
      if (isMounted) setPermission(response.status);
    });

    const subscription = addLuxListener((sample) => {
      setLux(sample.lux);
    });

    return () => {
      isMounted = false;
      subscription.remove();
      stopLuxUpdatesAsync();
    };
  }, []);

  const ensurePermissionAsync = async () => {
    if (permission === 'granted') return true;
    const response = await requestPermissionsAsync();
    setPermission(response.status);
    return response.granted;
  };

  const handleStart = async () => {
    const allowed = await ensurePermissionAsync();
    if (!allowed) return;
    await startLuxUpdatesAsync({ updateInterval: 0.4, calibrationConstant: 1200 });
    setIsRunning(true);
  };

  const handleStop = async () => {
    await stopLuxUpdatesAsync();
    setIsRunning(false);
  };

  return (
    <View style={styles.container}>
      <View style={styles.card}>
        <Text style={styles.title}>Expo Lux Sensor</Text>
        <Text style={styles.value}>{lux !== null ? `${lux.toFixed(0)} lux` : '--'}</Text>
        <Text style={styles.caption}>Permission: {permission}</Text>
        <View style={styles.actions}>
          <Button title="Start" onPress={handleStart} disabled={isRunning} />
          <Button title="Stop" onPress={handleStop} disabled={!isRunning} />
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#F6F8FA',
  },
  card: {
    width: '90%',
    padding: 24,
    borderRadius: 16,
    backgroundColor: '#FFFFFF',
    shadowColor: '#000000',
    shadowOffset: { width: 0, height: 8 },
    shadowOpacity: 0.1,
    shadowRadius: 16,
    elevation: 6,
    gap: 16,
  },
  title: {
    fontSize: 24,
    fontWeight: '600',
    textAlign: 'center',
  },
  value: {
    fontSize: 48,
    fontWeight: '700',
    textAlign: 'center',
  },
  caption: {
    fontSize: 16,
    textAlign: 'center',
    color: '#6B7280',
  },
  actions: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    gap: 12,
  },
});
