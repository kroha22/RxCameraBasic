package com.example.RxCameraBasic

import android.annotation.TargetApi
import android.arch.lifecycle.DefaultLifecycleObserver
import android.arch.lifecycle.Lifecycle
import android.arch.lifecycle.LifecycleOwner
import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaRecorder
import android.util.Log
import android.view.Surface
import android.view.TextureView
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.subjects.PublishSubject
import java.io.File


@TargetApi(21)
abstract class CameraControllerBase(private val context: Context,
                                           photoFileUrl: String,
                                           lifecycle: Lifecycle,
                                           private val textureView: AutoFitTextureView,
                                           private val videoButtonCallback: VideoButtonCallback) {

    val file: File = File(photoFileUrl)

    internal var mediaRecorder: MediaRecorder? = null
    internal var nextVideoAbsolutePath: String? = null
    internal var isRecordingVideo = false

    internal val compositeDisposable = CompositeDisposable()
    internal val onPauseSubject = PublishSubject.create<Any>()
    internal val onShutterClick = PublishSubject.create<Any>()
    internal val onStartVideoClick = PublishSubject.create<Any>()
    internal val onStopVideoClick = PublishSubject.create<Any>()
    internal val onSwitchCameraClick = PublishSubject.create<Any>()
    internal val onSurfaceTextureAvailable = PublishSubject.create<SurfaceTexture>()

    internal var surface: Surface? = null

    private val lifecycleObserver = object : DefaultLifecycleObserver {

        override fun onCreate(owner: LifecycleOwner) {

            showLog("\tonCreate")

            onCreate()

            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {

                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    showLog("\tonSurfaceTextureAvailable")
                    onSurfaceTextureAvailable.onNext(surface)
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                    showLog("\tonSurfaceTextureSizeChanged")
                    onSurfaceTextureAvailable.onNext(surface)
                    //NO-OP
                }

                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    showLog("\tonSurfaceTextureDestroyed")
                    return true
                }

                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
            }

            // For some reasons onSurfaceSizeChanged is not always called, this is a workaround
            textureView.addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                showLog("\tonLayoutChange")
                if (textureView.isAvailable) {
                    showLog("\ttextureView.isAvailable()")
                    onSurfaceTextureAvailable.onNext(textureView.surfaceTexture)
                }
            }
        }

        override fun onResume(owner: LifecycleOwner) {
            showLog("\tonResume")

            subscribe()

            // When the screen is turned off and turned back on, the SurfaceTexture is already
            // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
            // a camera and start preview from here (otherwise, we wait until the surface is ready in
            // the SurfaceTextureListener).
            if (textureView.isAvailable) {
                showLog("\ttextureView.isAvailable()")
                onSurfaceTextureAvailable.onNext(textureView.surfaceTexture)
            }
        }


        override fun onPause(owner: LifecycleOwner) {
            showLog("\tonPause")
            onPauseSubject.onNext(this)
        }

    }

    //----------------------------------------------------------------------------------------------

    init {
        lifecycle.addObserver(lifecycleObserver)
    }

    fun takePhoto() {
        /*DEBUG*/showLog("\tonPhotoClick")
        onShutterClick.onNext(this)
    }

    fun makeVideo() {
        /*DEBUG*/showLog("\tonVideoClick")
        if (isRecordingVideo) onStopVideoClick.onNext(this) else onStartVideoClick.onNext(this)
    }

    fun switchCamera() {
        /*DEBUG*/showLog("\tonSwitchCameraClick")
        onSwitchCameraClick.onNext(this)
    }
    /**
     * Flow is configured in this method
     */
    internal open fun subscribe(){
        /*DEBUG*/showLog("\tsubscribe")
        compositeDisposable.clear()
    }

    internal fun unsubscribe() {
        /*DEBUG*/showLog("\tunsubscribe")
        compositeDisposable.clear()
    }

    abstract fun onCreate()

    internal fun initMediaRecorder() {
        /*DEBUG*/showLog("\tinitMediaRecorder")
        mediaRecorder = MediaRecorder()
    }

    internal fun setVideoBtnState(isPressed: Boolean) {
        /*DEBUG*/showLog("\tsetVideoBtnState " + isPressed)
        isRecordingVideo = isPressed
        videoButtonCallback.onClick(isPressed)
    }

    internal fun getVideoFilePath(): String {
        /*DEBUG*/showLog("\tgetVideoFilePath")
        val filename = "${System.currentTimeMillis()}.mp4"
        val dir = context.getExternalFilesDir(null)

        return if (dir == null) {
            filename
        } else {
            "${dir.absolutePath}/$filename"
        }
    }

    internal fun closeMediaRecorder() {
        /*DEBUG*/showLog("\tcloseMediaRecorder")
        mediaRecorder?.reset()
        mediaRecorder?.release()
        mediaRecorder = null
    }

    //----------------------------------------------------------------------------------------------

    companion object {

        internal val TAG = CameraControllerBase::class.java.name

        internal fun showLog(msg: String) {
            Log.d(TAG + "!!!!!!!", msg)
        }

        internal fun contains(modes: IntArray?, mode: Int): Boolean {
            if (modes == null) {
                return false
            }
            for (i in modes) {
                if (i == mode) {
                    return true
                }
            }
            return false
        }
    }
    //----------------------------------------------------------------------------------------------

    interface Callback {
        fun onFocusStarted()

        fun onFocusFinished()

        fun onMessage(msg: String)

        fun onPhotoTaken(photoUrl: String)

        fun onVideoTaken(videoUrl: String)

        fun onCameraAccessException()

        fun onCameraOpenException(exception: Exception)

        fun onException(throwable: Throwable)
    }
    //----------------------------------------------------------------------------------------------

    interface VideoButtonCallback {
        fun onClick(isPressed:Boolean)
    }
    //----------------------------------------------------------------------------------------------

}
