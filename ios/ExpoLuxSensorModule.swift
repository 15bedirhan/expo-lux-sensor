import AVFoundation
import ExpoModulesCore
import ImageIO

public final class ExpoLuxSensorModule: Module {
  private let sessionQueue = DispatchQueue(label: "expo.modules.luxsensor.session")
  private let outputQueue = DispatchQueue(label: "expo.modules.luxsensor.output")
  private var captureSession: AVCaptureSession?
  private var videoOutput: AVCaptureVideoDataOutput?
  private var isRunning = false
  private var lastEmission = Date().timeIntervalSince1970
  private var configuration = LuxSensorConfiguration()
  private lazy var captureDelegate = LuxCaptureDelegate(owner: self)

  private struct LuxSensorConfiguration {
    var updateInterval: TimeInterval = 0.4
    var calibrationConstant: Double = 50
  }

  private struct LuxSensorOptions: Record {
    @Field var updateInterval: Double?
    @Field var calibrationConstant: Double?
  }

  private enum LuxSensorError: Error, LocalizedError {
    case permissionDenied
    case sessionUnavailable

    var errorDescription: String? {
      switch self {
      case .permissionDenied:
        return "Camera permission is required to start the lux sensor."
      case .sessionUnavailable:
        return "Unable to start camera session for lux measurements."
      }
    }
  }

  public func definition() -> ModuleDefinition {
    Name("ExpoLuxSensor")

    Events("onLuxChanged")

    AsyncFunction("getPermissionsAsync") { () -> [String: Any] in
      let status = AVCaptureDevice.authorizationStatus(for: .video)
      return self.permissionPayload(for: status)
    }

    AsyncFunction("requestPermissionsAsync") { () async -> [String: Any] in
      let currentStatus = AVCaptureDevice.authorizationStatus(for: .video)
      if currentStatus == .notDetermined {
        let granted = await withCheckedContinuation { continuation in
          AVCaptureDevice.requestAccess(for: .video) { granted in
            continuation.resume(returning: granted)
          }
        }
        return self.permissionPayload(for: granted ? .authorized : .denied)
      }
      return self.permissionPayload(for: currentStatus)
    }

    AsyncFunction("startAsync") { (options: LuxSensorOptions?) throws in
      try self.ensurePermission()
      try self.startCapture(with: options)
    }

    AsyncFunction("stopAsync") {
      self.stopCapture()
    }

    AsyncFunction("isRunningAsync") { () -> Bool in
      return self.isRunning
    }
  }

  private func ensurePermission() throws {
    guard AVCaptureDevice.authorizationStatus(for: .video) == .authorized else {
      throw LuxSensorError.permissionDenied
    }
  }

  private func startCapture(with options: LuxSensorOptions?) throws {
    var capturedError: Error?
    sessionQueue.sync {
      do {
        self.applyConfiguration(from: options)
        if let session = self.captureSession, session.isRunning {
          self.isRunning = true
          return
        }

        let session = AVCaptureSession()
        session.beginConfiguration()

        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back)
          ?? AVCaptureDevice.default(for: .video) else {
          throw LuxSensorError.sessionUnavailable
        }

        let input = try AVCaptureDeviceInput(device: device)
        if session.canAddInput(input) {
          session.addInput(input)
        }

        let output = AVCaptureVideoDataOutput()
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self.captureDelegate, queue: self.outputQueue)

        guard session.canAddOutput(output) else {
          throw LuxSensorError.sessionUnavailable
        }
        session.addOutput(output)

        session.sessionPreset = .medium
        session.commitConfiguration()
        session.startRunning()

        self.captureSession = session
        self.videoOutput = output
        self.isRunning = true
        self.lastEmission = 0
      } catch {
        capturedError = error
      }
    }

    if let error = capturedError {
      throw error
    }
  }

  private func stopCapture() {
    sessionQueue.sync {
      guard let session = self.captureSession else { return }
      session.stopRunning()
      self.videoOutput?.setSampleBufferDelegate(nil, queue: nil)
      self.videoOutput = nil
      self.captureSession = nil
      self.isRunning = false
    }
  }

  private func applyConfiguration(from options: LuxSensorOptions?) {
    if let interval = options?.updateInterval, interval > 0 {
      configuration.updateInterval = interval
    }
    if let calibration = options?.calibrationConstant, calibration > 0 {
      configuration.calibrationConstant = calibration
    }
  }

private func permissionPayload(for status: AVAuthorizationStatus) -> [String: Any] {
    let mapped: String
    switch status {
    case .authorized:
      mapped = "granted"
    case .denied, .restricted:
      mapped = "denied"
    case .notDetermined:
      fallthrough
    @unknown default:
      mapped = "undetermined"
    }

    return [
      "status": mapped,
      "granted": status == .authorized,
    ]
  }
}

extension ExpoLuxSensorModule {
  fileprivate func handleSampleBuffer(_ sampleBuffer: CMSampleBuffer) {
    guard isRunning,
          let luxValue = computeLux(sampleBuffer: sampleBuffer) else {
      return
    }

    let now = Date().timeIntervalSince1970
    if now - lastEmission < configuration.updateInterval {
      return
    }

    lastEmission = now

    let payload: [String: Any] = [
      "lux": luxValue,
      "timestamp": now * 1000,
    ]

    DispatchQueue.main.async {
      self.sendEvent("onLuxChanged", payload)
    }
  }

  private func computeLux(sampleBuffer: CMSampleBuffer) -> Double? {
    guard let rawMetadata = CMCopyDictionaryOfAttachments(
      allocator: nil,
      target: sampleBuffer,
      attachmentMode: CMAttachmentMode(kCMAttachmentMode_ShouldPropagate)
    ) else {
      return nil
    }

    let metadata = CFDictionaryCreateMutableCopy(nil, 0, rawMetadata) as NSMutableDictionary
    guard let exifData = metadata.value(forKey: "{Exif}") as? NSMutableDictionary else {
      return nil
    }

    guard let fNumber = exifData["FNumber"] as? Double,
          let exposureTime = exifData["ExposureTime"] as? Double,
          let isoArray = exifData["ISOSpeedRatings"] as? [Double],
          let iso = isoArray.first else {
      return nil
    }

    guard fNumber > 0, exposureTime > 0, iso > 0 else {
      return nil
    }

    let constant = configuration.calibrationConstant
    return (constant * fNumber * fNumber) / (exposureTime * iso)
  }
}

private final class LuxCaptureDelegate: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
  weak var owner: ExpoLuxSensorModule?

  init(owner: ExpoLuxSensorModule) {
    self.owner = owner
  }

  func captureOutput(
    _ output: AVCaptureOutput,
    didOutput sampleBuffer: CMSampleBuffer,
    from connection: AVCaptureConnection
  ) {
    owner?.handleSampleBuffer(sampleBuffer)
  }
}
