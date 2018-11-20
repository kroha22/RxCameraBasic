package com.example.RxCameraBasic.rxcamera2

import android.annotation.TargetApi
import android.arch.lifecycle.Lifecycle
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.CamcorderProfile
import android.media.ImageReader
import android.media.MediaRecorder
import android.util.Pair
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import com.example.RxCameraBasic.AutoFitTextureView
import com.example.RxCameraBasic.CameraControllerBase
import com.example.RxCameraBasic.rxcamera2.CameraRxWrapper.CaptureSessionData
import com.example.RxCameraBasic.rxcamera2.CameraRxWrapper.fromSetRepeatingRequest
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.functions.BiFunction
import io.reactivex.schedulers.Schedulers
import java.io.IOException
import java.util.*

@TargetApi(21)
class Camera2Controller(context: Context,
                        private val callback: Callback,
                        photoFileUrl: String,
                        lifecycle: Lifecycle,
                        private val textureView: AutoFitTextureView,
                        videoButtonCallback: VideoButtonCallback): CameraControllerBase(context, photoFileUrl, lifecycle, textureView, videoButtonCallback) {

    private val windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val cameraManager: CameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var imageReader: ImageReader? = null

    private val autoFocusConvergeWaiter = ConvergeWaiter.Factory.createAutoFocusConvergeWaiter()
    private val autoExposureConvergeWaiter = ConvergeWaiter.Factory.createAutoExposureConvergeWaiter()

    private var cameraParams: CameraParams? = null

    override fun onCreate() {

        showLog("\tonCreate")

        try {
            showLog("\tchoosing default camera")
            val cameraId: String? = CameraStrategy.chooseDefaultCamera(cameraManager)

            if (cameraId == null) {
                callback.onException(IllegalStateException("Can't find any camera"))
                return
            }

            cameraParams = getCameraParams(cameraId)
        } catch (e: CameraAccessException) {
            callback.onException(e)
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

        subscribe(openCameraObservable, closeCameraObservable)
    }

    fun subscribe(openCameraObservable: Observable<CameraDevice>, closeCameraObservable: Observable<CameraDevice>?) {

        // после успешного открытия камеры откроем сессию
        val createCaptureSessionObservable = openCameraObservable
                .flatMap { cameraDevice ->
                    CameraRxWrapper.createCaptureSession( cameraDevice, getAvailableSurfaces())
                }
                .share()

        // Observable, сигнализирующий об успешном открытии сессии
        val captureSessionConfiguredObservable = createCaptureSessionObservable
                .filter { pair -> pair.first === CameraRxWrapper.CaptureSessionStateEvents.ON_CONFIGURED }
                .map { pair -> pair.second }
                .share()

        // Observable, сигнализирующий об успешном закрытии сессии
        val captureSessionClosedObservable = createCaptureSessionObservable
                .filter { pair -> pair.first === CameraRxWrapper.CaptureSessionStateEvents.ON_CLOSED }
                .map { pair -> pair.second }
                .share()

        //  повторяющийся запрос для отображения preview
        val previewObservable = captureSessionConfiguredObservable
                .flatMap { cameraCaptureSession ->
                    showLog("\tstartPreview")

                    val previewBuilder = createPreviewBuilder(cameraCaptureSession, surface)
                    fromSetRepeatingRequest(cameraCaptureSession, previewBuilder.build())
                }
                .doFinally {
                    if (isRecordingVideo){
                        //todo???startRecordingVideo()
                    }
                }
                .share()

        // реакция на спуск затвора
        compositeDisposable.add(
                Observable.combineLatest(previewObservable, onShutterClick, BiFunction { captureSessionData: CaptureSessionData, _: Any -> captureSessionData })
                        .firstElement().toObservable()
                        .doOnNext { _ -> showLog("\ton shutter click") }
                        .doOnNext { _ -> callback.onFocusStarted() }
                        .flatMap { t1 -> this.waitForAf(t1) }
                        .flatMap { t2 -> this.waitForAe(t2) }
                        .doOnNext { _ -> callback.onFocusFinished() }
                        .flatMap { captureSessionData -> captureStillPicture(captureSessionData.session) }
                        .subscribe({ _ -> }, { this.onError(it) })
        )

        // реакция на изменение камеры
        compositeDisposable.add(
                Observable.combineLatest(previewObservable, onSwitchCameraClick, BiFunction { captureSessionData: CaptureSessionData, _: Any -> captureSessionData })
                        .firstElement().toObservable()
                        .doOnNext { _ -> showLog("\ton switch camera click") }
                        .doOnNext { closeCaptureSession(it) }
                        .flatMap { _ -> captureSessionClosedObservable }
                        .doOnNext { cameraCaptureSession -> cameraCaptureSession.device.close() }
                        .flatMap { _ -> closeCameraObservable }
                        .doOnNext { _ -> closeImageReader() }
                        .doOnNext { _ -> closeMediaRecorder() }
                        .subscribe({ _ -> switchCameraInternal() }, { this.onError(it) })
        )

        // реакция на onPause
        compositeDisposable.add(Observable.combineLatest(previewObservable, onPauseSubject, BiFunction { state: CaptureSessionData, _: Any -> state })
                .firstElement().toObservable()
                .doOnNext { _ -> showLog("\ton pause") }
                .doOnNext { captureSessionData ->
                    captureSessionData.session.stopRepeating()
                    captureSessionData.session.abortCaptures()
                    captureSessionData.session.close()
                }
                .flatMap { _ -> captureSessionClosedObservable }
                .doOnNext { cameraCaptureSession -> cameraCaptureSession.device.close() }
                .doOnNext { _ -> surface?.release() }
                .flatMap { _ -> closeCameraObservable }
                .doOnNext { _ -> closeImageReader() }
                .doOnNext { _ -> closeMediaRecorder() }
                .subscribe({ _ -> unsubscribe() }, { this.onError(it) })
        )

        // реакция на onStartVideo
        compositeDisposable.add(Observable.combineLatest(previewObservable, onStartVideoClick, BiFunction { state: CaptureSessionData, _: Any -> state })
                .firstElement().toObservable()
                .doOnNext { _ -> showLog("\ton start video") }
                .doOnNext { _ -> setVideoBtnState(true)}
                .doOnNext { setUpMediaRecorder() }
                .doOnNext { closeCaptureSession(it) }
                .flatMap { _ -> captureSessionClosedObservable }
                .subscribe({ captureSession ->
                    subscribe(Observable.create{it.onNext(captureSession.device)}, closeCameraObservable)
                   // onSurfaceTextureAvailable.onNext(textureView.surfaceTexture)
                },
                        { this.onError(it) })
        )

        // реакция на onStopVideo
        compositeDisposable.add(Observable.combineLatest(previewObservable, onStopVideoClick, BiFunction { state: CaptureSessionData, _: Any -> state })
                .firstElement().toObservable()
                .doOnNext { _ -> showLog("\ton stop video") }
                .doOnNext { _ -> setVideoBtnState(false)}
                .doOnNext { _ -> stopRecordingVideo()}
                .doOnNext { closeCaptureSession(it) }
                .flatMap { _ -> captureSessionClosedObservable }
                .subscribe({ captureSession ->
                    subscribe(Observable.create{it.onNext(captureSession.device)}, closeCameraObservable)
                    // onSurfaceTextureAvailable.onNext(textureView.surfaceTexture)
                }, { this.onError(it) })
        )
    }

    fun getAvailableSurfaces(): List<Surface> {
        return if (isRecordingVideo) {
            Arrays.asList<Surface>(surface, imageReader!!.surface, mediaRecorder!!.surface)
        } else {
            Arrays.asList<Surface>(surface, imageReader!!.surface)
        }
    }

    @Throws(CameraAccessException::class)
    private fun getCameraParams(cameraId: String): CameraParams {
        showLog("\tsetupPreviewSize")

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

    private fun setupSurface(surfaceTexture: SurfaceTexture) {
        /*DEBUG*/showLog("\tsetupSurface $surfaceTexture")
        surfaceTexture.setDefaultBufferSize(cameraParams!!.previewSize.width, cameraParams!!.previewSize.height)
        surface = Surface(surfaceTexture)
    }

    private fun closeCaptureSession(captureSessionData: CaptureSessionData) {
        /*DEBUG*/showLog("\tcloseCaptureSession")

        captureSessionData.session.close()
    }

    private fun onError(throwable: Throwable) {
        /*DEBUG*/showLog("\tonError: " + throwable.message + "\n" + throwable.cause)

        unsubscribe()
        when (throwable) {
            is CameraAccessException -> callback.onCameraAccessException()
            is OpenCameraException -> callback.onCameraOpenException(throwable)
            else -> callback.onException(throwable)
        }
    }

    private fun switchCameraInternal() {
        /*DEBUG*/showLog("\tswitchCameraInternal")
        try {
            unsubscribe()
            val cameraId = CameraStrategy.switchCamera(cameraManager, cameraParams!!.cameraId)
            cameraParams = getCameraParams(cameraId!!)
            subscribe()

            onSurfaceTextureAvailable.onNext(textureView.surfaceTexture)
            // waiting for textureView to be measured
        } catch (e: CameraAccessException) {
            onError(e)
        }

    }

    private fun initImageReader() {
        /*DEBUG*/showLog("\tinitImageReader")
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

    private fun waitForAf(captureResultParams: CaptureSessionData): Observable<CaptureSessionData> {
        /*DEBUG*/showLog("\twaitForAf")
        return Observable
                .fromCallable { createPreviewBuilder(captureResultParams.session, surface) }
                .flatMap { previewBuilder ->
                    autoFocusConvergeWaiter
                            .waitForConverge(captureResultParams, previewBuilder)
                            .toObservable()
                }
    }

    private fun waitForAe(captureResultParams: CaptureSessionData): Observable<CaptureSessionData> {
        /*DEBUG*/showLog("\twaitForAe")
        return Observable
                .fromCallable { createPreviewBuilder(captureResultParams.session, surface) }
                .flatMap { previewBuilder ->
                    autoExposureConvergeWaiter
                            .waitForConverge(captureResultParams, previewBuilder)
                            .toObservable()
                }
    }

    private fun captureStillPicture(cameraCaptureSession: CameraCaptureSession): Observable<CaptureSessionData> {
        /*DEBUG*/showLog("\tcaptureStillPicture")

        return Observable
                .fromCallable { createStillPictureBuilder(cameraCaptureSession.device) }
                .flatMap { builder -> CameraRxWrapper.fromCapture(cameraCaptureSession, builder.build()) }
    }

    @Throws(CameraAccessException::class)
    private fun createStillPictureBuilder(cameraDevice: CameraDevice): CaptureRequest.Builder {
        /*DEBUG*/showLog("\tcreateStillPictureBuilder")
        val builder: CaptureRequest.Builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
        builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
        builder.addTarget(imageReader!!.surface)
        setup3Auto(builder)

        val rotation = windowManager.defaultDisplay.rotation
        builder.set(CaptureRequest.JPEG_ORIENTATION, CameraOrientationHelper.getJpegOrientation(cameraParams!!.cameraCharacteristics, rotation))
        return builder
    }

    @Throws(CameraAccessException::class)
    internal fun createPreviewBuilder(captureSession: CameraCaptureSession, previewSurface: Surface?): CaptureRequest.Builder {
        /*DEBUG*/showLog("\tcreatePreviewBuilder, isRecordingVideo $isRecordingVideo")

        if (isRecordingVideo) {
            val builder: CaptureRequest.Builder = captureSession.device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)

            builder.addTarget(previewSurface)
            builder.addTarget(mediaRecorder!!.surface)
            setup3Auto(builder)

            val rotation = windowManager.defaultDisplay.rotation
            builder.set(CaptureRequest.JPEG_ORIENTATION, CameraOrientationHelper.getJpegOrientation(cameraParams!!.cameraCharacteristics, rotation))

            return builder
        } else {
            val builder = captureSession.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(previewSurface)
            setup3Auto(builder)
            return builder
        }
    }

    private fun setup3Auto(builder: CaptureRequest.Builder) {
        /*DEBUG*/showLog("\tsetup3Auto")
        // Enable auto-magical 3A run by camera device
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

        val minFocusDist = cameraParams!!.cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)

        // If MINIMUM_FOCUS_DISTANCE is 0, lens is fixed-focus and we need to skip the AF run.
        val noAFRun = minFocusDist == null || minFocusDist == 0.0f

        if (!noAFRun) {
            // If there is a "continuous picture" mode available, use it, otherwise default to AUTO.
            val afModes = cameraParams!!.cameraCharacteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            if (contains(afModes, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            } else {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            }
        }

        // If there is an auto-magical flash control mode available, use it, otherwise default to
        // the "on" mode, which is guaranteed to always be available.
        val aeModes = cameraParams!!.cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
        if (contains(aeModes, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }

        // If there is an auto-magical white balance control mode available, use it.
        val awbModes = cameraParams!!.cameraCharacteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
        if (contains(awbModes, CaptureRequest.CONTROL_AWB_MODE_AUTO)) {
            // Allow AWB to run auto-magically if this device supports this
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        }
    }

    @Throws(IOException::class)
    private fun setUpMediaRecorder() {
        /*DEBUG*/showLog("\tsetUpMediaRecorder")

        if (nextVideoAbsolutePath.isNullOrEmpty()) {
            nextVideoAbsolutePath = getVideoFilePath()
        }

        mediaRecorder!!.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setAudioSource(MediaRecorder.AudioSource.CAMCORDER)//todo??MIC
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(nextVideoAbsolutePath)

            val rotation = windowManager.defaultDisplay.rotation
            when (cameraParams!!.sensorOrientation) {
                CameraOrientationHelper.SENSOR_ORIENTATION_DEFAULT_DEGREES ->
                    mediaRecorder!!.setOrientationHint(CameraOrientationHelper.getDefaultOrientation(rotation))
                CameraOrientationHelper.SENSOR_ORIENTATION_INVERSE_DEGREES ->
                    mediaRecorder!!.setOrientationHint(CameraOrientationHelper.getInverseOrientation(rotation))
            }

            setVideoEncodingBitRate(10000000)
            setVideoFrameRate(30)
            setVideoSize(cameraParams!!.videoSize.width, cameraParams!!.videoSize.height)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            prepare()
        }
    }

    private fun startRecordingVideo() {
        showLog("\tstart recording video")

        mediaRecorder!!.start()
    }

    private fun stopRecordingVideo() {
        showLog("\tstop recording video")

        mediaRecorder!!.apply {
            try {
                stop()
            } catch (e: Exception) {
                callback.onMessage("Empty video")//todo???
            }
            reset()
        }

        callback.onMessage("Video saved: $nextVideoAbsolutePath")

        nextVideoAbsolutePath = null
    }

    private fun closeImageReader() {
        /*DEBUG*/showLog("\tcloseImageReader")
        if (imageReader != null) {
            imageReader!!.close()
            imageReader = null
        }
    }

    //--------------------------------------------------------------------------------------------------
    private inner class CameraParams internal constructor(val cameraId: String,
                                                          val cameraCharacteristics: CameraCharacteristics,
                                                          val previewSize: Size,
                                                          val videoSize: Size,
                                                          val sensorOrientation:Int)
    //--------------------------------------------------------------------------------------------------
}
