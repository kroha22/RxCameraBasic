package com.example.rxCameraBasic

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import com.example.rxCameraBasic.rxcamera.Camera1Controller
import com.example.rxCameraBasic.rxcamera2.Camera2Controller
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*


class MainActivity : AppCompatActivity() {
    //-----------------------------------------------------------------------------------------------
    companion object {
        private const val APP_MEDIA_DIRECTORY_NAME = "RxCameraBasic"
        private const val REQUEST_PERMISSION_CODE = 233
        private const val TAG = "RxCameraBasic:"

        private val REQUEST_PERMISSIONS = arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_EXTERNAL_STORAGE ,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        )

        fun log(msg: String) {
            Log.d(TAG, msg)
        }

        fun getVideoFilePath(): String? {
            val mediaStorageDir = getMediaStorageDir(Environment.DIRECTORY_MOVIES) ?: return null

            return mediaStorageDir.path + File.separator + "VID_" + getTimeStamp() + ".mp4"
        }

        fun getOutputPhotoFile():File? {
            val mediaStorageDir = getMediaStorageDir(Environment.DIRECTORY_PICTURES) ?: return null
            return try {
                File(mediaStorageDir.path + File.separator + "PHOTO_" + getTimeStamp() + ".jpg")
            } catch (ex: IOException) {
                log("Create output file error: " + ex.message)
                null
            }
        }

        private fun getMediaStorageDir(type: String): File? {

            val dir = File(
                    Environment.getExternalStoragePublicDirectory(type),
                    APP_MEDIA_DIRECTORY_NAME)
            if (!dir.exists() && !dir.mkdirs()) {
                log( "save: error: can't create Pictures directory")
                return null
            }

            return dir
        }

        private fun getTimeStamp() = SimpleDateFormat("yyyyMMdd_HHmmss",
                Locale.getDefault()).format(Date())

    }
    //-----------------------------------------------------------------------------------------------

    private var cameraController: CameraControllerBase? = null
    private var focusIndicator: View? = null
    private lateinit var textureView: AutoFitTextureView
    private lateinit var photoBtn: ImageView
    private lateinit var videoBtn: ImageView
    private lateinit var waitView: FrameLayout

    private val cameraControllerCallback = object : CameraControllerBase.Callback {

        override fun onPhotoTaken(photoUrl: String) {
            MediaScannerConnection.scanFile(this@MainActivity, Array(1){photoUrl}, null)
            { path, uri ->
                log("Photo $uri saved to $path")
                val intent = ShowPhotoActivity.IntentHelper.createIntent(this@MainActivity, photoUrl)
                startActivity(intent)
            }
        }

        override fun onVideoTaken(videoUrl: String) {
            MediaScannerConnection.scanFile(this@MainActivity, Array(1){videoUrl}, null)
            { path, uri -> log("Video $uri saved to $path")}
        }

        override fun showWaitView() {
            waitView.visibility = View.VISIBLE
        }

        override fun hideWaitView() {
            waitView.visibility = View.GONE
        }

        override fun showFocusStarted() {
            focusIndicator!!.visibility = View.VISIBLE
            focusIndicator!!.scaleX = 1f
            focusIndicator!!.scaleY = 1f
            focusIndicator!!.animate()
                    .scaleX(2f)
                    .scaleY(2f)
                    .setDuration(500)
                    .start()
        }

        override fun showFocusFinished() {
            focusIndicator!!.visibility = View.GONE
        }

        override fun showMessage(msg: String) {
            runOnUiThread{Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()}
        }

        override fun showError(error: String) {
            runOnUiThread{
                setResult(Activity.RESULT_CANCELED)

                AlertDialog.Builder(this@MainActivity)
                        .setMessage(error)
                        .setPositiveButton("Закрыть") { _, _ -> this@MainActivity.finish() }
                        .create()
                        .show()
            }
        }
    }

    private val videoButtonCallback = object : CameraControllerBase.VideoButtonCallback {
        override fun onClick(isPressed: Boolean) {
            if (isPressed) {
                videoBtn.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.video_btn_pressed))
                photoBtn.visibility = View.GONE
            } else {
                videoBtn.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.video_btn_not_pressed))
                photoBtn.visibility = View.VISIBLE
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.main_activity)

        waitView = findViewById<View>(R.id.customCameraActivity_waitView) as FrameLayout
        textureView = findViewById<View>(R.id.customCameraActivity_textureView) as AutoFitTextureView

        photoBtn = findViewById<View>(R.id.customCameraActivity_takePhoto) as ImageView
        photoBtn.setOnClickListener { cameraController!!.takePhoto() }

        videoBtn = findViewById<View>(R.id.customCameraActivity_takeVideo) as ImageView
        videoBtn.setOnClickListener { cameraController!!.makeVideo() }

        findViewById<View>(R.id.customCameraActivity_switchCamera).setOnClickListener { cameraController!!.switchCamera() }
        focusIndicator = findViewById(R.id.customCameraActivity_focusIndicator)

        if (checkPermission()) {
            init()
        } else {
            requestPermission()
        }
    }

    private fun init() {
        val outputFile: File? = getOutputPhotoFile()

        if (outputFile == null) {
            onError("Ошибка приложения. Не удалось создать файл сохранения снимка.")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            log("camera2 selected")

            this.cameraController = Camera2Controller(
                    this,
                    cameraControllerCallback,
                    outputFile!!.absolutePath,
                    this.lifecycle,
                    findViewById(R.id.customCameraActivity_textureView),
                    videoButtonCallback)
        } else {
            log("camera1 selected")    //TODO check Camera1Controller

            this.cameraController = Camera1Controller(
                    this,
                    cameraControllerCallback,
                    outputFile!!.absolutePath,
                    this.lifecycle,
                    findViewById(R.id.customCameraActivity_textureView),
                    videoButtonCallback)
        }
    }

    private fun checkPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }
        for (permission in REQUEST_PERMISSIONS) {
            if (ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }

    private fun requestPermission() {
        ActivityCompat.requestPermissions(this, REQUEST_PERMISSIONS, REQUEST_PERMISSION_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == REQUEST_PERMISSION_CODE) {
            if (grantResults.size == 2 && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                init()
            }
        }
    }

    private fun onError(errorMsg: String) {
        setResult(Activity.RESULT_CANCELED)

        AlertDialog.Builder(this@MainActivity)
                .setMessage(errorMsg)
                .setPositiveButton("Закрыть") { _, _ -> this@MainActivity.finish() }
                .create()
                .show()
    }

}
