import { NativeModule, requireNativeModule } from 'expo';

import {
  ExpoLuxSensorModuleEvents,
  LuxSensorOptions,
  PermissionResponse,
} from './ExpoLuxSensor.types';

declare class ExpoLuxSensorModule extends NativeModule<ExpoLuxSensorModuleEvents> {
  startAsync(options?: LuxSensorOptions): Promise<void>;
  stopAsync(): Promise<void>;
  isRunningAsync(): Promise<boolean>;
  getPermissionsAsync(): Promise<PermissionResponse>;
  requestPermissionsAsync(): Promise<PermissionResponse>;
}

export default requireNativeModule<ExpoLuxSensorModule>('ExpoLuxSensor');
