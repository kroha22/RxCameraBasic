package com.example.RxCameraBasic.rxcamera2

import android.annotation.TargetApi
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import com.example.RxCameraBasic.rxcamera2.CameraRxWrapper.CaptureSessionData

import java.util.Arrays
import java.util.Collections
import java.util.concurrent.TimeUnit

import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers

@TargetApi(21)
internal class ConvergeWaiter private constructor(
        //ключ и значение флажка, который запустит необходимый процесс схождения при вызове capture
        private val mRequestTriggerKey: CaptureRequest.Key<Int>,
        private val mRequestTriggerStartValue: Int,

        //ключ и набор ожидаемых значений флага из результата onCaptureCompleted
        private val mResultStateKey: CaptureResult.Key<Int>,
        private val mResultReadyStates: List<Int>
) {

    //------------------------------------------------------------------------------------------------

    companion object {

        private const val TIMEOUT_SECONDS: Long = 3
    }
    //------------------------------------------------------------------------------------------------

    fun waitForConverge(captureResultParams: CaptureSessionData, builder: CaptureRequest.Builder): Single<CaptureSessionData> {
        val previewRequest = builder.build()

        builder.set(mRequestTriggerKey, mRequestTriggerStartValue)
        val triggerRequest = builder.build()

        val triggerObservable = CameraRxWrapper.fromCapture(captureResultParams.session, triggerRequest)
        val previewObservable = CameraRxWrapper.fromSetRepeatingRequest(captureResultParams.session, previewRequest)
        val convergeSingle = Observable
                .merge(previewObservable, triggerObservable)
                .filter { resultParams -> isStateReady(resultParams.result) }
                .first(captureResultParams)

        // таймаут на случай если процесс схождения затягивается слишком долго или что-то пошло не так
        val timeOutSingle = Single
                .just(captureResultParams)
                .delay(TIMEOUT_SECONDS, TimeUnit.SECONDS, AndroidSchedulers.mainThread())

        return Single
                .merge(convergeSingle, timeOutSingle)
                .firstElement()
                .toSingle()
    }
    /*
     Дожидаемся момента, когда CaptureResult, переданный в этот метод, будет содержать ожидаемое значение флага.
     Для этого создадим функцию, которая принимает CaptureResult и возвращает true если в нём есть ожидаемое значение флага.
     */
    private fun isStateReady(result: CaptureResult): Boolean {
        val aeState = result.get(mResultStateKey)
        //Проверка на null нужна для кривых реализаций Camera2 API, чтобы не зависнуть в ожидании навеки.
        return aeState == null || mResultReadyStates.contains(aeState)
    }

    internal object Factory {
        //Для автофокуса значение ключа CaptureResult.CONTROL_AF_STATE, список значений:
        private val afReadyStates = Collections.unmodifiableList(
                Arrays.asList(
                        CaptureResult.CONTROL_AF_STATE_INACTIVE,
                        CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED,
                        CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
                        CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                )
        )

        //для автоэкспозиции значение ключа CaptureResult.CONTROL_AE_STATE, список значений:
        private val aeReadyStates = Collections.unmodifiableList(
                Arrays.asList(
                        CaptureResult.CONTROL_AE_STATE_INACTIVE,
                        CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED,
                        CaptureResult.CONTROL_AE_STATE_CONVERGED,
                        CaptureResult.CONTROL_AE_STATE_LOCKED
                )
        )

        fun createAutoFocusConvergeWaiter(): ConvergeWaiter {
            return ConvergeWaiter(
                    CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START,
                    CaptureResult.CONTROL_AF_STATE,
                    afReadyStates
            )
        }

        fun createAutoExposureConvergeWaiter(): ConvergeWaiter {
            return ConvergeWaiter(
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START,
                    CaptureResult.CONTROL_AE_STATE,
                    aeReadyStates
            )
        }
    }

}
