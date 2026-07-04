package com.example.wallpaperapplication

import android.content.Context
import android.hardware.camera2.CameraManager as AndroidCameraManager
import android.os.Build
import android.util.Log
import org.webrtc.*

class CameraManager(private val context: Context, private val eglBase: EglBase) {

    private var factory: PeerConnectionFactory? = null
    private var backCapturer: CameraVideoCapturer? = null
    private var frontCapturer: CameraVideoCapturer? = null
    private var backHelper: SurfaceTextureHelper? = null
    private var frontHelper: SurfaceTextureHelper? = null
    private var backSource: VideoSource? = null
    private var frontSource: VideoSource? = null

    private var backTrack: VideoTrack? = null
    private var frontTrack: VideoTrack? = null

    fun initialize(factory: PeerConnectionFactory) {
        this.factory = factory
        setupCapturers()
    }

    private fun setupCapturers() {
        val f = factory ?: return
        val enumerator: CameraEnumerator = if (Camera2Enumerator.isSupported(context)) {
            Camera2Enumerator(context)
        } else {
            Camera1Enumerator(true)
        }
        val deviceNames = enumerator.deviceNames

        // Setup Back Camera
        for (name in deviceNames) {
            if (enumerator.isBackFacing(name)) {
                try {
                    backCapturer = enumerator.createCapturer(name, null)
                    backHelper = SurfaceTextureHelper.create("BackVideoThread", eglBase.eglBaseContext)
                    backSource = f.createVideoSource(false)
                    backSource?.let {
                        backCapturer?.initialize(backHelper, context.applicationContext, it.capturerObserver)
                        backTrack = f.createVideoTrack("back_video", it)
                    }
                    Log.d("CameraManager", "Back camera initialized: $name")
                } catch (e: Exception) {
                    Log.e("CameraManager", "Error setting up back camera", e)
                }
                break
            }
        }

        // Setup Front Camera
        for (name in deviceNames) {
            if (enumerator.isFrontFacing(name)) {
                try {
                    frontCapturer = enumerator.createCapturer(name, null)
                    frontHelper = SurfaceTextureHelper.create("FrontVideoThread", eglBase.eglBaseContext)
                    frontSource = f.createVideoSource(false)
                    frontSource?.let {
                        frontCapturer?.initialize(frontHelper, context.applicationContext, it.capturerObserver)
                        frontTrack = f.createVideoTrack("front_video", it)
                    }
                    Log.d("CameraManager", "Front camera initialized: $name")
                } catch (e: Exception) {
                    Log.e("CameraManager", "Error setting up front camera", e)
                }
                break
            }
        }
    }

    fun startBackCamera() {
        try {
            backCapturer?.startCapture(Constants.VIDEO_WIDTH, Constants.VIDEO_HEIGHT, Constants.VIDEO_FPS)
        } catch (e: Exception) {
            Log.e("CameraManager", "Failed to start back camera", e)
        }
    }

    fun startFrontCamera() {
        try {
            frontCapturer?.startCapture(Constants.VIDEO_WIDTH, Constants.VIDEO_HEIGHT, Constants.VIDEO_FPS)
        } catch (e: Exception) {
            Log.e("CameraManager", "Failed to start front camera", e)
        }
    }

    fun changeResolution(width: Int, height: Int, fps: Int) {
        Log.d("CameraManager", "Changing capturing resolution to: ${width}x${height} @ ${fps}fps")
        try {
            // Stop active capturers
            stopCapturers()
            // Re-start with new parameters
            backCapturer?.startCapture(width, height, fps)
            frontCapturer?.startCapture(width, height, fps)
        } catch (e: Exception) {
            Log.e("CameraManager", "Error changing camera capturing parameters", e)
        }
    }

    fun stopCapturers() {
        try {
            backCapturer?.stopCapture()
            frontCapturer?.stopCapture()
        } catch (e: Exception) {
            Log.e("CameraManager", "Error stopping capturers", e)
        }
    }

    /**
     * Checks if the device supports concurrent camera streaming (API 30+).
     * On API < 30, always returns false.
     */
    fun isConcurrentStreamingSupported(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as AndroidCameraManager
                return manager.concurrentCameraIds.isNotEmpty()
            } catch (e: Exception) {
                Log.e("CameraManager", "Error checking concurrent camera support", e)
            }
        }
        return false
    }

    fun dispose() {
        // Run disposal on a background thread to prevent ANR on main thread
        Thread {
            try {
                stopCapturers()
                backCapturer?.dispose()
                frontCapturer?.dispose()
                backHelper?.dispose()
                frontHelper?.dispose()
                backTrack?.dispose()
                frontTrack?.dispose()
                backSource?.dispose()
                frontSource?.dispose()
            } catch (e: Exception) {
                Log.e("CameraManager", "Disposal error", e)
            } finally {
                backCapturer = null
                frontCapturer = null
                backTrack = null
                frontTrack = null
            }
        }.start()
    }

    fun getBackTrack(): VideoTrack? = backTrack
    fun getFrontTrack(): VideoTrack? = frontTrack
    fun hasBackCamera(): Boolean = backTrack != null
    fun hasFrontCamera(): Boolean = frontTrack != null

    fun captureSnapshot(useFrontCamera: Boolean, callback: (String) -> Unit) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val cameraId = if (useFrontCamera) {
            manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                        android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
            }
        } else {
            manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                        android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
            }
        } ?: manager.cameraIdList.firstOrNull()

        if (cameraId == null) {
            Log.e("CameraManager", "No camera device found for snapshot")
            return
        }

        try {
            manager.openCamera(cameraId, object : android.hardware.camera2.CameraDevice.StateCallback() {
                override fun onOpened(camera: android.hardware.camera2.CameraDevice) {
                    takeSinglePicture(camera, callback)
                }
                override fun onDisconnected(camera: android.hardware.camera2.CameraDevice) {
                    camera.close()
                }
                override fun onError(camera: android.hardware.camera2.CameraDevice, error: Int) {
                    camera.close()
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) {
            Log.e("CameraManager", "Permission denied for camera snapshot", e)
        } catch (e: Exception) {
            Log.e("CameraManager", "Error opening camera for snapshot", e)
        }
    }

    private fun takeSinglePicture(camera: android.hardware.camera2.CameraDevice, callback: (String) -> Unit) {
        val imageReader = android.media.ImageReader.newInstance(640, 480, android.graphics.ImageFormat.JPEG, 1)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage()
            if (image != null) {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()
                camera.close()
                imageReader.close()

                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                callback(base64)
            }
        }, Handler(Looper.getMainLooper()))

        try {
            val builder = camera.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(imageReader.surface)

            val outputs = listOf(imageReader.surface)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val config = android.hardware.camera2.params.SessionConfiguration(
                    android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                    outputs.map { android.hardware.camera2.params.OutputConfiguration(it) },
                    java.util.concurrent.Executors.newSingleThreadExecutor(),
                    object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                            try {
                                session.capture(builder.build(), null, null)
                            } catch (e: Exception) {
                                camera.close()
                                imageReader.close()
                            }
                        }
                        override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                            camera.close()
                            imageReader.close()
                        }
                    }
                )
                camera.createCaptureSession(config)
            } else {
                @Suppress("DEPRECATION")
                camera.createCaptureSession(outputs, object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                        try {
                            session.capture(builder.build(), null, null)
                        } catch (e: Exception) {
                            camera.close()
                            imageReader.close()
                        }
                    }
                    override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                        camera.close()
                        imageReader.close()
                    }
                }, null)
            }
        } catch (e: Exception) {
            camera.close()
            imageReader.close()
            Log.e("CameraManager", "Capture session setup failed", e)
        }
    }
}
