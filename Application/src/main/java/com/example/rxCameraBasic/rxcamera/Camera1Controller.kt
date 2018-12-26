package com.example.rxCameraBasic.rxcamera

import android.annotation.SuppressLint
import android.arch.lifecycle.Lifecycle
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.os.Environment
import android.util.Pair
import android.view.Surface
import com.example.rxCameraBasic.AutoFitTextureView
import com.example.rxCameraBasic.CameraControllerBase
import com.example.rxCameraBasic.MainActivity.Companion.getVideoFilePath
import com.example.rxCameraBasic.rxcamera2.ConvergeWaiter
import com.example.rxCameraBasic.rxcamera2.OpenCameraException
import io.reactivex.Observable
import io.reactivex.disposables.Disposable
import io.reactivex.functions.BiFunction
import io.reactivex.schedulers.Schedulers
import java.io.File

@Suppress("DEPRECATION")
class Camera1Controller(private val context: Context,
                        private val callback: Callback,
                        photoFileUrl: String,
                        lifecycle: Lifecycle,
                        private val textureView: AutoFitTextureView,
                        videoButtonCallback: VideoButtonCallback) : CameraControllerBase(photoFileUrl, lifecycle, textureView, videoButtonCallback) {

    private var camera: CameraWrap? = null

    private val autoFocusConvergeWaiter = ConvergeWaiter.Factory.createAutoFocusConvergeWaiter()//todo??
    private val autoExposureConvergeWaiter = ConvergeWaiter.Factory.createAutoExposureConvergeWaiter()//todo??

    private lateinit var characteristics: CameraCharacteristics
    private var cameraParams: CameraParams? = null

    override fun onCreate() {
        characteristics = configCamera()
    }

    override fun subscribe() {
        super.subscribe()

        // Открываем камеру после того, как SurfaceTexture готов к использованию.
        val cameraDeviceObservable: Observable<Pair<CameraRxWrapper.DeviceStateEvents, CameraWrap>> = onSurfaceTextureAvailable
                .firstElement()
                .doAfterSuccess { this.setupSurface(it) }
                .doAfterSuccess { initMediaRecorder() }
                .toObservable()
                .flatMap { CameraRxWrapper.openCamera(context, characteristics) }
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

        //  повторяющийся запрос для отображения preview
        val previewObservable = openCameraObservable
                .doOnNext { rxCamera -> cameraParams = getCameraParams(rxCamera.parameters) }
                .doOnNext { rxCamera -> camera = rxCamera }
                .doOnNext { rxCamera -> bindTexture(rxCamera) }
                .doOnNext { rxCamera -> CameraRxWrapper.startPreview(rxCamera) }
                .share()

        // реакция на спуск затвора
        compositeDisposable.add(
                Observable.combineLatest(previewObservable, onShutterClick, BiFunction { camera: CameraWrap, _: Any -> camera })
                        .firstElement().toObservable()
                        .observeOn(Schedulers.io())
                        .flatMap { camera -> CameraRxWrapper.TakePictureRequest(camera, { log("Captured!") }, true, 480, 640, ImageFormat.JPEG, true).get() }
                        .flatMap { cameraData -> ImageSaverRxWrapper.getBitmap(cameraData) }
                        .flatMap { bitmap -> ImageSaverRxWrapper.save(bitmap, file) }
                        .subscribe({ file -> callback.onPhotoTaken(file.absolutePath) }, { this.onError(it) })
        )

        // реакция на изменение камеры
        compositeDisposable.add(
                Observable.combineLatest(previewObservable, onSwitchCameraClick, BiFunction { camera: CameraWrap, _: Any -> camera })
                        .firstElement().toObservable()
                        .flatMap { _ -> closeCameraObservable }
                        .flatMap { camera -> switchCamera(camera) }
                        .subscribe({ _ -> unsubscribe() }, { this.onError(it) })
        )

        // реакция на onPause
        compositeDisposable.add(Observable.combineLatest(previewObservable, onPauseSubject, BiFunction { camera: CameraWrap, _: Any -> camera })
                .firstElement().toObservable()
                .doOnNext { _ -> log("\ton pause") }
                .doOnNext { cam -> cam.onSurfaceDestroy() }
                .doOnNext { _ -> closeCamera() }
                .doOnNext { _ -> closeMediaRecorder() }
                .flatMap { _ -> closeCameraObservable }
                .subscribe({ _ -> unsubscribe() }, { this.onError(it) })
        )

        // реакция на onStartVideo
        compositeDisposable.add(Observable.combineLatest(previewObservable, onStartVideoClick, BiFunction { camera: CameraWrap, _: Any -> camera })
                .firstElement().toObservable()
                .doOnNext { log("\ton start video") }
                .doOnNext { setUpMediaRecorder() }
                .doOnNext {
                    setVideoBtnState(true)
                    mediaRecorder?.start()
                }
                .subscribe({ _ -> unsubscribe() }, { this.onError(it) })
        )

        // реакция на onStopVideo
        compositeDisposable.add(Observable.combineLatest(previewObservable, onStopVideoClick, BiFunction { camera: CameraWrap, _: Any -> camera })
                .firstElement().toObservable()
                .doOnNext { _ -> log("\ton stop video") }
                .doOnNext { _ ->
                    setVideoBtnState(false)
                    stopRecordingVideo()
                    camera!!.nativeCamera.lock()

                    setupSurface(textureView.surfaceTexture)
                }
                .subscribe({ _ -> unsubscribe() }, { this.onError(it) })
        )
    }

    private fun stopRecordingVideo() {
        /*DEBUG*/log("\tstopRecordingVideo")
        if (mediaRecorder != null) {
            setVideoBtnState(false)
            mediaRecorder!!.stop()
            closeMediaRecorder()
        }

        callback.showMessage("Video saved: $videoAbsolutePath")

        videoAbsolutePath = null
    }

    private fun setupVideoSurfaces(): Surface {
        /*DEBUG*/log("\tsetupVideoSurfaces")
        val texture = textureView.surfaceTexture.apply {
            val previewSize = camera!!.parameters.previewSize
            setDefaultBufferSize(previewSize.width, previewSize.height)
        }

        return Surface(texture)
    }

    private fun setUpMediaRecorder() {
        /*DEBUG*/log("\tsetUpMediaRecorder")

        if (videoAbsolutePath.isNullOrEmpty()) {
            videoAbsolutePath = getVideoFilePath()
        }

        createVideoFile()//todo???

        camera!!.nativeCamera.unlock()

        mediaRecorder?.apply {
            setCamera(camera!!.nativeCamera)
            setAudioSource(MediaRecorder.AudioSource.CAMCORDER)//todo??MIC
            setVideoSource(MediaRecorder.VideoSource.CAMERA)//todo??SURFACE
            setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH))
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(videoAbsolutePath)

            /*todo??? val rotation = windowManager.defaultDisplay.rotation
            when (cameraParams!!.sensorOrientation) {
                CameraOrientationHelper.SENSOR_ORIENTATION_DEFAULT_DEGREES ->
                    mediaRecorder!!.setOrientationHint(CameraOrientationHelper.getDefaultOrientation(rotation))
                CameraOrientationHelper.SENSOR_ORIENTATION_INVERSE_DEGREES ->
                    mediaRecorder!!.setOrientationHint(CameraOrientationHelper.getInverseOrientation(rotation))
            }*/

            setPreviewDisplay(setupVideoSurfaces())
            setVideoEncodingBitRate(10000000)
            setVideoFrameRate(30)
            setVideoSize(cameraParams!!.videoSize.width, cameraParams!!.videoSize.height)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            prepare()
        }
    }

    private fun createVideoFile(): File {
        /*DEBUG*/log("\tcreateVideoFile")
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        return File(pictures, "myvideo.3gp")
    }

    private fun closeCamera() {
        /*DEBUG*/log("\tcloseCamera")
        if (camera != null) {
            camera!!.closeCamera()
        }
    }

    @SuppressLint("Recycle")
    private fun setupSurface(surfaceTexture: SurfaceTexture) {
        /*DEBUG*/log("\tsetupSurface")
        if (cameraParams!= null) {//todo camera params
            surfaceTexture.setDefaultBufferSize(cameraParams!!.previewSize.width, cameraParams!!.previewSize.height)
        }
        surface = Surface(surfaceTexture)
    }

    private fun configCamera(): CameraCharacteristics {
        /*DEBUG*/log("\tconfigCamera")

        return CameraCharacteristics.Builder()
                .useBackCamera()
                .setAutoFocus(true)
                .setPreferPreviewFrameRate(15, 30)
                .setPreferPreviewSize(Point(640, 480), false)
                .setHandleSurfaceEvent(true)
                .build()
    }

    private fun getCameraParams(cameraParameters: Camera.Parameters): CameraParams {
        /*DEBUG*/log("\tgetCameraParams")

        val videoSize = CameraStrategy.chooseVideoSize(cameraParameters.supportedVideoSizes)
        val previewSize = CameraStrategy.chooseOptimalSize(
                cameraParameters.supportedPreviewSizes,
                textureView.width,
                textureView.height,
                videoSize
        )

        return CameraParams(characteristics, previewSize, videoSize)
    }

    private fun bindTexture(rxCamera: CameraWrap): Observable<CameraWrap> {
        /*DEBUG*/log("\tbindTexture")

        rxCamera.onSurfaceAvailable()

        return Observable.create { emitter ->
            try {
                rxCamera.bindTextureInternal(textureView)
                emitter.onNext(rxCamera)
                emitter.onComplete()
            } catch (e: Exception) {
                emitter.onError(e)
            }
        }
    }

    private fun onError(throwable: Throwable) {
        /*DEBUG*/log("\tonError"+throwable.message)
        unsubscribe()
        when (throwable) {
            //todo ??? is CameraAccessException -> callback.showCameraAccessException()
            is OpenCameraException -> onCameraOpenException(throwable)
            else -> onException(throwable)
        }
    }

    private fun onException(throwable: Throwable) {
        log("Camera Exception: " + throwable.message)

        callback.showError("Ошибка приложения. ${throwable.message}")
    }

    private fun onCameraOpenException(exception: OpenCameraException) {
        log("Camera Open Exception: reason ${exception.reason}:" + exception.message)

        callback.showError("Ошибка при открытии камеры.")
    }

    /**
     * successive camera preview frame data
     */
    private fun requestSuccessiveData(camera: CameraWrap): Disposable? {
        return CameraRxWrapper.SuccessiveDataRequest(camera).get().subscribe { rxCameraData -> log("successiveData, cameraData.length: " + rxCameraData.cameraData!!.size) }
    }
    /**
     * only one shot camera data, encapsulated the setOneShotPreviewCallback
     */
    private fun requestOneShot(camera: CameraWrap): Disposable? {
        return CameraRxWrapper.TakeOneShotRequest(camera).get().subscribe { rxCameraData -> log("one shot request, cameraData.length: " + rxCameraData.cameraData!!.size) }
    }
    /**
     * periodic camera preview frame data
     * intervalMills the interval of the preview frame data will return, in millseconds
     */
    private fun requestPeriodicData(camera: CameraWrap): Disposable? {
        return CameraRxWrapper.PeriodicDataRequest(camera, 1000).get().subscribe { rxCameraData -> log("periodic request, cameraData.length: " + rxCameraData.cameraData!!.size) }
    }

    private fun actionZoom(camera: CameraWrap): Disposable? {
        return execute (camera) { cam -> cam.zoom(10)}
                .subscribe({ rxCamera -> log("zoom success: $rxCamera") }, { e -> log("zoom error: " + e.message) })
    }

    private fun actionSmoothZoom(camera: CameraWrap): Disposable? {
        return execute (camera) { cam -> cam.smoothZoom(10)}
                .subscribe({ rxCamera -> log("zoom success: $rxCamera") }, { e -> log("zoom error: " + e.message) })
    }

    private fun actionOpenFlash(camera: CameraWrap): Disposable? {
        return execute (camera) { cam -> cam.flashAction(true)}
                .subscribe({ rxCamera -> log("open flash: $rxCamera") }, { e -> log("open flash error: " + e.message) })
    }

    private fun actionCloseFlash(camera: CameraWrap): Disposable? {
        return execute (camera) { cam -> cam.flashAction(false)}
                .subscribe({ rxCamera -> log("close flash: $rxCamera") }, { e -> log("close flash error: " + e.message) })
    }

    private fun execute(camera: CameraWrap, action: (CameraWrap) -> Unit): Observable<CameraWrap> {
        return Observable.create<CameraWrap> { emitter ->
            try {
                    action.invoke(camera)
                    emitter.onNext(camera)
            } catch (e: Exception) {
                emitter.onError(e)
            }
        }
    }

    private fun switchCamera(camera: CameraWrap): Observable<CameraWrap> {
        /*DEBUG*/log("\tswitchCamera")
        return Observable.create { emitter ->

            try {
                camera.release()

                var nativeCamera = camera
                val characteristics = camera.switchCameraCharacteristics()
                val oldTextureView = camera.getTextureView()

                if (oldTextureView != null) {
                    nativeCamera = CameraWrap(context, characteristics)
                    nativeCamera.bindTextureInternal(oldTextureView)
                }

                nativeCamera.startPreviewInternal()

                log("\tswitchCamera - success")
                if (!emitter.isDisposed) {
                    emitter.onNext(nativeCamera)
                    emitter.onComplete()
                }

            } catch (e: Exception) {
                log("\tswitchCamera - onError")

                if (!emitter.isDisposed) {
                    emitter.onError(OpenCameraFailedException(Exception(), OpenCameraFailedException.Reason.OPEN_FAILED, ""))//todo???
                }
            }
        }
    }

    //--------------------------------------------------------------------------------------------------
    private inner class CameraParams internal constructor(val cameraCharacteristics: CameraCharacteristics,
                                                          val previewSize: android.hardware.Camera.Size,
                                                          val videoSize: android.hardware.Camera.Size)
    //--------------------------------------------------------------------------------------------------
}
