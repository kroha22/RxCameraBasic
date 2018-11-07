package com.example.RxCameraBasic

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import com.example.RxCameraBasic.rxcamera2.Camera2Controller
import java.io.File
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private var cameraController: CameraControllerBase? = null
    private var focusIndicator: View? = null
    private lateinit var videoBtn: ImageView

    private val cameraControllerCallback = object : CameraControllerBase.Callback {

        override fun onPhotoTaken(photoUrl: String) {
            val intent = ShowPhotoActivity.IntentHelper.createIntent(this@MainActivity, photoUrl)
            startActivity(intent)
        }

        override fun onVideoTaken(videoUrl: String) {
            Toast.makeText(this@MainActivity, "Video saved: $videoUrl", Toast.LENGTH_SHORT).show()
        }

        override fun onFocusStarted() {
            focusIndicator!!.visibility = View.VISIBLE
            focusIndicator!!.scaleX = 1f
            focusIndicator!!.scaleY = 1f
            focusIndicator!!.animate()
                    .scaleX(2f)
                    .scaleY(2f)
                    .setDuration(500)
                    .start()
        }

        override fun onFocusFinished() {
            focusIndicator!!.visibility = View.GONE
        }

        override fun onMessage(msg: String) {
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
        }

        override fun onCameraAccessException() {
            Log.d(TAG, "Camera Access Exception")
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        override fun onCameraOpenException(exception: Exception) {
            Log.d(TAG, "Camera Open Exception: " + exception.message)
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        override fun onException(throwable: Throwable) {
            throwable.printStackTrace()
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    private val videoButtonCallback = object : CameraControllerBase.VideoButtonCallback{
        override fun onClick(isPressed: Boolean) {
            if (isPressed){
                videoBtn.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.video_btn_pressed))
            } else {
                videoBtn.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.video_btn_not_pressed))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.main_activity)

        val outputDir = cacheDir // context being the Activity pointer
        val outputFile: File? = try {
            File.createTempFile("prefix", ".jpg", outputDir)
        } catch (ex: IOException) {
            Log.d(TAG, "Create output file error: " + ex.message)
            null
        }

        findViewById<View>(R.id.customCameraActivity_takePhoto).setOnClickListener { cameraController!!.takePhoto() }

        videoBtn = findViewById<View>(R.id.customCameraActivity_takeVideo) as ImageView
        videoBtn.setOnClickListener { cameraController!!.makeVideo() }

        findViewById<View>(R.id.customCameraActivity_switchCamera).setOnClickListener { cameraController!!.switchCamera() }
        focusIndicator = findViewById(R.id.customCameraActivity_focusIndicator)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Log.d(TAG, "camera2 selected")
            this.cameraController = Camera2Controller(
                    this,
                    cameraControllerCallback,
                    outputFile!!.absolutePath,
                    this.lifecycle,
                    findViewById(R.id.customCameraActivity_textureView),
                    videoButtonCallback)
        } else {
            Log.d(TAG, "camera1 selected")
            this.cameraController = com.example.RxCameraBasic.rxcamera.Camera1Controller(
                    this,
                    cameraControllerCallback,
                    outputFile!!.absolutePath,
                    this.lifecycle,
                    findViewById(R.id.customCameraActivity_textureView),
                    videoButtonCallback)
        }
    }

    /* private fun checkPermission(): Boolean {
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
                openCamera()
            }
        }
    }*/

    companion object {
        private val TAG = MainActivity::class.java.name

        //private val REQUEST_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)

        // private val REQUEST_PERMISSION_CODE = 233
    }

}
