package com.example.RxCameraBasic.rxcamera2

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.hardware.camera2.*
import android.util.Pair
import android.view.Surface
import com.example.RxCameraBasic.MainActivity
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
        MainActivity.log("$TAG: $msg")
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

            log("openCamera")

            camEmitter.setCancellable { log("openCamera - unsubscribed") }

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {

                override fun onOpened(camera: CameraDevice) {
                    log("camera ${camera.id}: openCamera - onOpened")

                    if (!camEmitter.isDisposed) {
                        camEmitter.onNext(Pair(DeviceStateEvents.ON_OPENED, camera))
                    }
                }

                override fun onClosed(camera: CameraDevice) {
                    log("camera ${camera.id}: openCamera - onClosed")

                    if (!camEmitter.isDisposed) {
                        camEmitter.onNext(Pair(DeviceStateEvents.ON_CLOSED, camera))
                        camEmitter.onComplete()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    log("camera ${camera.id}: openCamera - onDisconnected")

                    if (!camEmitter.isDisposed) {
                        camEmitter.onNext(Pair(DeviceStateEvents.ON_DISCONNECTED, camera))
                        camEmitter.onComplete()
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    log("camera ${camera.id}: openCamera - onError")

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
     Создание сессии для указанного устройства camera.
     surfaceList - список Surface, которые будут использованы для записи данных с устройства.
     */
    fun createCaptureSession(camera: CameraDevice, surfaceList: List<Surface>): Observable<Pair<CaptureSessionStateEvents, CameraCaptureSession>> {
        return Observable.create { sesEmitter ->

            log("camera ${camera.id}: createCaptureSession")

            sesEmitter.setCancellable { log("camera ${camera.id}: createCaptureSession - unsubscribed") }

            camera.createCaptureSession(surfaceList, object : CameraCaptureSession.StateCallback() {

                override fun onConfigured(session: CameraCaptureSession) {
                    log("camera ${session.device.id}: createCaptureSession - onConfigured")

                    if (!sesEmitter.isDisposed) {
                        sesEmitter.onNext(Pair(CaptureSessionStateEvents.ON_CONFIGURED, session))
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    log("camera ${session.device.id}: createCaptureSession - onConfigureFailed")

                    if (!sesEmitter.isDisposed) {
                        sesEmitter.onError(CreateCaptureSessionException(session))
                    }
                }

                override fun onReady(session: CameraCaptureSession) {
                    log("camera ${session.device.id}: createCaptureSession - onReady")

                    if (!sesEmitter.isDisposed) {
                        sesEmitter.onNext(Pair(CaptureSessionStateEvents.ON_READY, session))
                    }
                }

                override fun onActive(session: CameraCaptureSession) {
                    log("camera ${session.device.id}: createCaptureSession - onActive")

                    if (!sesEmitter.isDisposed) {
                        sesEmitter.onNext(Pair(CaptureSessionStateEvents.ON_ACTIVE, session))
                    }
                }

                override fun onClosed(session: CameraCaptureSession) {
                    log("camera ${session.device.id}: createCaptureSession - onClosed")

                    if (!sesEmitter.isDisposed) {
                        sesEmitter.onNext(Pair(CaptureSessionStateEvents.ON_CLOSED, session))
                        sesEmitter.onComplete()
                    }
                }

                override fun onSurfacePrepared(session: CameraCaptureSession, surface: Surface) {
                    log("camera ${session.device.id}: createCaptureSession - onSurfacePrepared")

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
                log("onCaptureFailed failure $failure")

                if (!sesDataEmitter.isDisposed) {//todo handle error
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
