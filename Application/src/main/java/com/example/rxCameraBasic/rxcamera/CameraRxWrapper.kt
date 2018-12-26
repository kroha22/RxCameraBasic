@file:Suppress("DEPRECATION")

package com.example.rxCameraBasic.rxcamera

import android.content.Context
import android.graphics.Matrix
import android.hardware.Camera
import android.util.Log
import android.util.Pair
import com.example.rxCameraBasic.rxcamera.CameraWrap.CameraData
import com.example.rxCameraBasic.rxcamera.CameraWrap.OnCameraPreviewFrameCallback
import io.reactivex.Observable
import io.reactivex.ObservableEmitter
import io.reactivex.ObservableOnSubscribe
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import java.util.concurrent.TimeUnit

object CameraRxWrapper {
    //-----------------------------------------------------------------------------------------------

    private val TAG = CameraRxWrapper::class.java.name

    private fun log(msg: String) {
        Log.d(TAG, msg)
    }

    //-----------------------------------------------------------------------------------------------
    enum class DeviceStateEvents {
        ON_OPENED,
        ON_CLOSED,
        ON_DISCONNECTED
    }

    //-----------------------------------------------------------------------------------------------
    fun openCamera(context: Context, characteristics: CameraCharacteristics): Observable<Pair<DeviceStateEvents, CameraWrap>> {
        return Observable.create { emitter ->
            log("\topenCamera")

            try {
                val rxCamera = CameraWrap(context, characteristics)

                log("\topenCamera - onOpened")
                if (!emitter.isDisposed) {
                    emitter.onNext(Pair(DeviceStateEvents.ON_OPENED, rxCamera))
                    emitter.onComplete()
                }

            } catch (e: Exception) {
                log("\topenCamera - onError")

                if (!emitter.isDisposed) {
                    emitter.onError(OpenCameraFailedException(Exception(), OpenCameraFailedException.Reason.OPEN_FAILED, ""))//todo???
                }
            }
        }
    }

    fun startPreview(camera: CameraWrap): Observable<CameraWrap> {
        return Observable.create<CameraWrap> { emitter ->
            try {
                camera.startPreviewInternal()
                emitter.onNext(camera)
                emitter.onComplete()
            } catch (e: Exception) {
                emitter.onError(e)
            }
        }
    }

    //-------------------------------------------------------------------------------------------------
    abstract class BaseRxCameraRequest(protected var rxCamera: CameraWrap) {
        abstract fun get(): Observable<CameraData>
    }

    //-------------------------------------------------------------------------------------------------
    class PeriodicDataRequest(rxCamera: CameraWrap, private val intervalMills: Long) : BaseRxCameraRequest(rxCamera), OnCameraPreviewFrameCallback {

        private var isInstallCallback = false
        private var emitter: ObservableEmitter<in CameraData>? = null
        private val currentData = CameraData()

        override fun get(): Observable<CameraData> {
            return Observable
                    .create(ObservableOnSubscribe<CameraData> { emitter ->
                        this@PeriodicDataRequest.emitter = emitter
                        emitter.setDisposable(Schedulers.newThread().createWorker().schedulePeriodically({
                            if (currentData.cameraData != null && !emitter.isDisposed) {
                                emitter.onNext(currentData)
                            }
                        }, 0, intervalMills, TimeUnit.MILLISECONDS))
                    })
                    .doOnSubscribe {
                        if (!isInstallCallback) {
                            rxCamera.installPreviewCallback(this@PeriodicDataRequest)
                            isInstallCallback = true
                        }
                    }
                    .doOnDispose {
                        rxCamera.uninstallPreviewCallback(this@PeriodicDataRequest)
                        isInstallCallback = false
                    }
                    .doOnTerminate {
                        rxCamera.uninstallPreviewCallback(this@PeriodicDataRequest)
                        isInstallCallback = false
                    }
                    .observeOn(AndroidSchedulers.mainThread())
        }

        override fun onPreviewFrame(data: ByteArray?) {
            if (emitter != null && !emitter!!.isDisposed) {
                if (data == null || data.isEmpty()) {
                    emitter!!.onError(CameraDataNullException())
                }
                currentData.cameraData = data
                currentData.rotateMatrix = rxCamera.rotateMatrix
            }
        }
    }

    //-------------------------------------------------------------------------------------------------
    abstract class DataRequest(rxCamera: CameraWrap) : BaseRxCameraRequest(rxCamera), OnCameraPreviewFrameCallback {

        private var emitter: ObservableEmitter<in CameraData>? = null

        override fun get(): Observable<CameraData> {
            return Observable.create { emitter -> this.emitter = emitter }
        }

        override fun onPreviewFrame(data: ByteArray?) {
            if (emitter != null && !emitter!!.isDisposed) {
                if (data == null || data.isEmpty()) {
                    emitter!!.onError(CameraDataNullException())
                }
                val cameraData = getCameraData(data, rxCamera.rotateMatrix)
                emitter!!.onNext(cameraData)
            }
        }
    }

    //-------------------------------------------------------------------------------------------------
    class SuccessiveDataRequest(rxCamera: CameraWrap) : DataRequest(rxCamera) {

        private var isInstallSuccessivePreviewCallback = false

        override fun get(): Observable<CameraData> {

            return super.get()
                    .doOnSubscribe {
                        if (!isInstallSuccessivePreviewCallback) {
                            rxCamera.installPreviewCallback(this@SuccessiveDataRequest)
                            isInstallSuccessivePreviewCallback = true
                        }
                    }
                    .doOnDispose {
                        rxCamera.uninstallPreviewCallback(this@SuccessiveDataRequest)
                        isInstallSuccessivePreviewCallback = false
                    }
                    .doOnTerminate {
                        rxCamera.uninstallPreviewCallback(this@SuccessiveDataRequest)
                        isInstallSuccessivePreviewCallback = false

                    }
        }
    }

    //-------------------------------------------------------------------------------------------------
    class TakeOneShotRequest(rxCamera: CameraWrap) : DataRequest(rxCamera) {

        override fun get(): Observable<CameraData> {
            return super.get()
                    .doOnSubscribe { rxCamera.installOneShotPreviewCallback(this@TakeOneShotRequest) }
        }
    }

    //-------------------------------------------------------------------------------------------------
    class TakePictureRequest(rxCamera: CameraWrap,
                             private val shutterAction: () -> Unit?,
                             private val isContinuePreview: Boolean,
                             private val pictureWidth: Int,
                             private val pictureHeight: Int,
                             private val pictureFormat: Int,
                             private val openFlash: Boolean) : BaseRxCameraRequest(rxCamera) {

        override fun get(): Observable<CameraData> {
            return Observable.create { emitter ->

                try {
                    setCameraParameters()
                } catch (e: Exception) {
                    emitter.onError(e)
                }

                rxCamera.takePicture(shutterAction) { data ->
                    if (isContinuePreview) { //todo????
                        startPreview(rxCamera).doOnError { emitter.onError(it) }.subscribe()
                    }
                    if (data != null) {
                        val cameraData = getCameraData(data, rxCamera.rotateMatrix)
                        emitter.onNext(cameraData)

                        // should close flash
                        if (openFlash) {
                            CameraWrap.CameraParametersBuilder.forCamera(rxCamera).setFlashMode(Camera.Parameters.FLASH_MODE_OFF).applyParameters(rxCamera)
                        }

                    } else {
                        emitter.onError(TakePictureFailedException("cannot get take picture data"))
                    }
                }
            }
        }

        private fun setCameraParameters() {
            val paramBuilder =
                    CameraWrap.CameraParametersBuilder.forCamera(rxCamera)
                            .setPictureFormat(pictureFormat)
                            .setPictureSize(pictureWidth, pictureHeight)

            if (openFlash) {
                paramBuilder.setFlashMode(Camera.Parameters.FLASH_MODE_ON)
            }

            paramBuilder.applyParameters(rxCamera)
        }
    }
//-------------------------------------------------------------------------------------------------

    private fun getCameraData(cameraData: ByteArray?, rotateMatrix: Matrix?): CameraData {
        val rxCameraData = CameraData()
        rxCameraData.cameraData = cameraData
        rxCameraData.rotateMatrix = rotateMatrix
        return rxCameraData
    }
//-------------------------------------------------------------------------------------------------
}
