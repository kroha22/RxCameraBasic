package com.example.RxCameraBasic.rxcamera2

import android.annotation.TargetApi
import android.hardware.camera2.CameraCharacteristics
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.WindowManager

@TargetApi(21)
internal object CameraOrientationHelper {

    private val JPEG_ORIENTATIONS = SparseIntArray()
    private val DEFAULT_ORIENTATIONS = SparseIntArray()
    private val INVERSE_ORIENTATIONS = SparseIntArray()

    const val SENSOR_ORIENTATION_DEFAULT_DEGREES = 90
    const val SENSOR_ORIENTATION_INVERSE_DEGREES = 270

    init {
        JPEG_ORIENTATIONS.append(Surface.ROTATION_0, 90)
        JPEG_ORIENTATIONS.append(Surface.ROTATION_90, 0)
        JPEG_ORIENTATIONS.append(Surface.ROTATION_180, 270)
        JPEG_ORIENTATIONS.append(Surface.ROTATION_270, 180)

        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_0, 90)
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_90, 0)
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_180, 270)
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_270, 180)

        INVERSE_ORIENTATIONS.append(Surface.ROTATION_0, 270)
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_90, 180)
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_180, 90)
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_270, 0)
    }
    /*
     Извлекает ориентацию JPEG с указанного вращения экрана.
     */
    fun getJpegOrientation(characteristics: CameraCharacteristics, screenRotation: Int): Int {
        val sensorOrientation = getSensorOrientation(characteristics)

        /*
        Ориентация датчика составляет 90 для большинства устройств или 270 для некоторых устройств (например, Nexus 5X).
        Мы должны принять это во внимание и правильно повернуть JPEG.
        Для устройств с ориентацией 90 мы просто возвращаем наше отображение из ORIENTATIONS.
        Для устройств с ориентацией 270 мы должны поворачивать JPEG на 180 градусов.
         */
        return (JPEG_ORIENTATIONS.get(screenRotation) + sensorOrientation + 270) % 360
    }
    /*
    Возвращает градусы от 0, 90, 180, 270
     */
    private fun getSensorOrientation(characteristics: CameraCharacteristics): Int {
        return characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: return 0
    }

    fun getDefaultOrientation(screenRotation: Int): Int {
        return DEFAULT_ORIENTATIONS.get(screenRotation)
    }

    fun getInverseOrientation(screenRotation: Int): Int {
        return INVERSE_ORIENTATIONS.get(screenRotation)
    }
    /*
    Преобразует значения, указанные в [Display.getRotation], в градусы
     */
    fun rotationInDegrees(windowManager: WindowManager): Int {
        when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_0 -> return 0
            Surface.ROTATION_90 -> return 90
            Surface.ROTATION_180 -> return 180
            Surface.ROTATION_270 -> return 270
        }
        return 0
    }
    /*
    Датчик может быть повернут в устройстве, этот метод возвращает нормальный размер датчика ориентации
     */
    fun getSensorSizeRotated(characteristics: CameraCharacteristics, sensorSize: Size): Size {
        val sensorOrientationDegrees = CameraOrientationHelper.getSensorOrientation(characteristics)

        return if (sensorOrientationDegrees % 180 == 0) {
            sensorSize
        } else Size(sensorSize.height, sensorSize.width)

        // swap dimensions
    }

}
