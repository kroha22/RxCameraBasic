package com.example.rxCameraBasic

import android.annotation.TargetApi
import android.arch.lifecycle.DefaultLifecycleObserver
import android.arch.lifecycle.Lifecycle
import android.arch.lifecycle.LifecycleOwner
import android.graphics.SurfaceTexture
import android.media.MediaRecorder
import android.view.Surface
import android.view.TextureView
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.subjects.PublishSubject
import java.io.File


@TargetApi(21)
abstract class CameraControllerBase(photoFileUrl: String,
                                    lifecycle: Lifecycle,
                                    private val textureView: AutoFitTextureView,
                                    private val videoButtonCallback: VideoButtonCallback) {

    //----------------------------------------------------------------------------------------------

    companion object {

        private val TAG = CameraControllerBase::class.java.name

        internal fun log(msg: String) {
            MainActivity.log("$TAG: $msg")
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

        fun showWaitView()

        fun hideWaitView()

        fun showFocusStarted()

        fun showFocusFinished()

        fun showMessage(msg: String)

        fun onPhotoTaken(photoUrl: String)

        fun onVideoTaken(videoUrl: String)

        fun showError(error: String)

    }
    //----------------------------------------------------------------------------------------------

    interface VideoButtonCallback {
        fun onClick(isPressed:Boolean)
    }
    //----------------------------------------------------------------------------------------------

    val file: File = File(photoFileUrl)

    internal var mediaRecorder: MediaRecorder? = null
    internal var videoAbsolutePath: String? = null
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
            log("onCreate")

            onCreate()
            initSurfaceTextureListener()
        }

        override fun onResume(owner: LifecycleOwner) {
            log("onResume")

            subscribe()
            /*
            Когда выкл/вкл экран, если SurfaceTexture уже доступен, то «onSurfaceTextureAvailable» не будет вызываться
             */
            if (textureView.isAvailable) {
                log("textureView.isAvailable()")

                onSurfaceTextureAvailable.onNext(textureView.surfaceTexture)
            }
        }


        override fun onPause(owner: LifecycleOwner) {
            log("onPause")

            onPauseSubject.onNext(this)
        }
    }

    //----------------------------------------------------------------------------------------------

    init {
        lifecycle.addObserver(lifecycleObserver)
    }

    fun takePhoto() {
        log("onPhotoClick")
        
        onShutterClick.onNext(this)
    }

    fun makeVideo() {
        log("onVideoClick, isRecordingVideo = $isRecordingVideo")
        
        if (isRecordingVideo) onStopVideoClick.onNext(this) else onStartVideoClick.onNext(this)
    }

    fun switchCamera() {
        log("onSwitchCameraClick")
        
        onSwitchCameraClick.onNext(this)
    }

    abstract fun onCreate()    
    
    internal open fun subscribe(){
        log("subscribe")
        
        compositeDisposable.clear()
    }

    internal fun unsubscribe() {
        log("unsubscribe")
        
        compositeDisposable.clear()
    }

    internal fun initMediaRecorder() {       
        log("initMediaRecorder")
        
        mediaRecorder = MediaRecorder()
    }

    internal fun closeMediaRecorder() {
        log("closeMediaRecorder")

        mediaRecorder?.reset()
        mediaRecorder?.release()
        mediaRecorder = null
    }

    internal fun setVideoBtnState(isPressed: Boolean) {
        isRecordingVideo = isPressed
        videoButtonCallback.onClick(isPressed)
    }

    fun initSurfaceTextureListener() {
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {

            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                log("onSurfaceTextureAvailable")

                onSurfaceTextureAvailable.onNext(surface)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                log("onSurfaceTextureSizeChanged")

                onSurfaceTextureAvailable.onNext(surface)
            }

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                log("onSurfaceTextureDestroyed")

                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
        }

        // По некоторым причинам onSurfaceSizeChanged не всегда вызывается, это обходное решение
        textureView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            log("onLayoutChange")

            if (textureView.isAvailable) {
                log("textureView.isAvailable()")

                onSurfaceTextureAvailable.onNext(textureView.surfaceTexture)
            }
        }
    }

}
