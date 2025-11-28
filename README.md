# expo-lux-sensor

A native Expo module that provides access to ambient light sensor (lux) measurements using the device's camera on iOS and Android.

## Features

- 📱 **Cross-platform**: Works on both iOS and Android
- 🔄 **Real-time updates**: Get continuous light level measurements
- ⚙️ **Configurable**: Adjustable update interval and calibration constant
- 🔐 **Permission handling**: Built-in permission request and status checking
- 📊 **Event-based**: Listen to light level changes via event listeners
- 📷 **Back camera**: Uses the device's back camera for ambient light measurements

## Installation

```bash
npm install expo-lux-sensor
```

### iOS Setup

After installing the package, run:

```bash
npx pod-install
```

### Android Setup

No additional setup required. The module will automatically be linked.

## Usage

### Basic Example

```typescript
import {
  addLuxListener,
  startLuxUpdatesAsync,
  stopLuxUpdatesAsync,
  requestPermissionsAsync,
} from 'expo-lux-sensor';
import { useEffect, useState } from 'react';

export default function App() {
  const [lux, setLux] = useState<number | null>(null);

  useEffect(() => {
    // Request permissions
    requestPermissionsAsync();

    // Listen to lux changes
    const subscription = addLuxListener((sample) => {
      setLux(sample.lux);
    });

    // Start sensor updates
    startLuxUpdatesAsync();

    // Cleanup
    return () => {
      subscription.remove();
      stopLuxUpdatesAsync();
    };
  }, []);

  return (
    <View>
      <Text>Light Level: {lux !== null ? `${lux.toFixed(0)} lux` : '--'}</Text>
    </View>
  );
}
```

### Advanced Example with Options

```typescript
import {
  addLuxListener,
  startLuxUpdatesAsync,
  stopLuxUpdatesAsync,
  requestPermissionsAsync,
  getPermissionsAsync,
} from 'expo-lux-sensor';

async function startLightSensor() {
  // Check permissions first
  const permission = await getPermissionsAsync();
  
  if (!permission.granted) {
    const result = await requestPermissionsAsync();
    if (!result.granted) {
      console.warn('Camera permission is required');
      return;
    }
  }

  // Start with custom options
  await startLuxUpdatesAsync({
    updateInterval: 0.5, // Update every 500ms
    calibrationConstant: 1200, // Calibration constant (Android) or 50 (iOS)
  });

  // Listen to updates
  const subscription = addLuxListener((sample) => {
    console.log(`Lux: ${sample.lux}, Timestamp: ${sample.timestamp}`);
  });

  // Remember to clean up
  // subscription.remove();
  // stopLuxUpdatesAsync();
}
```

## API Reference

### Functions

#### `startLuxUpdatesAsync(options?: LuxSensorOptions): Promise<void>`

Starts the light sensor updates. Requires camera permission.

**Parameters:**
- `options` (optional): Configuration options
  - `updateInterval?: number` - Update interval in seconds (default: 0.4)
  - `calibrationConstant?: number` - Calibration constant for lux calculation (default: 1200 for Android, 50 for iOS)

**Throws:**
- `MissingPermissions` - If camera permission is not granted

#### `stopLuxUpdatesAsync(): Promise<void>`

Stops the light sensor updates and releases camera resources.

#### `isLuxUpdatesRunningAsync(): Promise<boolean>`

Returns whether the sensor is currently running.

#### `getPermissionsAsync(): Promise<PermissionResponse>`

Gets the current permission status without requesting.

**Returns:**
```typescript
{
  granted: boolean;
  status: 'undetermined' | 'granted' | 'denied';
}
```

#### `requestPermissionsAsync(): Promise<PermissionResponse>`

Requests camera permission from the user.

**Returns:**
```typescript
{
  granted: boolean;
  status: 'undetermined' | 'granted' | 'denied';
}
```

### Event Listeners

#### `addLuxListener(listener: (sample: LuxMeasurement) => void): EventSubscription`

Adds a listener for lux measurement updates.

**Parameters:**
- `listener` - Callback function that receives `LuxMeasurement` objects

**Returns:**
- `EventSubscription` - Subscription object with a `remove()` method

**Example:**
```typescript
const subscription = addLuxListener((sample) => {
  console.log(`Lux: ${sample.lux}`);
  console.log(`Timestamp: ${sample.timestamp}`);
});

// Later, remove the listener
subscription.remove();
```

#### `removeAllListeners(): void`

Removes all lux event listeners.

### Types

#### `LuxMeasurement`

```typescript
{
  lux: number;        // Light level in lux
  timestamp: number;  // Timestamp in milliseconds
}
```

#### `LuxSensorOptions`

```typescript
{
  updateInterval?: number;        // Update interval in seconds
  calibrationConstant?: number;   // Calibration constant
}
```

#### `PermissionResponse`

```typescript
{
  granted: boolean;
  status: 'undetermined' | 'granted' | 'denied';
}
```

## Platform-Specific Notes

### iOS

- Uses `AVCaptureDevice` to access camera metadata
- Uses the **back camera** for light measurements (falls back to default camera if back camera is unavailable)
- Calculates lux from EXIF data (aperture, exposure time, ISO)
- Default calibration constant: `50`
- Requires `NSCameraUsageDescription` in `Info.plist` (handled automatically by Expo)

### Android

- Uses Camera2 API to capture image frames
- Uses the **back camera** for light measurements (falls back to first available camera if back camera is unavailable)
- Calculates lux from image buffer average
- Default calibration constant: `1200`
- Requires `CAMERA` permission (handled automatically by Expo)

## Permissions

This module requires camera permission because it uses the device's camera to measure ambient light. The permission is requested automatically when you call `requestPermissionsAsync()`.

**Note:** The module prioritizes the back camera (rear-facing camera) for measurements. If the back camera is unavailable, it will fall back to the default camera. Make sure the camera is not obstructed and is facing the light source you want to measure.

**iOS:** Add to `app.json`:
```json
{
  "ios": {
    "infoPlist": {
      "NSCameraUsageDescription": "This app needs access to the camera to measure ambient light levels."
    }
  }
}
```

**Android:** The permission is automatically added by Expo.

## Troubleshooting

### Permission Denied

If you get a permission error:
1. Make sure you've requested permissions using `requestPermissionsAsync()`
2. Check that the permission is granted before calling `startLuxUpdatesAsync()`
3. On iOS, verify `NSCameraUsageDescription` is set in `app.json`

### No Lux Values

If you're not receiving lux values:
1. Ensure the sensor is started with `startLuxUpdatesAsync()`
2. Check that you've added a listener with `addLuxListener()`
3. Verify camera permission is granted
4. Make sure the app is running on a physical device (camera access may not work in simulators)

### High Battery Usage

The sensor uses the camera continuously, which can drain battery. Consider:
- Increasing the `updateInterval` to reduce update frequency
- Stopping the sensor when not needed with `stopLuxUpdatesAsync()`
- Only starting the sensor when the screen is visible

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

MIT

## Author

[15bedirhan](https://github.com/15bedirhan)

## Links

- [GitHub Repository](https://github.com/15bedirhan/expo-lux-sensor)
- [Report an Issue](https://github.com/15bedirhan/expo-lux-sensor/issues)
