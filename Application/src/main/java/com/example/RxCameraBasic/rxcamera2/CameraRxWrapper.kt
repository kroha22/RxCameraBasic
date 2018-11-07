package com.example.RxCameraBasic.rxcamera2

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import android.util.Pair
import android.view.Surface

import io.reactivex.Observable
import io.reactivex.ObservableEmitter

/**
 * Helper class, creates Observables from camera async methods.
 */
@TargetApi(21)
object CameraRxWrapper {
    //-----------------------------------------------------------------------------------------------

    private val TAG = CameraRxWrapper::class.java.name

    private fun log(msg: String) {
        Log.d(TAG, msg)
    }
    //-----------------------------------------------------------------------------------------------
    /**
     * @see CameraDevice.StateCallback
     */
    enum class DeviceStateEvents {
        ON_OPENED,
        ON_CLOSED,
        ON_DISCONNECTED
    }
    //-----------------------------------------------------------------------------------------------
    /*
     Открытие устройства с указанным cameraId.
     */
    @SuppressLint("MissingPermission")
    fun openCamera(cameraId: String,
                   cameraManager: CameraManager): Observable<Pair<DeviceStateEvents, CameraDevice>> {

        return Observable.create { camEmitter ->

            log("\topenCamera")

            camEmitter.setCancellable { log("\topenCamera - unsubscribed") }

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(cameraDevice: CameraDevice) {
                    log("\topenCamera - onOpened")

                    if (!camEmitter.isDisposed) {
                        camEmitter.onNext(Pair(DeviceStateEvents.ON_OPENED, cameraDevice))
                    }
                }

                override fun onClosed(cameraDevice: CameraDevice) {
                    log("\topenCamera - onClosed")

                    if (!camEmitter.isDisposed) {
                        camEmitter.onNext(Pair(DeviceStateEvents.ON_CLOSED, cameraDevice))
                        camEmitter.onComplete()
                    }
                }

                override fun onDisconnected(cameraDevice: CameraDevice) {
                    if (!camEmitter.isDisposed) {
                        camEmitter.onNext(Pair(DeviceStateEvents.ON_DISCONNECTED, cameraDevice))
                        camEmitter.onComplete()
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    log("\topenCamera - onError")

                    if (!camEmitter.isDisposed) {
                        camEmitter.onError(OpenCameraException(OpenCameraException.Reason.getReason(error)))
                    }
                }
            }, null)
        }
    }
    //-----------------------------------------------------------------------------------------------
    /**
     * @see CameraCaptureSession.StateCallback
     */
    enum class CaptureSessionStateEvents {
        ON_CONFIGURED,
        ON_READY,
        ON_ACTIVE,
        ON_CLOSED,
        ON_SURFACE_PREPARED
    }
    //-----------------------------------------------------------------------------------------------
    /*
     Создание сессии для указанного устройства cameraDevice.
     surfaceList - список Surface, которые будут использованы для записи данных с устройства.
     */
    fun createCaptureSession(cameraDevice: CameraDevice, surfaceList: List<Surface>): Observable<Pair<CaptureSessionStateEvents, CameraCaptureSession>> {
        return Observable.create { sesEmitter ->

            log("\tcreateCaptureSession")

            sesEmitter.setCancellable { log("\tcreateCaptureSession - unsubscribed") }

            cameraDevice.createCaptureSession(surfaceList, object : CameraCaptureSession.StateCallback() {

                override fun onConfigured(session: CameraCaptureSession) {
                    log("\tcreateCaptureSession - onConfigured")

                    if (!sesEmitter.isDisposed) {
                        sesEmitter.onNext(Pair(CaptureSessionStateEvents.ON_CONFIGURED, session))
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    log("\tcreateCaptureSession - onConfigureFailed")

                    if (!sesEmitter.isDisposed) {
                        sesEmitter.onError(CreateCaptureSessionException(session))
                    }
                }

                override fun onReady(session: CameraCaptureSession) {
                    log("\tcreateCaptureSession - onReady")

                    if (!sesEmitter.isDisposed) {
                        sesEmitter.onNext(Pair(CaptureSessionStateEvents.ON_READY, session))
                    }
                }

                override fun onActive(session: CameraCaptureSession) {
                    log("\tcreateCaptureSession - onActive")

                    if (!sesEmitter.isDisposed) {
                        sesEmitter.onNext(Pair(CaptureSessionStateEvents.ON_ACTIVE, session))
                    }
                }

                override fun onClosed(session: CameraCaptureSession) {
                    log("\tcreateCaptureSession - onClosed")

                    if (!sesEmitter.isDisposed) {
                        sesEmitter.onNext(Pair(CaptureSessionStateEvents.ON_CLOSED, session))
                        sesEmitter.onComplete()
                    }
                }

                override fun onSurfacePrepared(session: CameraCaptureSession, surface: Surface) {
                    log("\tcreateCaptureSession - onSurfacePrepared")

                    if (!sesEmitter.isDisposed) {
                        sesEmitter.onNext(Pair(CaptureSessionStateEvents.ON_SURFACE_PREPARED, session))
                    }
                }
            }, null)
        }
    }
    //-----------------------------------------------------------------------------------------------

    /**
     * @see CameraCaptureSession.CaptureCallback
     */
    enum class CaptureSessionEvents {
        ON_STARTED,
        ON_PROGRESSED,
        ON_COMPLETED,
        ON_SEQUENCE_COMPLETED,
        ON_SEQUENCE_ABORTED
    }
    //-----------------------------------------------------------------------------------------------
    /*
     Класс обертка для сессии CameraCaptureSession.
     */
    class CaptureSessionData internal constructor(internal val event: CaptureSessionEvents,
                                                  internal var session: CameraCaptureSession,
                                                  internal val request: CaptureRequest,
                                                  internal val result: CaptureResult){

        fun setSession(session: CameraCaptureSession): Observable<CaptureSessionData>{
            return Observable.create { this.session = session }
        }

        fun setRepeatingRequest(request: CaptureRequest): Observable<CaptureSessionData>{
            return fromSetRepeatingRequest(session, request)
        }
    }
    //-----------------------------------------------------------------------------------------------

    /*
      Для того чтобы на экране появилась живая картинка с камеры, необходимо постоянно получать новые изображения с устройства и передавать их для отображения.
      Warning, emits a lot!
     */
    internal fun fromSetRepeatingRequest(captureSes: CameraCaptureSession,
                                         request: CaptureRequest): Observable<CaptureSessionData> {
        return Observable
                .create { observableEmitter ->
                    captureSes.setRepeatingRequest(request, createCaptureCallback(observableEmitter), null)
                }
    }

    internal fun fromCapture(captureSes: CameraCaptureSession,
                             request: CaptureRequest): Observable<CaptureSessionData> {
        return Observable
                .create { observableEmitter ->
                    captureSes.capture(request, createCaptureCallback(observableEmitter), null)
                }
    }

    private fun createCaptureCallback(sesDataEmitter: ObservableEmitter<CaptureSessionData>): CameraCaptureSession.CaptureCallback {
        return object : CameraCaptureSession.CaptureCallback() {

            override fun onCaptureStarted(session: CameraCaptureSession,
                                          request: CaptureRequest,
                                          timestamp: Long,
                                          frameNumber: Long) {
            }

            override fun onCaptureProgressed(session: CameraCaptureSession,
                                             request: CaptureRequest,
                                             partialResult: CaptureResult) {
            }

            override fun onCaptureCompleted(session: CameraCaptureSession,
                                            request: CaptureRequest,
                                            result: TotalCaptureResult) {
                if (!sesDataEmitter.isDisposed) {
                    sesDataEmitter.onNext(CaptureSessionData(CaptureSessionEvents.ON_COMPLETED, session, request, result))
                }
            }

            override fun onCaptureFailed(session: CameraCaptureSession,
                                         request: CaptureRequest,
                                         failure: CaptureFailure) {
                if (!sesDataEmitter.isDisposed) {
                    sesDataEmitter.onError(CameraCaptureFailedException(failure))
                }
            }

            override fun onCaptureSequenceCompleted(session: CameraCaptureSession,
                                                    sequenceId: Int,
                                                    frameNumber: Long) {
            }

            override fun onCaptureSequenceAborted(session: CameraCaptureSession,
                                                  sequenceId: Int) {
            }
        }
    }
    //-----------------------------------------------------------------------------------------------

    class CreateCaptureSessionException(val session: CameraCaptureSession) : Exception()
    //-----------------------------------------------------------------------------------------------

    class CameraCaptureFailedException(val mFailure: CaptureFailure) : Exception()
    //-----------------------------------------------------------------------------------------------

}
