package com.example.RxCameraBasic.rxcamera2

import android.annotation.TargetApi
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import io.reactivex.Observable
import java.util.*

/*
Принимает решения размеров, используемых в конфигурации камеры, и выбирает камеру.
 */
@TargetApi(21)
internal object CameraStrategy {

    private val TAG = CameraStrategy::class.java.simpleName
    private const val MAX_PREVIEW_WIDTH = 1920
    private const val MAX_PREVIEW_HEIGHT = 1920
    private const val MAX_STILL_IMAGE_WIDTH = 1920
    private const val MAX_STILL_IMAGE_HEIGHT = 1920

    @Throws(CameraAccessException::class)
    fun chooseDefaultCamera(manager: CameraManager): String? {
        return getCameraWithFacing(manager, CameraCharacteristics.LENS_FACING_BACK)
    }

    @Throws(CameraAccessException::class)
    fun switchCamera(manager: CameraManager, currentCameraId: String?): String? {
        if (currentCameraId != null) {
            val currentFacing = manager.getCameraCharacteristics(currentCameraId).get(CameraCharacteristics.LENS_FACING)
            if (currentFacing != null) {

                val lensFacing: Int = if (currentFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                    CameraCharacteristics.LENS_FACING_BACK
                } else {
                    CameraCharacteristics.LENS_FACING_FRONT
                }
                return getCameraWithFacing(manager, lensFacing)
            }
        }
        return chooseDefaultCamera(manager)
    }

    /*
     Функция выбора камеры с предпочтением по ориентации.
     Возвращает id камеры нужной ориентации или любой другой, если нужной не нашлось.
     */
    @Throws(CameraAccessException::class)
    private fun getCameraWithFacing(manager: CameraManager, lensFacing: Int): String? {
        var possibleCandidate: String? = null
        val cameraIdList = manager.cameraIdList
        if (cameraIdList.isEmpty()) {
            return null
        }
        for (cameraId in cameraIdList) {
            val characteristics = manager.getCameraCharacteristics(cameraId) //список характеристик для камеры cameraId

            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?: continue

            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing != null && facing == lensFacing) {
                return cameraId
            }

            //на случай если у устройства нет нужной камеры
            possibleCandidate = cameraId
        }
        return possibleCandidate ?: cameraIdList[0]
    }
    /*
     Получить размер превью исходя из характеристик камеры.
     */
    fun getPreviewSize(characteristics: CameraCharacteristics): Size {
        // Получения списка выходного формата, который поддерживает камера
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        val outputSizes: Array<Size> = map!!.getOutputSizes(SurfaceTexture::class.java)

        if (outputSizes.isEmpty()) {
            throw IllegalStateException("No supported sizes for SurfaceTexture")
        }

        val filteredOutputSizes = Observable.fromIterable(outputSizes.asIterable())
                .filter{ size -> size.width <= MAX_PREVIEW_WIDTH && size.height <= MAX_PREVIEW_HEIGHT }
                .toList()
                .blockingGet()

        return if (filteredOutputSizes.size == 0) {
            outputSizes[0]
        } else Collections.max(filteredOutputSizes, CompareSizesByArea())

    }

    /**
     * In this sample, we choose a video size with 3x4 aspect ratio. Also, we don't use sizes
     * larger than 1080p, since MediaRecorder cannot handle such a high-resolution video.
     *
     * @param choices The list of available sizes
     * @return The video size
     */
    internal fun chooseVideoSize(choices: Array<Size>) = choices.firstOrNull {
        it.width == it.height * 4 / 3 && it.width <= 1080
    } ?: choices[choices.size - 1]

    /**
     * Given [choices] of [Size]s supported by a camera, chooses the smallest one whose
     * width and height are at least as large as the respective requested values, and whose aspect
     * ratio matches with the specified value.
     *
     * @param choices     The list of sizes that the camera supports for the intended output class
     * @param width       The minimum desired width
     * @param height      The minimum desired height
     * @param aspectRatio The aspect ratio
     * @return The optimal [Size], or an arbitrary one if none were big enough
     */
    internal fun chooseOptimalSize(
            choices: Array<Size>,
            width: Int,
            height: Int,
            aspectRatio: Size
    ): Size {

        // Collect the supported resolutions that are at least as big as the preview Surface
        val w = aspectRatio.width
        val h = aspectRatio.height
        val bigEnough = choices.filter {
            it.height == it.width * h / w && it.width >= width && it.height >= height
        }

        // Pick the smallest of those, assuming we found any
        return if (bigEnough.isNotEmpty()) {
            Collections.min(bigEnough, CameraStrategy.CompareSizesByArea())
        } else {
            choices[0]
        }
    }

    /*
    Обратите внимание, что соотношение сторон должно быть одинаковым для [.getPreviewSize] и [.getStillImageSize]
     */
    fun getStillImageSize(characteristics: CameraCharacteristics, previewSize: Size): Size {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val outputSizes = map!!.getOutputSizes(ImageFormat.JPEG)
        if (outputSizes.isEmpty()) {
            throw IllegalStateException("No supported sizes for JPEG")
        }
        val filteredOutputSizes = Observable.fromIterable(outputSizes.asIterable())
                .filter { size -> size.width == size.height * previewSize.width / previewSize.height }
                .filter { size -> size.width <= MAX_STILL_IMAGE_WIDTH && size.height <= MAX_STILL_IMAGE_HEIGHT }
                .toList()
                .blockingGet()

        return if (filteredOutputSizes.size == 0) {
            outputSizes[0]
        } else Collections.max(filteredOutputSizes, CompareSizesByArea())

    }
    /*
    Сравнивает два Size на основе площади
     */
    public class CompareSizesByArea : Comparator<Size> {

        override fun compare(lhs: Size, rhs: Size): Int {
            // We cast here to ensure the multiplications won't overflow
            return java.lang.Long.signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
        }

    }
}
