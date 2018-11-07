@file:Suppress("DEPRECATION")

package com.example.RxCameraBasic.rxcamera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.Camera
import android.os.Build
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import com.example.RxCameraBasic.rxcamera.CameraCharacteristics.CameraFacing
import com.example.RxCameraBasic.rxcamera.CameraCharacteristics.CameraId
import java.util.*

object CameraStrategy {

    private const val TAG = "Camera1Strategy"

    private var frontCameraId = -1
    private var backCameraId = -1
    private var cameraNumber = -1

    fun chooseDefaultCamera(): CameraId {
        return getCameraWithFacing(CameraFacing.CAMERA_FACING_BACK)
    }

    fun switchCamera(currentCameraId: CameraId): CameraId {
        if (currentCameraId.isExist()) {
            val cameraInfo = Camera.CameraInfo()
            Camera.getCameraInfo(currentCameraId.idInt, cameraInfo)
            if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {

                val lensFacing = if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    CameraCharacteristics.CameraFacing.CAMERA_FACING_BACK
                } else {
                    CameraFacing.CAMERA_FACING_FRONT
                }
                return getCameraWithFacing(lensFacing)
            }
        }
        return chooseDefaultCamera()
    }
    /*
     Функция выбора камеры с предпочтением по ориентации.
     Возвращает id камеры нужной ориентации или любой другой, если нужной не нашлось.
     */
    private fun getCameraWithFacing(facing: CameraFacing): CameraId {
        return if (facing == CameraFacing.CAMERA_FACING_FRONT){
            getFrontCameraId()
        } else {
            getBackCameraId()
        }
    }

    fun getFrontCameraId(): CameraId {
        if (frontCameraId == -1) {
            val cameraInfo = Camera.CameraInfo()
            for (i in 0 until getCameraNumber()) {
                Camera.getCameraInfo(i, cameraInfo)
                if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    frontCameraId = i
                    break
                }
            }
        }
        return CameraId(frontCameraId)
    }

    fun getCameraInfo(id: CameraId): Camera.CameraInfo? {
        if (id.isExist() && id.idInt < getCameraNumber()) {
            val cameraInfo = Camera.CameraInfo()
            Camera.getCameraInfo(id.idInt, cameraInfo)
            return cameraInfo
        }
        return null
    }

    private fun getCameraNumber(): Int {
        if (cameraNumber == -1) {
            cameraNumber = Camera.getNumberOfCameras()
        }
        return cameraNumber
    }

    fun hasFrontCamera(): Boolean {
        return getFrontCameraId().isExist()
    }

    fun hasBackCamera(): Boolean {
        return getBackCameraId().isExist()
    }

    fun getBackCameraId(): CameraId {
        if (backCameraId == -1) {
            val cameraInfo = Camera.CameraInfo()
            for (i in 0 until getCameraNumber()) {
                Camera.getCameraInfo(i, cameraInfo)
                if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    backCameraId = i
                    break
                }
            }
        }
        return CameraId(backCameraId)
    }

    fun getPortraitCameraDisplayOrientation(context: Context, cameraId: CameraId, isFrontCamera: Boolean): Int {
        if (cameraId.idInt < 0 || cameraId.idInt > getCameraNumber()) {
            return -1
        }
        val cameraInfo = Camera.CameraInfo()
        Camera.getCameraInfo(cameraId.idInt, cameraInfo)

        val rotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        var degrees = 0
        when (rotation) {
            Surface.ROTATION_0 -> degrees = 0
            Surface.ROTATION_90 -> degrees = 90
            Surface.ROTATION_180 -> degrees = 180
            Surface.ROTATION_270 -> degrees = 270
        }

        var result: Int
        if (isFrontCamera) {
            result = (cameraInfo.orientation + degrees) % 360
            result = (360 - result) % 360
        } else {
            result = (cameraInfo.orientation - degrees + 360) % 360
        }
        return result
    }

    fun getPreviewBufferSizeFromParameter(camera: Camera): Int {
        return if (camera.parameters.previewFormat == ImageFormat.YV12) {
            val width = camera.parameters.previewSize.width
            val height = camera.parameters.previewSize.height
            val yStride = Math.ceil(width / 16.0).toInt() * 16
            val uvStride = Math.ceil(yStride / 2 / 16.0).toInt() * 16
            val ySize = yStride * height
            val uvSize = uvStride * height / 2
            ySize + uvSize * 2
        } else {
            camera.parameters.previewSize.width *
                    camera.parameters.previewSize.height *
                    ImageFormat.getBitsPerPixel(camera.parameters.previewFormat) / 8
        }
    }

    fun findClosestFpsRange(camera: Camera, minFrameRate: Int, maxFrameRate: Int): IntArray {
        var minFrameRate = minFrameRate
        var maxFrameRate = maxFrameRate
        minFrameRate *= 1000
        maxFrameRate *= 1000
        val parameters = camera.parameters
        var minIndex = 0
        var minDiff = Integer.MAX_VALUE
        val rangeList = parameters.supportedPreviewFpsRange
        Log.d(TAG, "support preview fps range list: " + dumpFpsRangeList(rangeList))
        for (i in rangeList.indices) {
            val fpsRange = rangeList[i]
            if (fpsRange.size != 2) {
                continue
            }
            val minFps = fpsRange[0] / 1000
            val maxFps = fpsRange[1] / 1000
            val diff = Math.abs(minFps - minFrameRate) + Math.abs(maxFps - maxFrameRate)
            if (diff < minDiff) {
                minDiff = diff
                minIndex = i
            }
        }
        return rangeList[minIndex]
    }

    private fun dumpFpsRangeList(rangeList: List<IntArray>): String {
        var result = ""
        for (range in rangeList) {
            if (range.size != 2) {
                continue
            }
            result += "(" + range[0] + "," + range[1] + ") "
        }
        return result
    }

    fun findClosestPreviewSize(camera: Camera, preferSize: Point): Camera.Size {
        val preferX = preferSize.x
        val preferY = preferSize.y
        val parameters = camera.parameters
        val allSupportSizes = parameters.supportedPreviewSizes
        Log.d(TAG, "all support preview size: " + dumpPreviewSizeList(allSupportSizes))
        var minDiff = Integer.MAX_VALUE
        var index = 0
        for (i in allSupportSizes.indices) {
            val size = allSupportSizes[i]
            val x = size.width
            val y = size.height

            val diff = Math.abs(x - preferX) + Math.abs(y - preferY)
            if (diff < minDiff) {
                minDiff = diff
                index = i
            }
        }

        return allSupportSizes[index]
    }

    fun findClosestNonSquarePreviewSize(camera: Camera, preferSize: Point): Camera.Size {
        val preferX = preferSize.x
        val preferY = preferSize.y
        val parameters = camera.parameters
        val allSupportSizes = parameters.supportedPreviewSizes
        Log.d(TAG, "all support preview size: " + dumpPreviewSizeList(allSupportSizes))
        var minDiff = Integer.MAX_VALUE
        var index = 0
        for (i in allSupportSizes.indices) {
            val size = allSupportSizes[i]
            val x = size.width
            val y = size.height
            if (x != y) {
                val diff = Math.abs(x - preferX) + Math.abs(y - preferY)
                if (diff < minDiff) {
                    minDiff = diff
                    index = i
                }
            }
        }

        return allSupportSizes[index]
    }

    private fun dumpPreviewSizeList(sizes: List<Camera.Size>): String {
        var result = ""
        for (size in sizes) {
            result += "(" + size.width + "," + size.height + ") "
        }
        return result
    }

    fun transferCameraAreaFromOuterSize(center: Point, outerSize: Point, size: Int): Rect {
        val left = clampAreaCoord((center.x / outerSize.x.toFloat() * 2000 - 1000).toInt(), size)
        val top = clampAreaCoord((center.y / outerSize.y.toFloat() * 2000 - 1000).toInt(), size)

        return Rect(left, top, left + size, top + size)
    }

    /**
     * In this sample, we choose a video size with 3x4 aspect ratio. Also, we don't use sizes
     * larger than 1080p, since MediaRecorder cannot handle such a high-resolution video.
     *
     * @param choices The list of available sizes
     * @return The video size
     */
    internal fun chooseVideoSize(choices: MutableList<Camera.Size>) = choices.firstOrNull {
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
            choices: MutableList<Camera.Size>,
            width: Int,
            height: Int,
            aspectRatio: Camera.Size
    ): Camera.Size {

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

    private fun clampAreaCoord(center: Int, focusAreaSize: Int): Int {
        return if (Math.abs(center) + focusAreaSize / 2 > 1000) {
            if (center > 0) {
                1000 - focusAreaSize / 2
            } else {
                -1000 + focusAreaSize / 2
            }
        } else {
            center - focusAreaSize / 2
        }
    }

    fun canDisableShutter(id: CameraId): Boolean {
        // cameraInfo.canDisableShutterSound is only available for API 17 and newer
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            val cameraInfo = getCameraInfo(id)
            return cameraInfo != null && cameraInfo.canDisableShutterSound
        } else {
            Log.d(TAG, "SDK does not support disabling shutter sound")
            return false
        }
    }
    /*
    Сравнивает два Size на основе площади
     */
    private class CompareSizesByArea : Comparator<Camera.Size> {

        override fun compare(lhs: Camera.Size, rhs: Camera.Size): Int {
            // We cast here to ensure the multiplications won't overflow
            return java.lang.Long.signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
        }

    }
}
