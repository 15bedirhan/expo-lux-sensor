package expo.modules.luxsensor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraAccessException
import android.media.Image
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

class ExpoLuxSensorModule : Module() {
  private val reactContext: Context?
    get() = appContext.reactContext

  private var cameraDevice: CameraDevice? = null
  private var captureSession: CameraCaptureSession? = null
  private var imageReader: ImageReader? = null
  private var backgroundThread: HandlerThread? = null
  private var backgroundHandler: Handler? = null
  private var isStreaming = false
  private var lastEmission = 0L
  private var updateIntervalMs = 400L
  private var calibrationConstant = 1200.0

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
      ensurePermission()
      startCamera(options)
    }

    AsyncFunction("stopAsync") {
      stopCamera()
    }

    AsyncFunction("isRunningAsync") {
      isStreaming
    }
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
  private fun startCamera(options: LuxSensorOptions?) {
    if (isStreaming) {
      applyOptions(options)
      return
    }

    applyOptions(options)
    startBackgroundThread()

    val context = reactContext ?: throw Exceptions.ReactContextLost()
    val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    val cameraId = findBackCamera(manager) ?: manager.cameraIdList.firstOrNull()
      ?: throw CameraAccessException(CameraAccessException.CAMERA_ERROR)

    if (imageReader == null) {
      imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2).apply {
        setOnImageAvailableListener({ reader ->
          handleImage(reader.acquireLatestImage())
        }, backgroundHandler)
      }
    }

    manager.openCamera(cameraId, stateCallback, backgroundHandler)
  }

  private fun stopCamera() {
    isStreaming = false
    captureSession?.close()
    captureSession = null
    cameraDevice?.close()
    cameraDevice = null
    imageReader?.close()
    imageReader = null
    stopBackgroundThread()
  }

  private fun handleImage(image: Image?) {
    if (image == null) return

    val luxValue = computeLux(image)
    image.close()

    if (luxValue == null) return

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

  private fun computeLux(image: Image): Double? {
    val buffer = image.planes.firstOrNull()?.buffer ?: return null
    if (buffer.remaining() <= 0) {
      return null
    }
    var sum = 0L
    while (buffer.hasRemaining()) {
      sum += buffer.get().toInt() and 0xFF
    }
    buffer.rewind()
    val avg = sum.toDouble() / buffer.remaining().coerceAtLeast(1)
    return (avg / 255.0) * calibrationConstant
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
          session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
          isStreaming = true
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
}
