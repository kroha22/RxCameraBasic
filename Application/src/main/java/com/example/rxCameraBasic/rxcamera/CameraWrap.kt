package com.example.rxCameraBasic.rxcamera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Point
import android.hardware.Camera
import android.os.Build
import android.util.Log
import android.view.TextureView
import java.util.*

@Suppress("DEPRECATION")
class CameraWrap(context: Context, private var characteristics: CameraCharacteristics) {
    //------------------------------------------------------------------------------------------------
    companion object {
        private const val TAG = "CameraWrap"
        private const val CALLBACK_BUFF_COUNT = 3
    }
    //------------------------------------------------------------------------------------------------
    var nativeCamera: Camera
    val rotateMatrix: Matrix = Matrix()

    var parameters: Camera.Parameters

    private var isBindSurface = false
    private var bindTextureView: TextureView? = null
    private var isSurfaceAvailable = false
    private var isNeedStartPreviewLater = false

    private var callbackBuffList: MutableList<ByteArray>? = null

    private var isSetPreviewCallback = false

    private val previewFrameCallbackList = ArrayList<OnCameraPreviewFrameCallback>()
    private val oneshotPreviewFrameCallbackList = ArrayList<OnCameraPreviewFrameCallback>()

    private val cameraPreviewCallback = Camera.PreviewCallback { data, camera ->
        for (callback in previewFrameCallbackList) {
            callback.onPreviewFrame(data)
        }
        for (callback in oneshotPreviewFrameCallbackList) {
            callback.onPreviewFrame(data)
        }
        oneshotPreviewFrameCallbackList.clear()
        camera.addCallbackBuffer(data)
    }

    //------------------------------------------------------------------------------------------------
    init {
        rotateMatrix.postRotate(characteristics.cameraOrientation.toFloat(), 0.5f, 0.5f)
        nativeCamera = CameraBuilder(context, characteristics).build()

        parameters = nativeCamera.parameters
    }

    fun bindTextureInternal(textureView: TextureView?){
        checkBindSurfaceCondition({!isBindSurface}, "Has bind surface")
        checkBindSurfaceCondition({textureView != null}, "Texture view null")

        try {
            bindTextureView = textureView
            if (bindTextureView!!.surfaceTexture != null) {
                nativeCamera.setPreviewTexture(bindTextureView!!.surfaceTexture)
            }
            isBindSurface = true
        } catch (e: Exception) {
            throw BindSurfaceFailedException(TAG + ": bindSurfaceTexture failed: " + e.message, e)
        }
    }

    fun startPreviewInternal() {
        checkPreviewFailedCondition({isBindSurface}, "Has not bind surface")

        try {
            isSurfaceAvailable = false
            if (bindTextureView != null && bindTextureView!!.isAvailable) {
                isSurfaceAvailable = true
            }
            if (!isSurfaceAvailable && characteristics.isHandleSurfaceEvent) {
                isNeedStartPreviewLater = true
            }
            nativeCamera.startPreview()
        } catch (e: Exception) {
            throw StartPreviewFailedException("$TAG:  preview failed: " + e.message, e)
        }
    }

    fun switchCameraCharacteristics(): CameraCharacteristics {
        val builder = CameraCharacteristics.Builder()
        builder.from(characteristics)
        if (characteristics.isFaceCamera()) {
            builder.useBackCamera()
        } else {
            builder.useFrontCamera()
        }
        return builder.build()
    }

    fun installPreviewCallback(previewCallback: OnCameraPreviewFrameCallback) {
        if (callbackBuffList == null) {
            initCallbackBuffList()
        }
        for (i in callbackBuffList!!.indices) {
            nativeCamera.addCallbackBuffer(callbackBuffList!![i])
        }
        this.previewFrameCallbackList.add(previewCallback)
        if (!isSetPreviewCallback) {
            nativeCamera.setPreviewCallbackWithBuffer(cameraPreviewCallback)
            isSetPreviewCallback = true
        }
    }

    fun uninstallPreviewCallback(previewCallback: OnCameraPreviewFrameCallback): Boolean {
        return previewFrameCallbackList.remove(previewCallback)
    }

    fun installOneShotPreviewCallback(previewFrameCallback: OnCameraPreviewFrameCallback) {
        this.oneshotPreviewFrameCallbackList.add(previewFrameCallback)
        nativeCamera.setOneShotPreviewCallback(cameraPreviewCallback)
        isSetPreviewCallback = false // the oneshot callback will only be called once
    }

    fun uninstallOneShotPreviewCallback(previewFrameCallback: OnCameraPreviewFrameCallback): Boolean {
        return oneshotPreviewFrameCallbackList.remove(previewFrameCallback)
    }

    fun release() {
        nativeCamera.setPreviewCallback(null)
        nativeCamera.release()
    }

    fun closeCamera(): Boolean {
        try {
            release()
            reset()
        } catch (e: Exception) {
            Log.e(TAG, "close camera failed: " + e.message)
            return false
        }

        return true
    }

    /**
     * set the zoom level of the camera
     * @param level
     * @return
     */
    fun zoom(level: Int) {
        val parameters = nativeCamera.parameters
        if (!parameters.isZoomSupported) {
            throw ZoomFailedException(ZoomFailedException.Reason.ZOOM_NOT_SUPPORT)
        }
        val maxZoomLevel = parameters.maxZoom
        if (level < 0 || level > maxZoomLevel) {
            throw ZoomFailedException(ZoomFailedException.Reason.ZOOM_RANGE_ERROR)
        }
        parameters.zoom = level
        nativeCamera.parameters = parameters
    }

    /**
     * smooth zoom the camera, which will gradually change the preview content
     * @param level
     * @return
     */
    fun smoothZoom(level: Int) {
        val parameters = nativeCamera.parameters
        if (!parameters.isZoomSupported || !parameters.isSmoothZoomSupported) {
            throw ZoomFailedException(ZoomFailedException.Reason.ZOOM_NOT_SUPPORT)
        }
        val maxZoomLevel = parameters.maxZoom
        if (level < 0 || level > maxZoomLevel) {
            throw ZoomFailedException(ZoomFailedException.Reason.ZOOM_RANGE_ERROR)
        }
        nativeCamera.startSmoothZoom(level)
    }

    fun flashAction(isOn: Boolean) {
        val parameters = nativeCamera.parameters
        if (parameters.supportedFlashModes == null || parameters.supportedFlashModes.size <= 0) {
            throw SettingFlashException(SettingFlashException.Reason.NOT_SUPPORT)
        }
        if (isOn) {
            if (parameters.supportedFlashModes.contains(Camera.Parameters.FLASH_MODE_TORCH)) {
                parameters.flashMode = Camera.Parameters.FLASH_MODE_TORCH
                nativeCamera.parameters = parameters
            } else {
                throw SettingFlashException(SettingFlashException.Reason.NOT_SUPPORT)
            }
        } else {
            if (parameters.supportedFlashModes.contains(Camera.Parameters.FLASH_MODE_OFF)) {
                parameters.flashMode = Camera.Parameters.FLASH_MODE_OFF
                nativeCamera.parameters = parameters
            } else {
                throw SettingFlashException(SettingFlashException.Reason.NOT_SUPPORT)
            }
        }
    }    

    fun areaFocusAction(focusAreaList: List<Camera.Area>) {
        if (!focusAreaList.isEmpty()) {
            val parameters = nativeCamera.parameters
            if (parameters.maxNumFocusAreas < focusAreaList.size) {
                throw SettingAreaFocusError(SettingAreaFocusError.Reason.NOT_SUPPORT)
            } else {
                if (parameters.focusMode != Camera.Parameters.FOCUS_MODE_AUTO) {
                    val focusModes = parameters.supportedFocusModes
                    if (focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                        parameters.focusMode = Camera.Parameters.FOCUS_MODE_AUTO
                    }
                }
                parameters.focusAreas = focusAreaList
                nativeCamera.parameters = parameters
                nativeCamera.autoFocus { success, _ ->
                    if (!success) {
                        throw SettingAreaFocusError(SettingAreaFocusError.Reason.SET_AREA_FOCUS_FAILED)
                    }
                }
            }
        }
    }

    fun areaMeterAction(meterAreaList: List<Camera.Area>) {
        if (!meterAreaList.isEmpty()) {
            val parameters = nativeCamera.parameters
            if (parameters.maxNumMeteringAreas < meterAreaList.size) {
                throw SettingMeterAreaError(SettingMeterAreaError.Reason.NOT_SUPPORT)
            } else {
                parameters.focusAreas = meterAreaList
                nativeCamera.parameters = parameters
            }
        }
    }

    private fun reset() {
        isBindSurface = false
        isNeedStartPreviewLater = false
        isSurfaceAvailable = false
        previewFrameCallbackList.clear()
        oneshotPreviewFrameCallbackList.clear()
        bindTextureView = null
    }

    private fun checkBindSurfaceCondition(condition:()->Boolean, errMsg:String){
        if(!condition.invoke()){
            throw BindSurfaceFailedException("$TAG: bindSurfaceTexture failed: $errMsg", Exception())
        }
    }

    private fun checkPreviewFailedCondition(condition:()->Boolean, errMsg:String){
        if(!condition.invoke()){
            throw StartPreviewFailedException("$TAG:  preview failed: $errMsg", Exception())
        }
    }

    fun onSurfaceAvailable() {
        if (isNeedStartPreviewLater) {
            try {
                if (bindTextureView != null) {
                    nativeCamera.setPreviewTexture(bindTextureView!!.surfaceTexture)
                }
                nativeCamera.startPreview()
            } catch (e: Exception) {
                Log.e(TAG, "onAvailable, start preview failed")
            }
        }
    }
    /*
     data a byte array of the picture data
     */
    fun takePicture(shutterAction: () -> Unit?, dataConsumer: (ByteArray?) -> Unit) {
        nativeCamera.takePicture({ shutterAction.invoke() }, { _, _ -> }, { data, _ -> dataConsumer.invoke(data) })
    }

    fun getTextureView() = bindTextureView

    fun onSurfaceDestroy() {
        isSurfaceAvailable = false
    }

    private fun initCallbackBuffList() {
        var buffSize = characteristics.previewBufferSize
        if (characteristics.previewBufferSize == -1) {
            buffSize = CameraStrategy.getPreviewBufferSizeFromParameter(nativeCamera)
        }
        callbackBuffList = ArrayList()
        for (i in 0 until CALLBACK_BUFF_COUNT) {
            callbackBuffList!!.add(ByteArray(buffSize))
        }
    }

    //---------------------------------------------------------------------------------------------
    private class CameraBuilder(context: Context, characteristics: CameraCharacteristics) {

        private val nativeCamera: Camera
        private val parameters: Camera.Parameters

        init {
            nativeCamera = try {
                Camera.open(characteristics.currentCameraId.idInt)
            } catch (e: Exception) {
                throw OpenCameraFailedException(e, OpenCameraFailedException.Reason.OPEN_FAILED, "open camera failed")
            }

            parameters = try {
                nativeCamera.parameters
            } catch (e: Exception) {
                throw OpenCameraFailedException(Exception(), OpenCameraFailedException.Reason.GET_PARAMETER_FAILED, "get parameter failed: " + e.message)
            }
                    ?: throw OpenCameraFailedException(Exception(), OpenCameraFailedException.Reason.GET_PARAMETER_FAILED, "get camera parameters failed")

            applyCharacteristics(context, characteristics)
        }

        fun applyCharacteristics(context: Context, characteristics: CameraCharacteristics) {
            if (characteristics.minPreferPreviewFrameRate != -1 && characteristics.maxPreferPreviewFrameRate != -1) {
                setFps(characteristics.minPreferPreviewFrameRate, characteristics.maxPreferPreviewFrameRate)
            }

            if (characteristics.preferPreviewSize != null) {
                setPreviewSize(characteristics.acceptSquarePreview, characteristics.preferPreviewSize)
            }

            if (characteristics.previewFormat != -1) {
                setFormat(characteristics.previewFormat)
            }

            if (characteristics.isAutoFocus) {
                setAutoFocus(Camera.Parameters.FOCUS_MODE_AUTO)
            }

            if (CameraStrategy.canDisableShutter(characteristics.currentCameraId) && characteristics.muteShutterSound) {
                setEnableShutterSound(false)
            }

            applyParameters()

            var displayOrientation = characteristics.displayOrientation
            if (displayOrientation == -1) {
                displayOrientation = CameraStrategy.getPortraitCameraDisplayOrientation(context, characteristics.currentCameraId, characteristics.isFaceCamera())
            }
            setDisplayOrientation(displayOrientation)
        }

        // set enableShutterSound (only supported for API 17 and newer)
        fun setEnableShutterSound(enabled: Boolean): CameraBuilder {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                nativeCamera.enableShutterSound(enabled)
            }
            return this
        }

        @Throws(OpenCameraFailedException::class)
        fun setFps(minFrameRate: Int, maxFrameRate: Int): CameraBuilder {
            execute(
                    {
                        val range = CameraStrategy.findClosestFpsRange(nativeCamera, minFrameRate, maxFrameRate)
                        parameters.setPreviewFpsRange(range[0], range[1])
                    },
                    OpenCameraFailedException.Reason.SET_PREVIEW_FORMAT_FAILED,
                    "set preview fps range failed"
            )
            return this
        }

        @Throws(OpenCameraFailedException::class)
        fun setPreviewSize( acceptSquarePreview: Boolean, preferPreviewSize: Point): CameraBuilder {
            execute(
                    {
                        //check whether squared preview is accepted or not.
                        val previewSize = if (acceptSquarePreview) {
                            CameraStrategy.findClosestPreviewSize(nativeCamera, preferPreviewSize)
                        } else {
                            CameraStrategy.findClosestNonSquarePreviewSize(nativeCamera, preferPreviewSize)
                        }
                        parameters.setPreviewSize(previewSize.width, previewSize.height)
                    },
                    OpenCameraFailedException.Reason.SET_PREVIEW_SIZE_FAILED,
                    "set preview size failed"
            )
            return this
        }

        @Throws(OpenCameraFailedException::class)
        fun setFormat(previewFormat: Int): CameraBuilder {
            execute(
                    {
                        parameters.previewFormat = previewFormat
                        parameters.pictureFormat = ImageFormat.JPEG
                    },
                    OpenCameraFailedException.Reason.SET_PREVIEW_FORMAT_FAILED,
                    "set preview format failed"
            )
            return this
        }

        @Throws(OpenCameraFailedException::class)
        fun setAutoFocus(focusMode: String): CameraBuilder {
            execute(
                    {
                        val focusModes = parameters.supportedFocusModes
                        if (focusModes.contains(focusMode)) {
                            parameters.focusMode = focusMode
                        }
                    },
                    OpenCameraFailedException.Reason.SET_AUTO_FOCUS_FAILED,
                    "set auto focus failed"
            )
            return this
        }

        @Throws(OpenCameraFailedException::class)
        fun applyParameters(): CameraBuilder {
            execute({ nativeCamera.parameters = parameters }, OpenCameraFailedException.Reason.SET_PARAMETER_FAILED, "set final parameter failed")
            return this
        }

        @Throws(OpenCameraFailedException::class)
        fun setDisplayOrientation(displayOrientation: Int): CameraBuilder {
            execute( {nativeCamera.setDisplayOrientation(displayOrientation)},
                    OpenCameraFailedException.Reason.SET_DISPLAY_ORIENTATION_FAILED,
                    "set display orientation failed")

            return this
        }

        fun build(): Camera {
            return nativeCamera
        }

        @Throws(OpenCameraFailedException::class)
        private fun execute(cameraAction: () -> Unit, failedReason: OpenCameraFailedException.Reason, failedMsg: String): Boolean {
            return try {
                cameraAction.invoke()
                true
            } catch (e: Exception) {
                throw OpenCameraFailedException(e, failedReason, failedMsg)
            }
        }

    }

    //--------------------------------------------------------------------------------------------
    class CameraParametersBuilder private constructor(rxCamera: CameraWrap) {

        private val param: Camera.Parameters = rxCamera.nativeCamera.parameters

        companion object {
            fun forCamera(rxCamera: CameraWrap): CameraParametersBuilder{
                return CameraParametersBuilder(rxCamera)
            }
        }

        fun applyParameters(rxCamera: CameraWrap){
            rxCamera.nativeCamera.parameters = param
        }

        fun setFlashMode(flashMode: String): CameraParametersBuilder {
            if (param.supportedFlashModes != null && param.supportedFlashModes.contains(flashMode)) {
                param.flashMode = flashMode
            }
            return this
        }

        fun setPictureSize(pictureWidth: Int, pictureHeight: Int): CameraParametersBuilder {
            if (pictureWidth != -1 && pictureHeight != -1) {
                val size = findClosetPictureSize(param.supportedPictureSizes, pictureWidth, pictureHeight)
                if (size != null) {
                    param.setPictureSize(size.width, size.height)
                }
            }
            return this
        }

        fun setPictureFormat(pictureFormat: Int): CameraParametersBuilder {
            if (pictureFormat != -1) {
                param.pictureFormat = pictureFormat
            }
            return this
        }

        private fun findClosetPictureSize(sizeList: List<Camera.Size>?, width: Int, height: Int): Camera.Size? {
            if (sizeList == null || sizeList.isEmpty()) {
                return null
            }
            var minDiff = Integer.MAX_VALUE
            var bestSize: Camera.Size? = null
            for (size in sizeList) {
                val diff = Math.abs(size.width - width) + Math.abs(size.height - height)
                if (diff < minDiff) {
                    minDiff = diff
                    bestSize = size
                }
            }
            return bestSize
        }
    }
    //---------------------------------------------------------------------------------------------
    interface OnCameraPreviewFrameCallback {
        fun onPreviewFrame(data: ByteArray?)
    }
    //---------------------------------------------------------------------------------------------
    class CameraData {

        /**
         * the raw preview frame, the format is in YUV if you not set the
         * preview format in the config, it will null on face detect request
         */
        var cameraData: ByteArray? = null

        /**
         * a matrix help you rotate the camera data in portrait mode,
         * it will null on face detect request
         */
        var rotateMatrix: Matrix? = null
    }
    //---------------------------------------------------------------------------------------------


}
