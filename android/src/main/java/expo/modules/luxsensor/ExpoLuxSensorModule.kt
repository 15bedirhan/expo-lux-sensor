package expo.modules.luxsensor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import expo.modules.interfaces.permissions.Permissions
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record
import kotlinx.coroutines.launch

class ExpoLuxSensorModule : Module(), SensorEventListener {
  private val reactContext: Context?
    get() = appContext.reactContext

  private var sensorManager: SensorManager? = null
  private var lightSensor: Sensor? = null
  private var cameraDevice: CameraDevice? = null
  private var captureSession: CameraCaptureSession? = null
  private var imageReader: ImageReader? = null
  private var backgroundThread: HandlerThread? = null
  private var backgroundHandler: Handler? = null
  private var isCameraStreaming = false
  private var isSensorStreaming = false
  private var lastEmission = 0L
  private var updateIntervalMs = 400L
  private var calibrationConstant = 60.0

  private class LuxSensorOptions : Record {
    @Field var updateInterval: Double? = null
    @Field var calibrationConstant: Double? = null
  }

  override fun definition() = ModuleDefinition {
    Name("ExpoLuxSensor")

    Events("onLuxChanged")

    AsyncFunction("getPermissionsAsync") { promise: Promise ->
      Permissions.getPermissionsWithPermissionsManager(
        appContext.permissions,
        promise,
        Manifest.permission.CAMERA
      )
    }

    AsyncFunction("requestPermissionsAsync") { promise: Promise ->
      Permissions.askForPermissionsWithPermissionsManager(
        appContext.permissions,
        promise,
        Manifest.permission.CAMERA
      )
    }

    AsyncFunction("startAsync") { options: LuxSensorOptions? ->
      start(options)
    }

    AsyncFunction("stopAsync") {
      stopAll()
    }

    AsyncFunction("isRunningAsync") {
      isCameraStreaming || isSensorStreaming
    }
  }

  private fun start(options: LuxSensorOptions?) {
    applyOptions(options)
    val context = reactContext ?: throw Exceptions.ReactContextLost()
    if (sensorManager == null) {
      sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
      lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
    }

    if (lightSensor != null) {
      startLightSensor()
      return
    }

    ensurePermission()
    startCamera()
  }

  private fun ensurePermission() {
    val context = reactContext ?: throw Exceptions.ReactContextLost()
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      throw Exceptions.MissingPermissions(Manifest.permission.CAMERA)
    }
  }

  private fun applyOptions(options: LuxSensorOptions?) {
    options?.updateInterval?.takeIf { it > 0 }?.let {
      updateIntervalMs = (it * 1000).toLong()
    }
    options?.calibrationConstant?.takeIf { it > 0 }?.let {
      calibrationConstant = it
    }
  }

  @SuppressLint("MissingPermission")
  private fun startCamera() {
    if (isCameraStreaming) {
      return
    }
    startBackgroundThread()

    val context = reactContext ?: throw Exceptions.ReactContextLost()
    val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    val cameraId = findBackCamera(manager) ?: manager.cameraIdList.firstOrNull()
      ?: throw CameraAccessException(CameraAccessException.CAMERA_ERROR)

    if (imageReader == null) {
      imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2).apply {
        setOnImageAvailableListener({ reader ->
          reader.acquireLatestImage()?.close()
        }, backgroundHandler)
      }
    }

    manager.openCamera(cameraId, stateCallback, backgroundHandler)
  }

  private fun stopCamera() {
    isCameraStreaming = false
    captureSession?.close()
    captureSession = null
    cameraDevice?.close()
    cameraDevice = null
    imageReader?.close()
    imageReader = null
    stopBackgroundThread()
  }

  private fun computeLux(result: CaptureResult): Double? {
    val aperture = result.get(CaptureResult.LENS_APERTURE) ?: return null
    val exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return null
    val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return null
    if (aperture <= 0.0 || exposureNs <= 0L || iso <= 0) {
      return null
    }
    val exposureSeconds = exposureNs / 1_000_000_000.0
    return (calibrationConstant * aperture * aperture) / (exposureSeconds * iso)
  }

  private val stateCallback = object : CameraDevice.StateCallback() {
    override fun onOpened(camera: CameraDevice) {
      cameraDevice = camera
      createSession()
    }

    override fun onDisconnected(camera: CameraDevice) {
      camera.close()
      cameraDevice = null
      stopCamera()
    }

    override fun onError(camera: CameraDevice, error: Int) {
      camera.close()
      cameraDevice = null
      stopCamera()
    }
  }

  private fun createSession() {
    val device = cameraDevice ?: return
    val reader = imageReader ?: return

    device.createCaptureSession(
      listOf(reader.surface),
      object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
          captureSession = session
          val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(reader.surface)
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
          }
          session.setRepeatingRequest(
            requestBuilder.build(),
            object : CameraCaptureSession.CaptureCallback() {
              override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
              ) {
                val luxValue = computeLux(result) ?: return
                val now = System.currentTimeMillis()
                if (now - lastEmission < updateIntervalMs) {
                  return
                }
                lastEmission = now
                appContext.mainQueue.launch {
                  sendEvent(
                    "onLuxChanged",
                    mapOf(
                      "lux" to luxValue,
                      "timestamp" to now.toDouble()
                    )
                  )
                }
              }
            },
            backgroundHandler
          )
          isCameraStreaming = true
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
          stopCamera()
        }
      },
      backgroundHandler
    )
  }

  private fun findBackCamera(manager: CameraManager): String? {
    for (id in manager.cameraIdList) {
      val characteristics = manager.getCameraCharacteristics(id)
      val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
      if (facing == CameraCharacteristics.LENS_FACING_BACK) {
        return id
      }
    }
    return null
  }

  private fun startBackgroundThread() {
    if (backgroundThread != null) return
    backgroundThread = HandlerThread("ExpoLuxSensorThread").also {
      it.start()
      backgroundHandler = Handler(it.looper)
    }
  }

  private fun stopBackgroundThread() {
    backgroundThread?.quitSafely()
    try {
      backgroundThread?.join()
    } catch (_: InterruptedException) {
    }
    backgroundThread = null
    backgroundHandler = null
  }

  private fun startLightSensor() {
    if (isSensorStreaming) return
    sensorManager?.registerListener(
      this,
      lightSensor,
      SensorManager.SENSOR_DELAY_NORMAL
    )
    isSensorStreaming = true
  }

  private fun stopLightSensor() {
    if (!isSensorStreaming) return
    sensorManager?.unregisterListener(this)
    isSensorStreaming = false
  }

  private fun stopAll() {
    stopLightSensor()
    stopCamera()
  }

  override fun onSensorChanged(event: SensorEvent?) {
    if (!isSensorStreaming || event?.sensor?.type != Sensor.TYPE_LIGHT) return
    val luxValue = event.values.firstOrNull()?.toDouble() ?: return
    val now = System.currentTimeMillis()
    if (now - lastEmission < updateIntervalMs) {
      return
    }
    lastEmission = now
    appContext.mainQueue.launch {
      sendEvent(
        "onLuxChanged",
        mapOf(
          "lux" to luxValue,
          "timestamp" to now.toDouble()
        )
      )
    }
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
