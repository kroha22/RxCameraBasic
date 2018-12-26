package com.example.rxCameraBasic.rxcamera

import java.lang.Exception

class CameraDataNullException : Exception("the camera data is null")

class TakePictureFailedException(detailMessage: String) : Exception(detailMessage)

class StartPreviewFailedException(detailMessage: String, cause: Throwable) : Exception("$detailMessage Cause: $cause")

class BindSurfaceFailedException(detailMessage: String, cause: Throwable) : Exception("$detailMessage Cause: $cause")

class OpenCameraFailedException(override val cause: Throwable, val reason: Reason, detailMessage: String) : Exception(String.format("$detailMessage: ", reason.toString())) {
    enum class Reason {
        PARAMETER_ERROR,
        GET_PARAMETER_FAILED,
        OPEN_FAILED,
        SET_FPS_FAILED,
        SET_PREVIEW_SIZE_FAILED,
        SET_PREVIEW_FORMAT_FAILED,
        SET_PARAMETER_FAILED,
        SET_DISPLAY_ORIENTATION_FAILED,
        SET_AUTO_FOCUS_FAILED
    }
}

class ZoomFailedException(reason: Reason) : Exception(String.format("Zoom failed: %s", reason.toString())) {
    enum class Reason {
        ZOOM_NOT_SUPPORT,
        ZOOM_RANGE_ERROR
    }
}

class SettingAreaFocusError(val reason: Reason) : Exception() {
    enum class Reason {
        NOT_SUPPORT,
        SET_AREA_FOCUS_FAILED
    }
}

class SettingFlashException(val reason: Reason) : Exception() {
    enum class Reason {
        NOT_SUPPORT
    }
}

class SettingMeterAreaError(val reason: Reason) : Exception() {
    enum class Reason {
        NOT_SUPPORT
    }
}
