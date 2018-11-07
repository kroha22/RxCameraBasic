package com.example.RxCameraBasic

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.widget.ImageView

import com.squareup.picasso.MemoryPolicy
import com.squareup.picasso.Picasso

import java.io.File

class ShowPhotoActivity : AppCompatActivity() {

    private var mPhotoView: ImageView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.show_photo_activity)
        mPhotoView = findViewById(R.id.showPhotoActivity_photo)
        val photoUrl = IntentHelper.getPhotoUrl(intent)
        Picasso.get()
                .load(File(photoUrl))
                .memoryPolicy(MemoryPolicy.NO_CACHE, MemoryPolicy.NO_STORE)
                .placeholder(R.drawable.ic_adb_black_24dp)
                .error(R.drawable.ic_error_outline_black_24dp)
                .into(mPhotoView)
    }

    object IntentHelper {
        private val EXTRA_PHOTO_URL = "EXTRA_PHOTO_URL"

        fun createIntent(context: Context, photoUrl: String): Intent {
            val intent = Intent(context, ShowPhotoActivity::class.java)
            intent.putExtra(EXTRA_PHOTO_URL, photoUrl)
            return intent
        }

        internal fun getPhotoUrl(intent: Intent): String {
            return intent.getStringExtra(EXTRA_PHOTO_URL)
        }
    }
}
