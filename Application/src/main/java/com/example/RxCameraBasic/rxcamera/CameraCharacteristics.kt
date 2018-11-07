package com.example.RxCameraBasic.rxcamera

import android.graphics.Point

class CameraCharacteristics(builder: Builder) {
    //------------------------------------------------------------------------------------------------
    enum class CameraFacing {
        CAMERA_FACING_BACK, CAMERA_FACING_FRONT
    }

    class CameraId(val idInt: Int){
        companion object {
            fun empty(): CameraId{
                return CameraId(-1)
            }
        }
        fun isExist():Boolean{
            return idInt > -1
        }
    }
    class Builder {
        //------------------------------------------------------------------------------------------------
        companion object {
            var DEFAULT_PREFER_PREVIEW_SIZE = Point(320, 240)
        }
        //------------------------------------------------------------------------------------------------
        internal var cameraFacing = CameraFacing.CAMERA_FACING_BACK
        internal var currentCameraId = CameraId.empty()
        internal var preferPreviewSize: Point? = null
        internal var acceptSquarePreview = true
        internal var minPreferPreviewFrameRate = -1
        internal var maxPreferPreviewFrameRate = -1
        internal var previewFormat = -1
        internal var displayOrientation = -1
        internal var isAutoFocus = false
        internal var previewBufferSize = -1
        internal var isHandleSurfaceEvent = false
        internal var cameraOrientation = -1
        internal var muteShutterSound = false

        fun useFrontCamera(): Builder {
            cameraFacing = CameraFacing.CAMERA_FACING_FRONT
            currentCameraId = CameraStrategy.getFrontCameraId()
            return this
        }

        fun useBackCamera(): Builder {
            cameraFacing = CameraFacing.CAMERA_FACING_BACK
            currentCameraId = CameraStrategy.getBackCameraId()
            return this
        }

        fun setPreferPreviewSize(size: Point?, acceptSquarePreview: Boolean): Builder {
            if (size == null) {
                return this
            }
            preferPreviewSize = size
            this.acceptSquarePreview = acceptSquarePreview
            return this
        }

        fun setPreferPreviewFrameRate(minFrameRate: Int, maxFrameRate: Int): Builder {
            if (minFrameRate <= 0 || maxFrameRate <= 0 || maxFrameRate < minFrameRate) {
                return this
            }
            minPreferPreviewFrameRate = minFrameRate
            maxPreferPreviewFrameRate = maxFrameRate
            return this
        }

        fun setPreviewFormat(previewFormat: Int): Builder {
            this.previewFormat = previewFormat
            return this
        }

        fun setDisplayOrientation(displayOrientation: Int): Builder {
            if (displayOrientation < 0) {
                return this
            }
            if (displayOrientation != 0 &&
                    displayOrientation != 90 &&
                    displayOrientation != 180 &&
                    displayOrientation != 270)
                throw IllegalArgumentException("display orientation: $displayOrientation. (must be 0, 90, 180, or 270)")

            this.displayOrientation = displayOrientation
            return this
        }

        fun setAutoFocus(isAutoFocus: Boolean): Builder {
            this.isAutoFocus = isAutoFocus
            return this
        }

        fun setHandleSurfaceEvent(isHandle: Boolean): Builder {
            isHandleSurfaceEvent = isHandle
            return this
        }

        fun setPreviewBufferSize(size: Int): Builder {
            previewBufferSize = size
            return this
        }

        fun setMuteShutterSound(mute: Boolean): Builder {
            muteShutterSound = mute
            return this
        }

        fun from(characteristics: CameraCharacteristics): Builder {
            if (characteristics.isFaceCamera()) {
                useFrontCamera()
            } else {
                useBackCamera()
            }
            setPreferPreviewSize(characteristics.preferPreviewSize, characteristics.acceptSquarePreview)
            setPreferPreviewFrameRate(characteristics.maxPreferPreviewFrameRate, characteristics.minPreferPreviewFrameRate)
            setPreviewFormat(characteristics.previewFormat)
            setDisplayOrientation(characteristics.displayOrientation)
            setAutoFocus(characteristics.isAutoFocus)
            setHandleSurfaceEvent(characteristics.isHandleSurfaceEvent)
            setPreviewBufferSize(characteristics.previewBufferSize)
            setMuteShutterSound(characteristics.muteShutterSound)
            return this
        }

        fun build(): CameraCharacteristics {
            setProperConfigVal()
            return CameraCharacteristics(this)
        }

        private fun setProperConfigVal(): Builder {
            if (!currentCameraId.isExist()) {
                currentCameraId = if (!isFaceCamera()) {
                    CameraStrategy.getBackCameraId()
                } else {
                    CameraStrategy.getFrontCameraId()
                }
            }
            if (preferPreviewSize == null) {
                preferPreviewSize = DEFAULT_PREFER_PREVIEW_SIZE
            }

            val cameraInfo = CameraStrategy.getCameraInfo(currentCameraId)
            if (cameraInfo != null) {
                cameraOrientation = cameraInfo.orientation
            }
            return this
        }

        private fun isFaceCamera(): Boolean {
            return cameraFacing == CameraFacing.CAMERA_FACING_FRONT
        }
    }
    //------------------------------------------------------------------------------------------------

    val cameraFacing: CameraFacing = CameraFacing.CAMERA_FACING_BACK
    val currentCameraId: CameraId = builder.currentCameraId
    val preferPreviewSize: Point? = builder.preferPreviewSize
    val acceptSquarePreview: Boolean = builder.acceptSquarePreview
    val minPreferPreviewFrameRate: Int = builder.minPreferPreviewFrameRate
    val maxPreferPreviewFrameRate: Int = builder.maxPreferPreviewFrameRate
    val previewFormat: Int = builder.previewFormat
    val displayOrientation: Int = builder.displayOrientation
    val isAutoFocus: Boolean = builder.isAutoFocus
    val previewBufferSize: Int = builder.previewBufferSize
    val isHandleSurfaceEvent: Boolean = builder.isHandleSurfaceEvent
    val cameraOrientation: Int = builder.cameraOrientation
    val muteShutterSound: Boolean = builder.muteShutterSound

    fun isFaceCamera(): Boolean {
        return cameraFacing == CameraFacing.CAMERA_FACING_FRONT
    }

    override fun toString(): String {
        val result = StringBuilder("CameraCharacteristics ")
        result.append(String.format("cameraFacing: %b, currentCameraId: %d, ", cameraFacing.name, currentCameraId))
        result.append(String.format("preferPreviewSize: %s, ", preferPreviewSize))
        result.append(String.format("minPreferPreviewFrameRate: %d, maxPreferPreviewFrameRate: %d, ", minPreferPreviewFrameRate, maxPreferPreviewFrameRate))
        result.append(String.format("previewFormat: %d, ", previewFormat))
        result.append(String.format("displayOrientation: %d, ", displayOrientation))
        result.append(String.format("isAutoFocus: %b", isAutoFocus))
        result.append(String.format("previewBufferSize: %d, ", previewBufferSize))
        result.append(String.format("isHandleSurfaceEvent: %b, ", isHandleSurfaceEvent))
        result.append(String.format("cameraOrientation: %d, ", cameraOrientation))
        result.append(String.format("acceptSquarePreview: %s, ", acceptSquarePreview))
        result.append(String.format("muteShutterSound: %s", muteShutterSound))
        return result.toString()
    }
    //------------------------------------------------------------------------------------------------

}
