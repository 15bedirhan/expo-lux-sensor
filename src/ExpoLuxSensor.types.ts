export type PermissionStatus = 'undetermined' | 'granted' | 'denied';

export type PermissionResponse = {
  granted: boolean;
  status: PermissionStatus;
};

export type LuxMeasurement = {
  lux: number;
  timestamp: number;
};

export type LuxSensorOptions = {
  updateInterval?: number;
  calibrationConstant?: number;
};

export type ExpoLuxSensorModuleEvents = {
  onLuxChanged: (sample: LuxMeasurement) => void;
};
