import { type EventSubscription } from 'expo-modules-core';

import {
  LuxMeasurement,
  LuxSensorOptions,
  PermissionResponse,
} from './ExpoLuxSensor.types';
import ExpoLuxSensorModule from './ExpoLuxSensorModule';

export function addLuxListener(listener: (sample: LuxMeasurement) => void): EventSubscription {
  return ExpoLuxSensorModule.addListener('onLuxChanged', listener);
}

export function removeAllListeners(): void {
  ExpoLuxSensorModule.removeAllListeners('onLuxChanged');
}

export async function startLuxUpdatesAsync(options?: LuxSensorOptions): Promise<void> {
  return ExpoLuxSensorModule.startAsync(options);
}

export async function stopLuxUpdatesAsync(): Promise<void> {
  return ExpoLuxSensorModule.stopAsync();
}

export async function isLuxUpdatesRunningAsync(): Promise<boolean> {
  return ExpoLuxSensorModule.isRunningAsync();
}

export async function getPermissionsAsync(): Promise<PermissionResponse> {
  return ExpoLuxSensorModule.getPermissionsAsync();
}

export async function requestPermissionsAsync(): Promise<PermissionResponse> {
  return ExpoLuxSensorModule.requestPermissionsAsync();
}

export default {
  addLuxListener,
  removeAllListeners,
  startAsync: startLuxUpdatesAsync,
  stopAsync: stopLuxUpdatesAsync,
  isRunningAsync: isLuxUpdatesRunningAsync,
  getPermissionsAsync,
  requestPermissionsAsync,
};

export * from './ExpoLuxSensor.types';
