package com.example.rxCameraBasic.rxcamera2

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.arch.lifecycle.Lifecycle
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.media.MediaRecorder
import android.util.Pair
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import com.example.rxCameraBasic.AutoFitTextureView
import com.example.rxCameraBasic.CameraControllerBase
import com.example.rxCameraBasic.MainActivity.Companion.getVideoFilePath
import com.example.rxCameraBasic.rxcamera2.CameraRxWrapper.CameraCaptureFailedException
import com.example.rxCameraBasic.rxcamera2.CameraRxWrapper.CaptureSessionData
import com.example.rxCameraBasic.rxcamera2.CameraRxWrapper.CreateCaptureSessionException
import com.example.rxCameraBasic.rxcamera2.CameraRxWrapper.fromSetRepeatingRequest
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.functions.BiFunction
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import java.io.IOException
import java.util.*

@TargetApi(21)
class Camera2Controller(context: Context,
                        private val callback: Callback,
                        photoFileUrl: String,
                        lifecycle: Lifecycle,
                        private val textureView: AutoFitTextureView,
                        videoButtonCallback: VideoButtonCallback) : CameraControllerBase(photoFileUrl, lifecycle, textureView, videoButtonCallback) {

    private val windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val cameraManager: CameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var imageReader: ImageReader? = null

    private val autoFocusConvergeWaiter = ConvergeWaiter.Factory.createAutoFocusConvergeWaiter()
    private val autoExposureConvergeWaiter = ConvergeWaiter.Factory.createAutoExposureConvergeWaiter()

    private var cameraParams: CameraParams? = null

    override fun onCreate() {
        log("onCreate")

        try {
            log("choosing default camera")

            val cameraId: String? = CameraStrategy.chooseDefaultCamera(cameraManager)

            if (cameraId == null) {
                onException(IllegalStateException("Can't find any camera"))
                return
            }

            cameraParams = getCameraParams(cameraId)
        } catch (e: CameraAccessException) {
            onException(e)
            return
        }
    }

    override fun subscribe() {
        super.subscribe()

        // Открываем камеру после того, как SurfaceTexture готов к использованию.
        val cameraDeviceObservable: Observable<Pair<CameraRxWrapper.DeviceStateEvents, CameraDevice>> = onSurfaceTextureAvailable
                .firstElement()
                .doAfterSuccess { this.setupSurface(it) }
                .doAfterSuccess { initImageReader() }
                .doAfterSuccess { initMediaRecorder() }
                .toObservable()
                .flatMap { CameraRxWrapper.openCamera(cameraParams!!.cameraId, cameraManager) }
                .share()

        // Observable, сигнализирующий об успешном открытии камеры
        val openCameraObservable = cameraDeviceObservable
                .filter { pair -> pair.first === CameraRxWrapper.DeviceStateEvents.ON_OPENED }
                .map { pair -> pair.second }
                .share()

        // Observable, сигнализирующий об успешном закрытии камеры
        val closeCameraObservable = cameraDeviceObservable
                .filter { pair -> pair.first === CameraRxWrapper.DeviceStateEvents.ON_CLOSED }
                .map { pair -> pair.second }
                .share()

        subscribeCaptureSession(openCameraObservable, closeCameraObservable)
    }

    private fun subscribeCaptureSession(openCameraObservable: Observable<CameraDevice>, closeCameraObservable: Observable<CameraDevice>) {
        log("subscribeCaptureSession, is video session = $isRecordingVideo")

        // после успешного открытия камеры откроем сессию
        val createCaptureSessionObservable = openCameraObservable
                .flatMap { cameraDevice -> CameraRxWrapper.createCaptureSession(cameraDevice, getAvailableSurfaces()) }
                .share()

        // Observable, сигнализирующий об успешном открытии сессии
        val captureSessionConfiguredObservable = createCaptureSessionObservable
                .filter { pair -> pair.first === CameraRxWrapper.CaptureSessionStateEvents.ON_CONFIGURED }
                .doOnNext { log("session configured, is video session = $isRecordingVideo") }
                .map { pair -> pair.second }
                .doOnNext { callback.hideWaitView() }
                .share()

        // Observable, сигнализирующий об успешном закрытии сессии
        val captureSessionClosedObservable = createCaptureSessionObservable
                .filter { pair -> pair.first === CameraRxWrapper.CaptureSessionStateEvents.ON_CLOSED }
                .map { pair -> pair.second }
                .doOnNext { log("session closed, is video session = $isRecordingVideo") }
                .share()

        //  повторяющийся запрос для отображения preview
        val previewObservable = captureSessionConfiguredObservable
                .flatMap { startPreview(it) }
                .share()

        // реакция на спуск затвора
        compositeDisposable.add(
                Observable.combineLatest(previewObservable, onShutterClick, BiFunction { captureSessionData: CaptureSessionData, _: Any -> captureSessionData })
                        .firstElement().toObservable()
                        .doOnNext { log("on shutter click") }
                        .doOnNext { callback.showFocusStarted() }
                        .flatMap { t1 -> this.waitForAf(t1) }
                        .flatMap { t2 -> this.waitForAe(t2) }
                        .doOnNext { callback.showFocusFinished() }
                        .doOnNext { callback.showWaitView() }
                        .flatMap { captureSessionData -> captureStillPicture(captureSessionData.session) }
                        .subscribe({ }, { this.onError(it) })
        )

        // реакция на изменение камеры
        compositeDisposable.add(
                Observable.combineLatest(previewObservable, onSwitchCameraClick, BiFunction { captureSessionData: CaptureSessionData, _: Any -> captureSessionData })
                        .firstElement().toObservable()
                        .doOnNext { log("on switch camera click") }
                        .doOnNext { callback.showWaitView() }
                        .doOnNext { closeCaptureSession(it) }
                        .flatMap { _ -> captureSessionClosedObservable }
                        .doOnNext { cameraCaptureSession -> cameraCaptureSession.device.close() }
                        .flatMap { _ -> closeCameraObservable }
                        .doOnNext { closeImageReader() }
                        .doOnNext { closeMediaRecorder() }
                        .subscribe({ switchCameraInternal() }, { this.onError(it) })
        )

        // реакция на onPause
        compositeDisposable.add(Observable.combineLatest(previewObservable, onPauseSubject, BiFunction { state: CaptureSessionData, _: Any -> state })
                .firstElement().toObservable()
                .doOnNext { log("on pause") }
                .doOnNext { captureSessionData ->
                    try {
                        captureSessionData.session.stopRepeating()
                        captureSessionData.session.abortCaptures()
                    } catch (e: Exception) {
                        log("onPause err: ${e.message}")
                    }
                    captureSessionData.session.close()
                }
                .flatMap { _ -> captureSessionClosedObservable }
                .doOnNext { cameraCaptureSession -> cameraCaptureSession.device.close() }
                .flatMap { _ -> closeCameraObservable }
                .doOnNext { closeImageReader() }
                .doOnNext { closeMediaRecorder() }
                .subscribe({ unsubscribe() }, { this.onError(it) })
        )

        // реакция на onStartVideo
        compositeDisposable.add(Observable.combineLatest(previewObservable, onStartVideoClick, BiFunction { state: CaptureSessionData, _: Any -> state })
                .firstElement().toObservable()
                .doOnNext { log("request start video") }
                .doOnNext { callback.showWaitView() }
                .doOnNext { setUpMediaRecorder() }
                .doOnNext { closeCaptureSession(it) }
                .flatMap { _ -> captureSessionClosedObservable }
                .doOnNext { setVideoBtnState(true) }
                .subscribe({ captureSession -> recreateSession(captureSession.device, closeCameraObservable) }, { this.onError(it) })
        )

        // реакция на onStopVideo
        compositeDisposable.add(Observable.combineLatest(previewObservable, onStopVideoClick, BiFunction { state: CaptureSessionData, _: Any -> state })
                .firstElement().toObservable()
                .doOnNext { log("on stop video") }
                .doOnNext { callback.showWaitView() }
                .doOnNext { captureSessionData ->
                    try {
                        captureSessionData.session.stopRepeating()
                        captureSessionData.session.abortCaptures()
                    } catch (e: Exception) {
                        log("onStopVideo err: ${e.message}")
                    }
                }
                .doOnNext { stopRecordingVideo() }
                .doOnNext { closeCaptureSession(it) }
                .flatMap { _ -> captureSessionClosedObservable }
                .doOnNext { setVideoBtnState(false) }
                .subscribe({ captureSession -> recreateSession(captureSession.device, closeCameraObservable) }, { this.onError(it) })
        )

        if (isRecordingVideo) {
            val recordingVideoRequest = PublishSubject.create<Any>()
            compositeDisposable.add(Observable.combineLatest(previewObservable, recordingVideoRequest, BiFunction { state: CaptureSessionData, _: Any -> state })
                    .firstElement().toObservable()
                    .subscribe({
                        if (isRecordingVideo) {
                            startRecordingVideo()
                        }
                    }, { this.onError(it) })
            )

            recordingVideoRequest.onNext(this)
        }
    }

    private fun startPreview(cameraCaptureSession: CameraCaptureSession): Observable<CaptureSessionData> {
        log("start preview")

        val previewBuilder = if (isRecordingVideo) {
            createVideoBuilder(cameraCaptureSession)
        } else {
            createPreviewBuilder(cameraCaptureSession)
        }
        return fromSetRepeatingRequest(cameraCaptureSession, previewBuilder.build())
    }

    private fun recreateSession(device: CameraDevice, closeCameraObservable: Observable<CameraDevice>) {
        log("recreateSession")

        unsubscribe()

        compositeDisposable.add(
                ImageSaverRxWrapper.createOnImageAvailableObservable(imageReader!!)
                        .observeOn(Schedulers.io())
                        .flatMap { imageReader -> ImageSaverRxWrapper.save(imageReader.acquireLatestImage(), file).toObservable() }
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe { file -> callback.onPhotoTaken(file.absolutePath) }
        )

        subscribeCaptureSession(Observable.create<CameraDevice> { emitter -> emitter.onNext(device) }.share(), closeCameraObservable)
    }

    private fun closeCaptureSession(captureSessionData: CaptureSessionData) {
        log("close capture session, is video session = $isRecordingVideo")

        captureSessionData.session.close()
    }

    private fun switchCameraInternal() {
        try {
            unsubscribe()

            val cameraId = CameraStrategy.switchCamera(cameraManager, cameraParams!!.cameraId)
            cameraParams = getCameraParams(cameraId!!)
            subscribe()

            onSurfaceTextureAvailable.onNext(textureView.surfaceTexture)
        } catch (e: CameraAccessException) {
            onError(e)
        }
    }

    @SuppressLint("Recycle")
    private fun setupSurface(surfaceTexture: SurfaceTexture) {
        log("\tsetup surface")

        surfaceTexture.setDefaultBufferSize(cameraParams!!.previewSize.width, cameraParams!!.previewSize.height)
        surface = Surface(surfaceTexture)
    }

    private fun onError(throwable: Throwable) {
        unsubscribe()

        when (throwable) {
            is CameraCaptureFailedException -> onCameraCaptureFailedException(throwable)
            is CameraAccessException -> onCameraAccessException(throwable)
            is OpenCameraException -> onCameraOpenException(throwable)
            is CreateCaptureSessionException -> onCreateCaptureSessionException(throwable)
            else -> onException(throwable)
        }
    }

    private fun startRecordingVideo() {
        log("start recording video")

        mediaRecorder!!.start()
    }

    private fun stopRecordingVideo() {
        log("stop recording video")

        mediaRecorder!!.apply {
            try {
                stop()

                callback.onVideoTaken(videoAbsolutePath!!)
            } catch (e: Exception) {
                callback.showMessage("Empty video")//todo???
            }

            reset()
        }

        log("Video saved: $videoAbsolutePath")
        videoAbsolutePath = null
    }

    @Throws(IOException::class)
    private fun setUpMediaRecorder() {
        if (videoAbsolutePath.isNullOrEmpty()) {
            videoAbsolutePath = getVideoFilePath()
        }

        mediaRecorder!!.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setAudioSource(MediaRecorder.AudioSource.CAMCORDER)//todo??MIC
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(videoAbsolutePath)

            val rotation = windowManager.defaultDisplay.rotation
            when (cameraParams!!.sensorOrientation) {
                CameraOrientationHelper.SENSOR_ORIENTATION_DEFAULT_DEGREES ->
                    mediaRecorder!!.setOrientationHint(CameraOrientationHelper.getDefaultOrientation(rotation))
                CameraOrientationHelper.SENSOR_ORIENTATION_INVERSE_DEGREES ->
                    mediaRecorder!!.setOrientationHint(CameraOrientationHelper.getInverseOrientation(rotation))
            }

            setVideoEncodingBitRate(10000000)
            setVideoFrameRate(20)
            setVideoSize(cameraParams!!.videoSize.width, cameraParams!!.videoSize.height)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            prepare()
        }
    }

    private fun initImageReader() {
        val sizeForImageReader = CameraStrategy.getStillImageSize(cameraParams!!.cameraCharacteristics, cameraParams!!.previewSize)
        imageReader = ImageReader.newInstance(sizeForImageReader.width, sizeForImageReader.height, ImageFormat.JPEG, 1)

        compositeDisposable.add(
                ImageSaverRxWrapper.createOnImageAvailableObservable(imageReader!!)
                        .observeOn(Schedulers.io())
                        .flatMap { imageReader -> ImageSaverRxWrapper.save(imageReader.acquireLatestImage(), file).toObservable() }
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe { file -> callback.onPhotoTaken(file.absolutePath) }
        )
    }

    private fun closeImageReader() {
        if (imageReader != null) {
            imageReader!!.close()
            imageReader = null
        }
    }

    private fun waitForAf(captureResultParams: CaptureSessionData): Observable<CaptureSessionData> {
        return Observable
                .fromCallable { createPreviewBuilder(captureResultParams.session) }
                .flatMap { previewBuilder ->
                    autoFocusConvergeWaiter
                            .waitForConverge(captureResultParams, previewBuilder)
                            .toObservable()
                }
    }

    private fun waitForAe(captureResultParams: CaptureSessionData): Observable<CaptureSessionData> {
        return Observable
                .fromCallable { createPreviewBuilder(captureResultParams.session) }
                .flatMap { previewBuilder ->
                    autoExposureConvergeWaiter
                            .waitForConverge(captureResultParams, previewBuilder)
                            .toObservable()
                }
    }

    private fun captureStillPicture(cameraCaptureSession: CameraCaptureSession): Observable<CaptureSessionData> {
        return Observable
                .fromCallable { createStillPictureBuilder(cameraCaptureSession.device) }
                .flatMap { builder -> CameraRxWrapper.fromCapture(cameraCaptureSession, builder.build()) }
    }

    @Throws(CameraAccessException::class)
    private fun createPreviewBuilder(cameraCaptureSession: CameraCaptureSession): CaptureRequest.Builder {
        return createPreviewBuilder(cameraCaptureSession, cameraParams!!, surface!!)
    }

    @Throws(CameraAccessException::class)
    private fun createVideoBuilder(captureSession: CameraCaptureSession): CaptureRequest.Builder {
        return createVideoBuilder(captureSession, cameraParams!!, surface!!)
    }

    @Throws(CameraAccessException::class)
    private fun createPreviewBuilder(captureSession: CameraCaptureSession, cameraParams: CameraParams, previewSurface: Surface): CaptureRequest.Builder {
        val builder = captureSession.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)

        builder.addTarget(previewSurface)
        setup3Auto(cameraParams, builder)

        return builder
    }

    @Throws(CameraAccessException::class)
    private fun createVideoBuilder(captureSession: CameraCaptureSession, cameraParams: CameraParams, previewSurface: Surface): CaptureRequest.Builder {
        val builder: CaptureRequest.Builder = captureSession.device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)

        builder.addTarget(previewSurface)
        builder.addTarget(mediaRecorder!!.surface)
        setup3Auto(cameraParams, builder)

        return builder
    }

    @Throws(CameraAccessException::class)
    private fun createStillPictureBuilder(cameraDevice: CameraDevice): CaptureRequest.Builder {
        val builder: CaptureRequest.Builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
        builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
        builder.addTarget(imageReader!!.surface)
        setup3Auto(cameraParams!!, builder)

        val rotation = windowManager.defaultDisplay.rotation
        builder.set(CaptureRequest.JPEG_ORIENTATION, CameraOrientationHelper.getJpegOrientation(cameraParams!!.cameraCharacteristics, rotation))

        return builder
    }

    private fun setup3Auto(cameraParams: CameraParams, builder: CaptureRequest.Builder) {
        // Enable auto-magical 3A run by camera device
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

        val minFocusDist = cameraParams.cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)

        // If MINIMUM_FOCUS_DISTANCE is 0, lens is fixed-focus and we need to skip the AF run.
        val noAFRun = minFocusDist == null || minFocusDist == 0.0f

        if (!noAFRun) {
            // If there is a "continuous picture" mode available, use it, otherwise default to AUTO.
            val afModes = cameraParams.cameraCharacteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            if (contains(afModes, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            } else {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            }
        }

        // If there is an auto-magical flash control mode available, use it, otherwise default to
        // the "on" mode, which is guaranteed to always be available.
        val aeModes = cameraParams.cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
        if (contains(aeModes, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }

        // If there is an auto-magical white balance control mode available, use it.
        val awbModes = cameraParams.cameraCharacteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
        if (contains(awbModes, CaptureRequest.CONTROL_AWB_MODE_AUTO)) {
            // Allow AWB to run auto-magically if this device supports this
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        }
    }

    private fun getAvailableSurfaces(): List<Surface> {
        return if (isRecordingVideo) {
            Arrays.asList<Surface>(surface, imageReader!!.surface, mediaRecorder!!.surface)
        } else {
            Arrays.asList<Surface>(surface, imageReader!!.surface)
        }
    }

    @Throws(CameraAccessException::class)
    private fun getCameraParams(cameraId: String): CameraParams {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw RuntimeException("Cannot get available preview/video sizes")

        val videoSize = CameraStrategy.chooseVideoSize(map.getOutputSizes(MediaRecorder::class.java))
        val previewSize = CameraStrategy.chooseOptimalSize(
                map.getOutputSizes(SurfaceTexture::class.java),
                textureView.width,
                textureView.height,
                videoSize
        )

        return CameraParams(cameraId, characteristics, previewSize, videoSize, characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION))
    }

    private fun onException(throwable: Throwable) {
        log("Camera Exception: " + throwable.message)

        callback.showError("Ошибка приложения. ${throwable.message}")
    }

    private fun onCameraAccessException(exception: CameraAccessException) {
        log("Camera Access Exception: " + exception.message)

        callback.showError("Ошибка при работе камеры")
    }

    private fun onCameraCaptureFailedException(exception: CameraCaptureFailedException) {
        log("Camera Capture Failed Exception: ${exception.message}, ${exception.cause}, failure reason ${exception.mFailure.reason}")

        callback.showError("Ошибка при работе камеры")
    }

    private fun onCameraOpenException(exception: OpenCameraException) {
        log("Camera Open Exception: reason ${exception.reason}:" + exception.message)

        callback.showError("Ошибка при открытии камеры")
    }

    private fun onCreateCaptureSessionException(exception: CreateCaptureSessionException) {
        log("Create Capture Session Exception: reason ${exception.session}:" + exception.message)

        callback.showError("Ошибка сеанса работы камеры")
    }

    //--------------------------------------------------------------------------------------------------
    private inner class CameraParams internal constructor(val cameraId: String,
                                                          val cameraCharacteristics: CameraCharacteristics,
                                                          val previewSize: Size,
                                                          val videoSize: Size,
                                                          val sensorOrientation: Int)
    //--------------------------------------------------------------------------------------------------
}
